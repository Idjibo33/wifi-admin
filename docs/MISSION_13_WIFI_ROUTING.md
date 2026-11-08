# Mission 13 — Routage Wi-Fi des sockets OkHttp (sans `bindProcessToNetwork`)

> Date : 07/11/2026 · Branche : `feature/mission-13-speedtest-reel`
> Question : faire utiliser explicitement le `Network` Wi-Fi Android sélectionné par
> OkHttp (sockets, DNS, connexions, vérifications d'identité) sans lier tout le
> processus et sans casser les tests JVM (moteur pur JVM, MockWebServer loopback).
> Statut : **proposition architecturale uniquement** — aucun fichier de code n'est
> modifié par ce document. Contraintes inchangées : aucun test réel vers Internet,
> HTTPS obligatoire, aucun repli cellulaire, UI inchangée, `router-core` intouché.
> Skills : `systematic-debugging` (cause racine avant correctif), `security-audit`
> **guidance uniquement** (frontière de confiance, pas d'audit complet, aucun
> artefact d'audit produit).

---

## 1. Cause racine (phase 1 `systematic-debugging`)

Le défaut à traiter n'est pas « OkHttp choisit mal le réseau » ; c'est que **rien
ne contraint OkHttp à utiliser le Wi-Fi** :

- Aujourd'hui `SpeedTestEngineFactory.create()` construit un `OkHttpClient`
  générique (timeouts + `followRedirects=false`) sans `socketFactory` ni `dns`
  imposés. Les sockets et la résolution DNS suivent donc le routage par défaut
  d'Android, qui peut basculer sur l'cellulaire sans que le moteur le sache.
- Le repository protège *avant* et *pendant* le run (`monitor.currentStatus()`
  double revalidation + `watchNetworkLoss()` qui annule), mais son annulation
  est **réactive** : un socket déjà ouvert peut terminer son échange sur le
  mauvais réseau, et un `connect` commencé avant la perte ne sera détecté qu'à
  l'échec ou à l'annulation.
- `MISSION_13_ARCHITECTURE.md` §6.6 laissait ce point « ouvert » en retenant
  l'option conservative (système choisit + annulation). Cette proposition
  ferme le point ouvert **sans** recourir à `bindProcessToNetwork` (§3).

Hypothèse unique à tester (phase 3) : *si chaque run impose explicitement le
`Network` Wi-Fi courant через `SocketFactory` + `Dns` OkHttp, alors aucun octet
ne peut partir par l'cellulaire même si le routage par défaut change, et un
`Network` disparu fait échouer sûrement au lieu de basculer.*

## 2. Proposition : interface JVM-ok résolue par run

### 2.1 Interface pure JVM (nouveau fichier `WifiNetworkRouteProvider.kt`)

```kotlin
package dev.wifiadmin.app.data.speedtest

import java.net.SocketFactory
import okhttp3.Dns

/**
 * Périmètre réseau d'un run. Pure JVM : aucun import android.net.*.
 * null dans les tests JVM = aucun routage imposé (MockWebServer loopback).
 */
fun interface WifiNetworkRouteProvider {
    /** Fournisseur de la route à utiliser pour CE run, ou null si indisponible. */
    fun resolveForRun(): WifiRoute?
}

data class WifiRoute(
    val socketFactory: SocketFactory,
    val dns: Dns,
)
```

Justification JVM-ok : `SocketFactory` et `okhttp3.Dns` sont des types Java/OkHttp,
sans Android. Le test JVM injecte un fake (fabrique sans réseau ou null), la
production injecte l'adaptateur Android. `LibreSpeedEngine` et `LibreSpeedClient`
ne changent pas d'imports Android — ils restent testables sous `test/` avec
MockWebServer, exactement comme aujourd'hui.

### 2.2 Adaptateur Android (`AndroidWifiNetworkRouteProvider.kt`)

```kotlin
class AndroidWifiNetworkRouteProvider(
    private val connectivity: ConnectivityManager,
) : WifiNetworkRouteProvider {
    override fun resolveForRun(): WifiRoute? {
        // 1. Reprendre le Network Wi-Fi *actuel*, jamais un Network au constructeur.
        // 2. Exclure hotspot/tethering : TRANSPORT_WIFI && !TRANSPORT_CELLULAR
        //    (miroir de AndroidNetworkMonitor.statusOf + AndroidWifiLinkInfoProvider).
        // 3. Sans Wi-Fi exploitable -> null (= échec sûr, jamais de repli).
        // 4. Sinon :
        //    socketFactory = network.socketFactory
        //    dns = Dns { host -> network.getAllByName(host) }
        //    (getAllByName vide -> UnknownHostException, pas de liste vide)
    }
}
```

Trois choix structurants :

1. **Acquis par run, pas à la construction.** Le `Network` Android est un objet
   vivant : il peut disparaître entre deux runs. Capturer le `Network` dans
   `AppContainer` figerait une route périmée. La résolution se fait donc
   **en tête de `LibreSpeedEngine.runMeasurement()`** (après la double
   revalidation du repository, avant la première sonde). La course résiduelle
   est traitée en §4.
2. **Les deux canaux : `SocketFactory` pour les sockets, `Dns` pour la
   résolution.** `Network.socketFactory` lie les sockets au réseau (le
   `connect` passe par le Wi-Fi même si un autre réseau devient par défaut).
   `Network.getAllByName` impose le DNS du Wi-Fi (pas le DNS cellulaire/VPN).
   Sans `Dns`, la résolution pourrait retourner des adresses valides qui
   échoueraient hors Wi-Fi — ou pire, réussir via une autre interface.
3. **Échec sûr si le Wi-Fi disparaît.** `resolveForRun() == null` → `run()`
   lève immédiatement `SpeedTestFailureException(NoWiFi)` **avant tout appel
   réseau** (zéro octet). Si le `Network` disparaît *après* résolution, les
   appels lèvent `IOException`/`UnknownHostException`, déjà mappés en
   `ServerUnavailable` dans `LibreSpeedEngine.run()` (ou `Cancelled` si
   `client.isCancelled()`). Aucune bascule silencieuse : le mapping actuel
   suffit, il ne faut **pas** ajouter de `catch` qui re-essaie ailleurs.

### 2.3 Branchement moteur et fabrique (sans UI, sans `router-core`)

- `LibreSpeedEngine` reçoit `WifiNetworkRouteProvider? = null` en constructeur.
  En tête de `runMeasurement()` : `val route = provider?.resolveForRun()`
  (ou `null` si provider absent).
  - `route == null && provider != null` → échec sûr `NoWiFi`, sans socket.
  - `route != null` → le `OkHttpClient` de ce run est dérivé :
    `httpClient.newBuilder().socketFactory(route.socketFactory).dns(route.dns).build()`
    — l'instance partagée ne change pas ; chaque run obtient son client lié.
    `LibreSpeedClient` continue de recevoir un `OkHttpClient` (dérivé) : son
    seam n'est pas exposé à la UI (inchangé).
  - `provider == null` (tests JVM, MockWebServer) → client inchangé, aucun
    changement de comportement.
- `SpeedTestEngineFactory.create()` gagne un paramètre optionnel
  `routeProvider: WifiNetworkRouteProvider? = null` (défaut null → JVM safe).
  Signature rétro-compatible ; `DisabledSpeedTestEngine` inchangé.
- `WifiAdminApplication.AppContainer` : ajoute
  `AndroidWifiNetworkRouteProvider(connectivity)` et l'injecte dans
  `SpeedTestEngineFactory.create(BuildConfig.SPEEDTEST_BASE_URL, routeProvider = …)`.
  **Un seul** endroit connaît Android ; le moteur reste pur JVM.
- **Vérifications d'identité TLS** : on ne touche ni à `sslSocketFactory`, ni
  à `hostnameVerifier`, ni à `ConnectionSpec`. `Network.socketFactory` ne fait
  que transporter les octets ; OkHttp vérifie toujours le certificat contre
  l'hôte validé par `SpeedTestEndpointValidator` (HTTPS obligatoire inchangé).
  Le brouillage de canal (MITM sur Wi-Fi) n'est pas introduit par ce routage.

### 2.4 Cohérence avec la double revalidation `LocalWifi` du repository

Les trois couches restent complémentaires, aucune ne remplace l'autre :

| Couche | Rôle | Échec |
|---|---|---|
| `SpeedTestRepository.start()` + `execute()` | Garde-fou d'orchestration : refuse de démarrer hors `LocalWifi`, revalide après prise du slot, annule sur perte/changement d'`identity.key` via `watchNetworkLoss`. | `Failed(NoWiFi)` / `Cancelled`, moteur jamais touché. |
| `WifiNetworkRouteProvider.resolveForRun()` | Verrou d'**exécution** : lie sockets + DNS au `Network` Wi-Fi *du moment*, pas à la seule identité logique. | `NoWiFi` avant tout octet, ou `IOException` → `ServerUnavailable`. |
| `LibreSpeedEngine.run()` (mapping actuel) | Transforme tout échec réseau en `SpeedTestFailure` fermé, sans retry. | `ServerUnavailable` / `Timeout` / `Unexpected`. |

La provider ne duplique pas la logique `NetworkIdentity` (qui vit dans
`AndroidNetworkMonitor`) : elle applique seulement l'exclusion Wi-Fi/cellulaire
(`WIFI && !CELLULAR`) à l'instant du run. Si `AndroidNetworkMonitor` décide
`NotOnWifi` (hotspot), la provider retourne aussi `null` — même verdict, deux
vues du même `ConnectivityManager`.

## 3. Rejet explicite : `bindProcessToNetwork` global

`bindProcessToNetwork(network)` (Architecture §6.6) lierait **tout le processus**
— y compris le trafic routeur (`RouterRepository`, polling) et le DataStore —
à un seul réseau, à `unbindProcessToNetwork` à la fin du run. Rejeté :

- Portée excessive : un run de 30 s ne doit pas contraindre le polling routeur
  (déjà testé par session, pas par run).
- Fenêtre d'oubli : un `unbind` manqué (crash, `cancel` mal ordonné) laisserait
  l'app entière muette ou cellulaire.
- Même problème de course que `socketFactory`, sans le bénéfice de la
  résolution par run. L'interface JVM-ok prouve strictement mieux la
  propriété « pas de repli ».

Note sécurité (guidance) : à aucun moment on ne loggé ni ne persiste le
`Network`, l'hôte, ni l'IP résolue. Le rejet d'endpoint (Architecture §5.4)
continue de ne pas journaliser la chaîne fautive.

## 4. Limites résiduelles honnêtes

1. **Course revalidation → connect (TOCTOU).** Entre `execute()` (revalidation)
   et `resolveForRun()` puis le premier `connect`, le `Network` peut se perdre.
   Le run n'a alors que deux issues : `IOException` à la résolution ou au
   `connect` → `ServerUnavailable`, ou annulation via `watchNetworkLoss()`.
   La propriétés garantie n'est pas « le Wi-Fi ne disparaît jamais », mais
   « **aucun octet ne part par un autre réseau si le Wi-Fi disparaît** ».
2. **Tethering / hotspot.** L'exclusion `WIFI && !CELLULAR` couvre le hotspot
   mobile classique (transports cumulatifs). Elle ne couvre pas un hotspot créé
   *par le téléphone* vers des clients (cas inverse, hors sujet) ni un réseau
   Wi-Fi+cellulaire non cumulatif d'un fabricant exotique → appui sur
   `statusOf` du monitor, pas sur la seule provider.
3. **VPN / DNS privé / IPv6.** Un VPN actif peut intercepter le DNS du `Network`
   Wi-Fi ; `getAllByName` le suit (comportement Android attendu). Un Wi-Fi sans
   IPv6 sortant fera échouer honnêtement (`UnknownHostException` → `ServerUnavailable`).
4. **Multi-Wi-Fi.** `ConnectivityManager` ne expose qu'un `Network` Wi-Fi « roi »
   via `getAllNetworksByTransport` ; la provider prend le Wi-Fi courant
   cohérent avec `AndroidNetworkMonitor` (même source `activeNetwork`) — pas
   de choix arbitraire entre BSSID.
5. **Pas de preuve JVM de la bascule réelle** : voir §5.

## 5. Ce que les tests JVM peuvent prouver — et ce qu'ils ne peuvent pas

| Preuve JVM (`test/`, sans Android, sans Internet) | Comment |
|---|---|
| `provider == null` → comportement inchangé (MockWebServer loopback vert). | Tests `LibreSpeedEngineTest` existants non modifiés. |
| `provider` retourne `null` → `NoWiFi` levé **sans aucun appel réseau** (MockWebServer compte 0 requête). | Fake provider + MockWebServer. |
| Provider fake fourni → `socketFactory` et `Dns` du run sont ceux du fake (fake `Dns` enregistre les hôtes résolus, fake `SocketFactory` refuse la connexion → `ServerUnavailable`). | Assertion sur les hôtes vus par le fake `Dns`, pas sur le réseau. |
| `LibreSpeedEngine` ne réutilise pas le `Network` d'un run précédent (2 runs → 2 résolutions, compteurs de résolution = 2). | Fake provider compteur. |
| HTTPS toujours exigé : `http` hors loopback rejeté avant toute résolution (validateur inchangé). | Test `SpeedTestEndpointValidatorTest` existant. |
| Annulation : `cancel()` pendant un run lié → appelle fermés, `Cancelled` publié (invariants Architecture §9). | Test existant T7. |

Ce qu'ils **ne peuvent pas** prouver (validation manuelle sur appareil,
§17 Architecture) :

- Que `Network.socketFactory` lie réellement les sockets sur le matériel ciblé
  (comportement OEM).
- Que la bascule cellulaire n'a pas d'effet observé (données, comptage opérateur).
- Le TLS de bout en bout via le `Network` Wi-Fi (certificat, hote validé).
- L'interaction VPN / DNS privé / IPv6 d'un Wi-Fi réel.

Ces preuves exigent une recette appareil avec endpoint opérateur — **jamais** un
test automatisé vers Internet (contrainte du projet).

## 6. Fichiers touchés et ordre des tranches

| Fichier | Action | Contenu |
|---|---|---|
| `data/speedtest/WifiNetworkRouteProvider.kt` | **créer** | Interface pure JVM + `WifiRoute` (§2.1). |
| `data/speedtest/AndroidWifiNetworkRouteProvider.kt` | **créer** | Adaptateur Android (§2.2). |
| `data/speedtest/SpeedTestEngineFactory.kt` | modifier | Paramètre optionnel `routeProvider` (rétro-compatible). |
| `data/speedtest/LibreSpeedEngine.kt` | modifier | Résolution par run + dérivation du client (§2.3), aucun nouveau `catch`. |
| `WifiAdminApplication.kt` | modifier | Instanciation et injection de l'adaptateur Android. |
| `app/src/test/.../LibreSpeedEngineTest.kt` | modifier | Fakes provider (§5), zéro Android. |
| `docs/MISSION_13_WIFI_ROUTING.md` | **créer** | Ce document. |
| `router-core/` | **non touché** | Critère `git diff --stat router-core/` vide. UI : inchangée. |

Tranches verticales (une à la fois, `tdd` : un test → implémentation minimale → vert) :

1. **R1 — interface + fake JVM.** `WifiNetworkRouteProvider` + tests fake
   (null → `NoWiFi` sans réseau, fake `Dns`/`SocketFactory` reçus par le run).
2. **R2 — moteur.** Résolution par run + dérivation du client dans
   `LibreSpeedEngine`, paramètre optionnel dans la fabrique. Tests verts sur
   MockWebServer sans Android.
3. **R3 — adaptateur Android.** `AndroidWifiNetworkRouteProvider` (exclusion
   hotspot, `socketFactory`/`getAllByName`) + injection `AppContainer`.
   Aucun test JVM ne le couvre (manuel appareil).
4. **R4 — recette.** Build offline, suite JVM verte, aucun `https://` en dur,
   `router-core` vide, PR vers `develop`.

## 7. Arbitrages restants (à trancher avant R1)

- **D-R1 — Résoudre le `Dns` ou seulement les sockets ?** Recommandé : **les
  deux** (sans `Dns`, la résolution peut fuiter). Coût : un `Dns` fake à
  maintenir dans les tests JVM.
- **D-R2 — Résoudre dans le moteur ou dans le repository ?** Recommandé :
  **le moteur** (proximité au `run`, propriété « zéro octet si null » testable
  sans le repository). Le repository garde sa revalidation logique.
- **D-R3 — Re-dériver le `OkHttpClient` par run ou épingler le `Network` dans
  l'instance partagée ?** Recommandé : **dérivation par run** (le `Network`
  change ; l'instance partagée non liée reste aussi utilisée hors runs si
  besoin). Coût : un client par run (pool partagé, économique).
- **D-R4 — Faut-il journaliser la perte du `Network` (diagnostic) ?** Recommandé :
  **non**, ni hôte ni IP ; au plus un compteur local déjà couvert par
  `SpeedTestFailure`.
- **D-R5 — Le provider doit-il vérifier `LocalWifi` ou seulement le transport ?**
  Recommandé : **transport seul** (le repository décide de la légitimité
  logique ; la provider applique la physique). Éviter deux sources de vérité
  divergent.
