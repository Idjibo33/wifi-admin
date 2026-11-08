# Mission 13 — Audit du choix du réseau Wi-Fi (`firstOrNull`)

> Date : 08/11/2026 · Branche : `feature/mission-13-speedtest-reel`
> Périmètre : lecture de source uniquement, aucune exécution cible, aucun fichier de
> production modifié. Skills : `systematic-debugging` Phase 1 (cause racine avant
> correctif) et `security-audit` **guidance uniquement** (pas d'audit complet, pas
> d'artefact d'audit produit par le skill).
> Méthode : `AndroidWifiNetworkRouteProvider.kt`, `WifiNetworkRouteProvider.kt`,
> `LibreSpeedEngine.kt` (`resolveForRun`), `SpeedTestEngine.kt` (repository
> `start`/`execute`/`watchNetworkLoss`), `NetworkMonitor.kt`,
> `AndroidNetworkMonitor.kt`, `WifiAdminApplication.kt`,
> `docs/MISSION_13_WIFI_ROUTING.md`.

## 1. Constat — prouvé par le code (lecture seule)

**Le `allNetworks.firstOrNull` est arbitraire.** `AndroidWifiNetworkRouteProvider.resolveForRun()`
(`app/src/main/java/dev/wifiadmin/app/data/speedtest/AndroidWifiNetworkRouteProvider.kt:26-31`)
parcourt `connectivity.allNetworks` dans l'ordre livré par la plateforme et prend le
**premier** `Network` vérifiant `TRANSPORT_WIFI && !TRANSPORT_CELLULAR`. Aucun filtre
supplémentaire, aucune préférence pour le réseau actif, aucune identité.

En revanche le repository valide **le réseau actif** :
`AndroidNetworkMonitor.currentStatus()` lit `manager.activeNetwork`
(`app/src/main/java/dev/wifiadmin/app/data/AndroidNetworkMonitor.kt:118`), applique
`statusOf()` (`:127-151`) — exclusion hotspot (`WIFI && CELLULAR` → `NotOnWifi`,
`:136-140`), non-Wi-Fi → `NotOnWifi`, puis `identity()` buildée sur
`LinkProperties.interfaceName` + plage d'adresses + passerelle (`:159-179`) —
et exige `LocalWifi` avec passerelle non nulle (`:145-150`, sinon `NoWifi`).
Le repository enchaîne : `start()` (`app/src/main/java/dev/wifiadmin/app/data/speedtest/SpeedTestEngine.kt:197-210`)
et `execute()` (`:254-267`) re-vérifient `LocalWifi` et la stabilité de `identity.key`,
et `watchNetworkLoss()` (`:353-370`) annule sur perte ou changement d'identité.

Résultat : **trois sources de vérité coexistent sans lien entre elles à l'instant du run** :

| Source | Fichier:ligne | Réseau choisi |
|---|---|---|
| Callback `observeStatus()` | `AndroidNetworkMonitor.kt:48-104` (`current: Network?` mis à jour par `onAvailable`) | Le réseau Wi-Fi ayant émis le dernier `onAvailable`, avec `LinkProperties` livrées par le callback (anti-course assumée, `:28-33`). |
| `currentStatus()` | `AndroidNetworkMonitor.kt:116-125` | `activeNetwork` (réseau par défaut). |
| `resolveForRun()` | `AndroidWifiNetworkRouteProvider.kt:26-31` | `allNetworks.firstOrNull { WIFI && !CELLULAR }` (ordre de plateforme, non spécifié). |

**Scénario de divergence prouvé par le code seul :** deux `Network` Wi-Fi coexistent
(`activeNetwork = A`, `allNetworks = [B, A]`). Le repository valide `A`
(`currentStatus()` → `LocalWifi(identity(A))`), puis `LibreSpeedEngine`
(`app/src/main/java/dev/wifiadmin/app/data/speedtest/LibreSpeedEngine.kt:73-85`)
résout `route` via `resolveForRun()` et lie les sockets + DNS à `B`. Le débit mesuré
est celui de `B`, alors que l'enrichissement du résultat attribue le run à l'identité
de `A` (`SpeedTestEngine.kt:297-308` : `networkKey = expectedNetworkKey`).
Le code ne contient aucun contrôle croisant ces deux choix.

La provider ignore en outre deux conditions que le monitor impose :
la présence d'une passerelle exploitable (`AndroidNetworkMonitor.kt:145-150`) et
l'identité `interfaceName`/`addressPrefix` (`NetworkIdentity.kt:28-45`). Elle peut donc
démarrer un run sur un Wi-Fi sans route par défaut (le monitor dirait `NoWifi`) ou sur
un Wi-Fi d'une autre interface que le réseau validé.

## 2. Cause racine (Phase 1 `systematic-debugging`)

**Cause racine : absence de liaison entre l'identité logique validée par le repository
(`NetworkIdentity`, issue de `activeNetwork`) et l'objet physique `Network` choisi par la
provider (`allNetworks.first`) pour lier les octets.** Le filtre de la provider ne retient
que le transport ; il ne retient ni `interfaceName`, ni passerelle, ni statut actif,
ni identité. Il n'y a donc aucune invariant « le run est lié au réseau que le repository
a validé » — seulement deux gardes indépendantes (repository = orchestration,
provider = exécution, cfr. `docs/MISSION_13_WIFI_ROUTING.md` §2.4) qui peuvent diverger
silencieusement. C'est un soupçon de « garde juste » à la couche orchestration alors que
la couche exécution choisit ailleurs : symptôme (débit d'un autre réseau, attribution
fausse, voire traffic facturé) ≠ cause (sélection non contrainte).

La branche `observeStatus()` vs `currentStatus()` aggrave le tableau : le callback
suit un `Network` Wi-Fi même quand il n'est plus par défaut (`:19-23`), tandis que
`currentStatus()` ne voit que `activeNetwork`. Le `watchNetworkLoss()` suit donc
l'identité du réseau Wi-Fi-suivi, pas forcément celle de `activeNetwork` : une bascule
de défaut Wi-Fi → cellulaire avec Wi-Fi toujours présent ne fait pas « perdre » le
Wi-Fi-suivi (le callback ne reçoit pas `onLost`), mais fait échouer `currentStatus()`
(`NotOnWifi`). Ce n'est pas le correctif à appliquer ici, mais cela confirme que
`interfaceName` n'est pas une preuve physique-stable sans re-lecture au moment du run.

## 3. Correctif recommandé (minimal, sans `bindProcessToNetwork`)

Principe (skill guidance : plus petit changement à l'invariant, au dernier point de
confiance) : **faire relire au provider l'identité validée et filtrer les candidats sur
cette identité.** Aucun `bindProcessToNetwork`, aucune signature JVM à casser,
`provider == null` en tests inchangé (`LibreSpeedEngine.kt:73-85` : si `routeProvider`
est nul, le client partagé est utilisé tel quel — comportement JVM préservé).

1. `AndroidWifiNetworkRouteProvider.kt:26` — injecter le même `NetworkMonitor` singleton
   déjà câblé dans `AppContainer` (`WifiAdminApplication.kt:68` et `:139-146`), par exemple
   `class AndroidWifiNetworkRouteProvider(connectivity, private val monitor: NetworkMonitor)`
   ou un fournisseur `() -> LocalNetworkStatus`. Sans objet Android dans
   `WifiNetworkRouteProvider.kt` (pure JVM, `:9-10`) : l'interface
   `fun interface WifiNetworkRouteProvider` (`:29-31`) **n'est pas modifiée**.
2. `AndroidWifiNetworkRouteProvider.kt:26-31` — dans `resolveForRun()` :
   a. appeler `monitor.currentStatus()` **à cet instant** (re-vérification `LocalWifi`
      au moment du run, pas seulement dans `SpeedTestEngine.kt:254` à l'entrée d'`execute()`) ;
   b. si ce n'est pas `LocalWifi` ou si `identity.gateway == null` → retourner `null`
      (échec sûr `NoWiFi`, cf. `LibreSpeedEngine.kt:74-77`) ;
   c. parcourir `allNetworks` en filtrant par transport (`WIFI && !CELLULAR`, inchangé)
      **et** `connectivity.getLinkProperties(candidate)?.interfaceName == status.identity.interfaceName`
      (et, si disponible, passerelle non nulle via `LinkProperties`) ;
      préférer `connectivity.activeNetwork` quand il satisfait ces filtres.
3. `WifiAdminApplication.kt:132-134` — passer `networkMonitor` à `AndroidWifiNetworkRouteProvider`
   (instance unique `:68`, pas un second `AndroidNetworkMonitor`).

Cette forme garde le choix transport-only à l'interface (`WifiNetworkRouteProvider.kt:29`)
mais la pousse sur le candidat cohérent à l'identité. Alternative écartée comme non
minimale : changer la signature en `resolveForRun(expectedInterface: String?)` —
plus propre à terme, mais casse les fakes JVM existants ; elle peut suivre ce premier
pas (ajouter une méthode avec implémentation par défaut déléguant à `resolveForRun()`).

## 4. Limites résiduelles honnêtes (à documenter, pas à promettre)

1. **TOCTOU irréductible.** Entre la revalidation `execute()` (`SpeedTestEngine.kt:254`),
   la re-lecture `currentStatus()` dans `resolveForRun()` et le premier `connect`,
   `activeNetwork` peut changer. La propriété garantie est « aucun octet ne part par
   un autre réseau si le Wi-Fi disparaît » (échec `ServerUnavailable`/
   `UnknownHostException` via `LibreSpeedEngine.kt:52-60` ou `Cancelled` via
   `watchNetworkLoss()`), pas « le Wi-Fi ne disparaît jamais ».
2. **Multi-Wi-Fi limité par la plateforme.** Sans SSID/BSSID (volontairement illisibles,
   `NetworkIdentity.kt:20-23`), `interfaceName` (`wlan0`) peut être partagé ou `null`
   selon l'OEM/API ; deux réseaux Wi-Fi distincts peuvent rester ambigus. Le filtre
   `interfaceName` réduit le choix à un ensemble cohérent, il ne prouve pas le même
   point d'accès.
3. **VPN / DNS privé / IPv6.** `network.getAllByName()` suit le DNS du réseau choisi,
   y compris un VPN interposé ; aucun supplémentaire n'est requis ni ne manquerait dans
   le code, mais le comportement est celui de la plateforme, variable selon l'OEM.
4. **Hotspot non-cumulatif / OEM exotiques.** Le filtre `WIFI && !CELLULAR` couvre le
   hotspot mobile classique ; un réseau Wi-Fi+cellulaire non cumulatif échapperait
   à cette seule vue — l'appui reste sur `statusOf()` du monitor, pas sur la provider.
5. **Aucune preuve JVM de la bascule.** Les tests JVM peuvent prouver « `provider == null`
   → comportement inchangé », « fake `null` → `NoWiFi` sans requête », « fake route →
   sockets/DNS du fake ». Ils ne prouvent pas que `Network.socketFactory` lie réellement
   sur le matériel finaux ni que les octets ne fuient pas en cellulaire : **recette
   appareil avec endpoint opérateur requise** (cf. `docs/MISSION_13_WIFI_ROUTING.md` §5).

## 5. Ce qui est prouvé ici vs pas

- **Prouvé par le code seul :** `firstOrNull` peut retourner un `Network` différent de
  celui validé par `currentStatus()`/`activeNetwork` ; aucune liaison d'identité
  n'existe entre les deux ; le correctif minimal ci-dessus ne touche ni `WifiNetworkRouteProvider.kt`,
  ni `LibreSpeedEngine.kt`, ni les tests JVM (`provider == null`).
- **Pas prouvé ici (exige recette appareil) :** que `allNetworks` contient effectivement
  un second Wi-Fi sur les appareils cibles, que `LinkProperties.interfaceName` est
  renseigné des deux côtés, et que le débit attribué suit bien le réseau lié après
  correctif. Ne pas présenter la recommandation comme prouvée sans ces mesures.
