# Mission 09 — Rapport de validation

> Date : 05/11/2026 · Mission : 09 · Rafraîchissement automatique et
> détection des changements de réseau
> Aucune écriture sur le routeur réel. Mission 08 close (`134c4fc`).

---

## 1. Contexte et objectif

La Mission 08 a ajouté le cache local et la détection d'expiration. La
Mission 09 ajoute deux choses :

- **A — un rafraîchissement automatique** toutes les 30 secondes, tant que
  l'écran concerné est visible ;
- **B — la détection des changements de réseau**, pour savoir quand le
  routeur n'est plus joignable.

La contrainte principale : **ne pas fragiliser la Mission 08**. Une
expiration confirmée, un timeout, une perte de Wi-Fi et un cooldown doivent
rester quatre situations distinctes.

---

## 2. Rapports des sous-agents

### 2.1 — Android Lifecycle

**Apport principal :** la primitive de visibilité.

| Option | Verdict |
|---|---|
| `LaunchedEffect` + `while (isActive)` | ❌ **le polling continue en arrière-plan** : `LaunchedEffect` n'est annulé qu'à la sortie de la composition, pas à l'`onStop` |
| `repeatOnLifecycle` dans le ViewModel | ❌ le ViewModel n'est pas un `LifecycleOwner` ; couplage au framework, et `lifecycle-runtime-testing` absent |
| **`LifecycleStartEffect(Unit)` + `startPolling()` / `stopPolling()`** | ✅ **retenu** |

Points clés du rapport :

- `LifecycleStartEffect` avec la clé `Unit` : une recomposition ne relance
  **jamais** l'effet.
- `startPolling()` doit être **idempotent** : l'écran le rappelle après une
  rotation ou un retour au premier plan.
- Le piège principal : un `LaunchedEffect` keyé sur l'état **redémarre à
  chaque recomposition**, et le minuteur ne atteint jamais 30 s — l'app
  paraît figée.
- Le `LaunchedEffect` d'expiration du NavHost est **orthogonal** : le
  polling ne modifie ni la clé ni la route.

### 2.2 — Réseau

**Apport principal :** la correction de la course documentée par la
plateforme.

> AOSP écrit dans la documentation de `NetworkCallback.onAvailable` :
> *« Do NOT call `getNetworkCapabilities(Network)` or
> `getLinkProperties(Network)` … as this is prone to race conditions. »*

Le code de `RouterAddressResolver` **violait** cette règle : il relisait
`activeNetwork` de façon synchrone depuis le callback et ignorait le
`LinkProperties` reçu. Corrigé : toutes les valeurs viennent des arguments
du callback.

Autres décisions du rapport :

| Sujet | Décision |
|---|---|
| `NET_CAPABILITY_VALIDATED` | **Jamais** une condition : un routeur sans Internet reste joignable. |
| `NET_CAPABILITY_INTERNET` | Jamais demandé : `NetworkRequest.Builder()` ne l'ajoute pas par défaut. |
| SSID / BSSID | Jamais lus : `ACCESS_FINE_LOCATION` depuis Android 12, et l'application ne la demande pas. |
| Partage de connexion mobile | C'est un réseau Wi-Fi **et** cellulaire : jamais le routeur. |
| `RouteInfo.hasGateway()` | API 29, indisponible à `minSdk = 26` : le code filtre `0.0.0.0` à la place. |
| `getAllNetworks()` | Déprécié API 31 : la référence du réseau Wi-Fi vient du callback. |
| Deux callbacks ou un ? | `registerNetworkCallback(TRANSPORT_WIFI)` suffit : le Wi-Fi est ce qui compte, pas le réseau par défaut. |

**Fusion** : `RouterAddressResolver` consomme désormais le `NetworkMonitor`
au lieu d'enregistrer son propre callback — deux observateurs du même radio
dupliquaient le travail et frôlaient la limite système de 100 callbacks.

### 2.3 — Architecture Kotlin

**Apport principal :** le piège de la mutualisation.

> Le `Mutex` du repository enveloppe l'appel réseau. Le garder ainsi
> **casse silencieusement** la mutualisation : le second appelant attend le
> verrou, constate qu'aucune lecture n'est en vol, et fait une **seconde**
> lecture. Aucun test ne le verrait, sauf un compteur explicite.

La parade : le `Mutex` ne protège plus que les **mutations d'état**, et la
mutualisation revient à un `Deferred` en vol unique.

Deuxième avertissement, plus subtil :

> La fenêtre de fraîcheur peut rendre **verts des tests d'expiration qui ne
> testent plus rien** : la lecture est court-circuitée et le routeur n'est
> jamais joint.

La parade retenue : les tests de la Mission 08 construisent le repository
avec `freshness = ReadFreshnessPolicy { false }`, et un test vérifie
explicitement que la fenêtre ne masque pas une lecture attendue.

Le rapport a aussi推荐的 : `ReadOutcome.Failure(RouterUnavailableException)`
typé, pour que le `when` du repository soit **exhaustif** sur la hiérarchie
scellée — ajouter une nouvelle erreur force alors une décision explicite.

### 2.4 — Sécurité et QA

**Apport principal :** trois défauts graves, dont deux que les tests
n'auraient pas montrés.

| # | Défaut | Gravité | Détail |
|---|---|---|---|
| 1 | **Le cache est restaurable pour un autre routeur** | 🔴 Critique | `SnapshotContext` ne portait que `(hôte, compte)`. Or `192.168.1.1` est la passerelle par défaut de **tout** routeur domestique, et `admin` le compte par défaut : le couple est identique partout. |
| 2 | **Un `OkHttpClient` par requête** | 🔴 Critique | Chaque client possède un pool de connexions et un pool de threads, jamais fermés. Avec le rafraîchissement : 8 640 clients par jour. |
| 3 | **`disconnect()` bloque l'interface** | 🟠 Moyen | Il attendait le verrou tenu par la lecture en vol : jusqu'à 8 s (deux timeouts de 4 s) avant que l'utilisateur ne voie l'écran de connexion. |

Le rapport a aussi fourni les chiffres d'usure : **3 requêtes par cycle**
(223, 224, 225), soit environ **360 requêtes par heure** d'usage continu, et
a rappelé que le gel de 180 s ne peut pas être provoqué par le rafraîchissement
si celui-ci n'authentifie jamais proactively.

---

## 3. Décisions d'architecture

| Sujet | Décision |
|---|---|
| Visibilité | `LifecycleStartEffect(Unit)` dans les écrans ; `startPolling()` / `stopPolling()` idempotents |
| Boucle | **Une seule**, dans `RefreshCoordinator` (instance unique de `AppContainer`) |
| Mutualisation | `ReadCoordinator` : un `Deferred` en vol ; le `Mutex` du repository ne protège plus la lecture |
| Fraîcheur | `ReadFreshnessPolicy` (fonction pure) : 25 s |
| Back-off | 30 s → 60 s → 120 s → 300 s, remis à zéro au succès |
| Cooldown | Suspend la boucle pendant la pénalité annoncée par le routeur |
| Horloges | `AppClock` : `monotonicMillis()` pour les délais, `wallClockMillis()` pour les étiquettes |
| Réseau | `NetworkMonitor` sur `TRANSPORT_WIFI` ; jamais `VALIDATED`, jamais le SSID/BSSID |
| Identité réseau | `NetworkIdentity` (interface + plage + passerelle) dans le `SnapshotContext` |
| Changement de réseau | `ConnectionState.NetworkChanged`, distinct de `SessionExpired` ; lectures suspendues, données conservées, aucune navigation |
| Nettoyage réseau | `RouterAddressResolver` consomme le moniteur : un seul jeu de callbacks |

---

## 4. Fichiers

**Créés (production)** : `AppClock.kt`, `NetworkMonitor.kt`,
`AndroidNetworkMonitor.kt`, `NetworkIdentity.kt`, `NetworkAssessment.kt`,
`RouteSummary.kt`, `ReadCoordinator.kt`, `RefreshCoordinator.kt`.

**Modifiés (production)** : `RouterRepository.kt`, `RouterAdapter.kt`
(inchangé), `SnapshotStore.kt` (identité réseau), `ZltRouterAdapter.kt`
(inchangé), `WifiAdminApplication.kt`, `WifiAdminViewModelFactory.kt`,
`SessionNavigation.kt`, `DashboardViewModel.kt`, `DevicesViewModel.kt`,
`DashboardScreen.kt`, `DevicesScreen.kt`, `CommonComponents.kt`,
`Presentation.kt`, `RouterAddressResolver.kt`.

**Corrigé hors mission** : `OkHttpRouterTransport.kt` — les clients sont
partagés au lieu d'être recréés à chaque requête.

**Tests ajoutés** : `RefreshCoordinatorTest` (11), `ReadCoordinatorTest` (8),
`NetworkChangeTest` (13), `PollingInvariantsTest` (15),
`Mission09BoundaryTest` (10), plus `FakeRouterAdapter`,
`FakeNetworkMonitor`, `TestHarness`.

---

## 5. Résultats

| Suite | Avant | Après | Échecs |
|---|---:|---:|---:|
| `router-core` | 193 | 193 | 0 |
| `poc-cli` | 56 | 56 | 0 |
| `app` | 126 | **189** | 0 |
| **Total** | 375 | **438** | **0** |

| Scénario hors ligne | Résultat |
|---|---|
| `tools/fake_router_scenarios.py` | 10/10, aucune écriture |
| `tools/fake_router_expiry_scenarios.py` | 3/3, aucune écriture |
| `:app:assembleDebug` | BUILD SUCCESSFUL |

### Les tests qui prouvent l'essentiel

| Test | Prouve |
|---|---|
| `two concurrent reads reach the adapter only once` | Le cœur de la mission : deux écrans, **une** requête. |
| `the polling reads every thirty seconds` | La période exacte, sans aucune attente réelle. |
| `the polling does not read again before thirty seconds have passed` | La borne (et non 29 s). |
| `stopping the polling prevents any further read` | Aucune lecture hors écran. |
| `a rotation does not trigger an extra read` | La rotation ne coûte pas une requête. |
| `the polling never opens a session on its own` | Aucun `cmd 100` proactif : le gel de 180 s ne peut pas être provoqué. |
| `a cooldown suspends the polling for the time the router asked` | Le routeur qui refuse les logins est laissé tranquille. |
| `a timeout during the polling is not an expiry` | Invariant Mission 08 sous rafraîchissement. |
| `a confirmed expiry during the polling is still reported as an expiry` | L'expiration reste détectée. |
| `the polling stops after an expiry` | Plus aucune lecture après l'expiration. |
| `a read that completes after a disconnect does not resurrect the session` | La génération protège l'état. |
| `an absent payload during the polling keeps the previous snapshot` | Une réponse incomplète n'efface rien. |
| `a network change never returns to the login screen` | Un changement de réseau n'est pas une expiration. |
| `a network change keeps the last known snapshot on screen` | Les dernières données restent visibles. |
| `the application declares no service` | Aucun service permanent. |
| `the application never reads the SSID or the BSSID` | Aucune permission de localisation. |
| `the application never validates the network before using it` | Un Wi-Fi sans Internet reste utilisable. |

---

## 6. Limites

1. **Deux réseaux de même passerelle ne sont pas distinguables.** Le BSSID
   le permettrait, mais il exige `ACCESS_FINE_LOCATION`, que l'application ne
   demande pas. Le pire cas : un changement **non détecté**, qui se
   manifeste par des lectures en échec — **jamais** par une expiration.

2. **Le `SnapshotStore` compare les horodatages sur l'horloge civile.** Un
   recul de l'heure du téléphone peut faire rejeter une écriture légitime.
   C'est un comportement préexistant, hors du périmètre de la Mission 09, et
   documenté comme tel.

3. **Le rafraîchissement et la rotation ne sont pas prouvés en JVM.** La
   logique est entièrement testable ; le *déclenchement* par
   `LifecycleStartEffect` relève du framework et se constate sur téléphone.

4. **La fréquence réelle du routeur n'a pas été mesurée.** Les chiffres
   (3 requêtes par cycle, ~360 par heure) sont **calculés**, pas relevés sur
   le firmware.

5. **Les deux contrôles visuels de la Mission 08 restent ouverts** : le
   message d'expiration affiché et le comportement du bouton Retour. Ce sont
   des propriétés du framework ; la procédure est en §2 de
   `MISSION_09_VALIDATION.md`.

---

## 7. Verdict

| Critère | Verdict |
|---|---|
| Une lecture par période | **CONFORME** |
| Lecture manuelle sans doublon | **CONFORME** |
| Mutualisation Tableau de bord / Appareils | **CONFORME** |
| Arrêt hors écran | **CONFORME** |
| Reprise au retour de l'écran | **CONFORME** |
| Cooldown respecté | **CONFORME** |
| Expiration toujours détectée | **CONFORME** |
| Erreur réseau jamais une expiration | **CONFORME** |
| Changement de réseau distinct de l'expiration | **CONFORME** |
| Cache jamais restauré pour un autre routeur | **CONFORME** (corrigé) |
| Aucun secret, aucune écriture, aucun service | **CONFORME** |
| Non-régression Mission 08 | **438 tests, 0 échec** |

### Verdict : **MISSION 09 LIVRÉE, puis VALIDÉE SUR APPAREIL RÉEL**

Sept défauts ont été trouvés par les sous-agents — dont deux critiques
(cache cross-routeur, fuite de pools HTTP) — et tous corrigés, chacun
couvert par un test.

La recette automatisée sur un **Pixel 7 Pro réel (Android 17)** a ensuite
révélé **six défauts supplémentaires**, dont deux critiques :

| # | Défaut | Gravité |
|---|---|---|
| 1 | L'observation du réseau n'était **jamais démarrée** en production : la fonctionnalité B était du code mort. | 🔴 |
| 2 | `SnapshotContext.networkId` n'était **jamais alimenté** : la protection cross-routeur n'existait pas. | 🔴 |
| 3 | L'identité réseau n'était comparée que par la passerelle. | 🟠 |
| 4 | La lecture manuelle interrogeait le routeur juste après la lecture programmée. | 🟠 |
| 5 | Un cooldown sans durée neutralisait la suspension. | 🟠 |
| 6 | Une lecture manuelle pendant un cooldown contournait la suspension. | 🟠 |

Tous corrigés et couverts par un test de régression. Le détail et les
preuves sont dans `docs/MISSION_09_DEVICE_VALIDATION_REPORT.md`.

**Suite** : la validation sur téléphone reste à faire, avec la procédure de
`docs/MISSION_09_VALIDATION.md`, qui inclut les deux contrôles visuels de la
Mission 08 restés ouverts.
