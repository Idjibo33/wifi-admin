# Mission 15 — VPN et réseau

## Fonctionnement réseau Android

`ConnectivityManager` ne connaît pas « le VPN de l'outil X » ; il expose des **réseaux et leurs capabilities** :

- `allNetworks` : chaque réseau vivant (Wi-Fi, cellulaire, Ethernet, VPN `tun0`, hotspot).
- `getNetworkCapabilities(network)` par réseau :
  - `hasTransport(TRANSPORT_WIFI)`, `TRANSPORT_CELLULAR`, `TRANSPORT_ETHERNET`, `TRANSPORT_VPN`.
  - `hasCapability(NET_CAPABILITY_INTERNET)`, `NET_CAPABILITY_VALIDATED` (Internet uniquement), `NET_CAPABILITY_NOT_VPN`.
- `activeNetwork` / réseau par défaut : le réseau retenu par Android pour un `Socket` non lié. Avec un VPN full-tunnel, c'est le VPN ; sinon le Wi-Fi.
- `Network.socketFactory` + `Network.getAllByName()` : socket et DNS **liés à un réseau précis**. C'est le mécanisme du moteur LibreSpeed.

### Le bug corrigé : `currentStatus()` sous VPN

Avant Mission 15, `AndroidNetworkMonitor.currentStatus()` lisait `activeNetwork` — sous VPN, c'est le **VPN**, donc `NotOnWifi` alors même que le Wi-Fi est vivant dans `allNetworks`. Conséquences :

- `AndroidWifiNetworkRouteProvider.resolveForRun()` retournait `null` → LibreSpeed échouait `NoWiFi` abusivement (alors que le flux `observeStatus()`, lui, restait `LocalWifi`).
- `RouterRepository.onNetworkStatus` (sur le flux) ne passait jamais en `NetworkChanged` pour une raison VPN, et `performRead` continuait d'appeler l'adaptateur sur la voie VPN.

**Correction** : `currentStatus()` cherche désormais le candidat Wi-Fi (non-hotspot) dans `allNetworks` via `TRANSPORT_WIFI && !TRANSPORT_CELLULAR` avec route par défaut, comme `observeStatus()`. Le flux et le snapshot divergent plus.

### Routage réel

| Cible | Voie | Comportement sous VPN |
|---|---|---|
| **Routeur ZLT** (`ZltRouterAdapter`, `OkHttpRouterTransport`) | `OkHttpClient` **par défaut** (suit le réseau par défaut, donc le VPN si actif) | Échec LAN possible en full-tunnel sans exclusion LAN. |
| **LibreSpeed** (`LibreSpeedEngine` + `AndroidWifiNetworkRouteProvider`) | `socketFactory` + DNS **épinglés au Wi-Fi** via `network.socketFactory` / `network.getAllByName()` | Le paquet sort par l'interface Wi-Fi même si le défaut est le VPN (split-tunnel ou pas, sauf lockdown qui refuse ces sockets). |

Conséquence : sous VPN, le speed test peut réussir (Internet via Wi-Fi épinglé) alors que la liste d'appareils échoue (LAN via VPN), ou l'inverse. Les deux mesures ne deviennent pas fausses — elles mesurent des voies différentes.

## Comportement VPN

### Ce que l'app détecte

`NetworkMonitor.isVpnPresent()` (défaut `false` pour compatibilité des fakes) :

- Production : vrai si un réseau `TRANSPORT_VPN` est présent dans `allNetworks` (lecture seule de capabilities, jamais de désactivation).
- Test : `FakeNetworkMonitor.setVpnPresent()`.

**Jamais** d'appel de désactivation VPN, **jamais** de contournement silencieux, **jamais** de `bindProcessToNetwork` global (refusé par design : il détournerait tout le trafic, routeur inclus), respect des politiques Always-on et lockdown (si lockdown est actif et refuse les sockets Wi-Fi épinglés, le diagnostic le reflète comme échec — jamais de forçage).

### Ce que l'app ne peut pas savoir par API publique

- Les politiques du VPN (applications exclues, routes permises) — pas de getter public donnant les « allowed apps/routes » de `VpnService`.
- Le SSID/BSSID (nécessite `ACCESS_FINE_LOCATION`, volontairement non demandée).
- Le DNS privé split-horizon du VPN.

Le diagnostic se limite donc aux **faits observables** : transports, interface, gateway, statut Wi-Fi, état de la permission, type d'échec.

### Pourquoi « VPN présent » n'est pas « LAN inaccessible »

- **Split-tunnel** : `VpnService.Builder.addRoute` peut exclure `192.168.1.0/24`. `TRANSPORT_VPN` reste visible, le LAN est joignable en direct.
- **Socket Wi-Fi épinglé (LibreSpeed)** : le trafic sort par le Wi-Fi même si le défaut est le VPN.
- **Lockdown** : à l'inverse, un VPN en « Bloquer les connexions sans VPN » peut **refuser** les sockets du Wi-Fi sous-jacent, alors que la route Wi-Fi « existe ».

Seule une **sonde de joignabilité** (socket 2 s vers le routeur sur la voie voulue) prouverait l'accessibilité — non implémentée ici car elle émettrait du trafic LAN non sollicité. Le diagnostic se contente donc d'une **indication prudente** quand un échec coexiste avec un VPN.

### Message prudent

Affiché **uniquement** quand un échec `Unreachable`/`Timeout` coexiste avec un VPN présent (ni en succès, ni quand la permission bloque, ni quand le Wi-Fi manque) :

> « Un VPN est actif et peut affecter la connexion à votre routeur. Vérifiez ses paramètres d'accès au réseau local, puis réessayez. »

- Modalité « peut » (jamais « bloque »), **jamais** de cause certaine sans preuve.
- Routeur inaccessible + VPN → indication prudente ; VPN + succès → aucune indication (la preuve du succès l'emporte) ; timeout sans VPN ni permission → `TIMEOUT_UNKNOWN` sans indication VPN (jamais transformé en « permission refusée »).
- Écrans : `DashboardScreen` et `DevicesScreen` (`INFONOTICECARD`, tonneau `secondaryContainer`, tag `vpn_hint`), jamais sur `LoginScreen`.

### Classement des erreurs (jamais de confusion)

`data/NetworkDiagnostics.kt` — fonction **pure** (`diagnoseNetwork()`), testable sur JVM :

| Entrée | Sortie |
|---|---|
| Permission bloque (`blocksLocalNetwork`) | `PERMISSION_REQUIRED`, sans hint VPN |
| Échec `LocalNetworkPermissionDenied` | `PERMISSION_REQUIRED`, sans hint VPN |
| `NoWifi` / `NotOnWifi` | `NO_WIFI`, sans hint VPN |
| `DifferentNetwork` | `NETWORK_CHANGED`, sans hint VPN |
| Succès (`failure == null`) | `NONE`, sans hint VPN |
| `Unreachable` + VPN | `ROUTER_UNREACHABLE`, hint prudent |
| `Timeout` + VPN + permission OK | `TIMEOUT_UNKNOWN`, hint prudent |
| `Timeout` sans VPN/permission | `TIMEOUT_UNKNOWN`, sans hint VPN, **≠ `PERMISSION_REQUIRED`** |
| `Unreachable` sans VPN | `ROUTER_UNREACHABLE`, sans hint VPN |

Règle d'or (Mission 15 §7) : **un timeout ne devient jamais « permission refusée »**. La permission n'est affirmée que sur vérification réelle de l'état du gateway.

## Risques de routage

- **Pas de `bindProcessToNetwork`** : le routeur et LibreSpeed suivent leurs voies propres ; lier le processus au Wi-Fi détournerait tout le trafic du système.
- **Race de réseau** : un `Network` peut disparaître entre `resolveForRun()` et `fetchDevices()`. La route est re-résolue **par appel**, jamais snapshotée au constructeur (déjà le cas LibreSpeed ; conservé).
- **Hotspot** : filtre `WIFI && !CELLULAR` obligatoire des deux côtés ; une absence Wi-Fi + présence VPN ne vaut jamais `NotOnWifi` sans vérifier `allNetworks` d'abord.
- **Réseau sans Internet** : `NET_CAPABILITY_INTERNET` / `VALIDATED` ne conditionnent jamais l'autorisation routeur (règle déjà en place dans `AndroidNetworkMonitor`).
- **DNS non aligné** : LibreSpeed résout via le DNS du Wi-Fi (`network.getAllByName`) ; ZLT utilise un hôte IP littéral (`192.168.1.1`), donc pas de DNS dans le chemin routeur. Un DNS privé du VPN masquant l'hôte affecterait LibreSpeed mais pas ZLT.

## Diagnostics et limitations

- `AndroidNetworkMonitor` expose les faits (Wi-Fi/hotspot, gateway, VPN présent) sans sonde réseau ; aucune sonde LAN n'est émise.
- Un timeout reste `TIMEOUT_UNKNOWN` (origine indéterminée) : l'app ne prétend pas en connaître la cause.
- Sous VPN full-tunnel sans exclusion LAN, le routeur échouera `Unreachable`/`Timeout` jusqu'à l'accord de l'utilisateur — le back-off de `RefreshCoordinator` (30/60/120/300 s) et le cooldown routeur (180 s) évitent de marteler ; aucune suspension de session n'est faite (le routeur connaît encore la session).
- La suppression du hotspot et l'exclusion `CELLULAR` sont testées via les fakes ; le comportement Always-on/lockdown est OS-dépendant et ne peut pas être simulé en JVM (recette manuelle, voir `MISSION_15_TEST_REPORT.md`).
