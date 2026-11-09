# Mission 15 — Permissions Android (`ACCESS_LOCAL_NETWORK`)

## Exigences Android

Documentation officielle — *Local network permission* (Android Developers).

| Cas | Comportement | Action |
|---|---|---|
| **API 26-35** (Android 8-15) | Aucun enforcement. `INTERNET` suffit. | Rien à demander. |
| **API 36** (Android 16) | L'ancien mécanisme opt-in (`RESTRICT_LOCAL_NETWORK` + `NEARBY_WIFI_DEVICES`) est **déprécié**. Ne pas l'utiliser en production. | Aucune demande runtime. |
| **API 37+ (Android 17) + `targetSdk >= 37`** | Réseau local **bloqué par défaut**. `ACCESS_LOCAL_NETWORK` est une runtime permission (groupe `NEARBY_DEVICES`). | Déclarer au manifest, **vérifier avant tout socket LAN** (`checkSelfPermission`), demander sur geste utilisateur. |
| `targetSdk <= 36` sur appareil 37+ | Grant implicite via `INTERNET` (temporaire). | Ne pas déclarer ni demander. |

Le dépôt cible `targetSdk = 37` et `compileSdk = 37` (`app/build.gradle.kts:23`), donc la déclaration manifest est **obligatoire et correcte**. Aucun `maxSdkVersion` n'est requis : le système ignore la permission sur appareil < 37.

Règle codée (fonction pure, sans Android) :

```kotlin
sdkInt >= 37 && targetSdk >= 37
```

Elle corrige l'ancien seuil `Build.VERSION_CODES.BAKLAVA` (36) qui demandait inutilement la permission sur Android 16.

## Implémentation

### Point de passage unique

`LocalNetworkPermissionGateway` — une **seule instance** dans `AppContainer` (`WifiAdminApplication.kt`), comme `networkMonitor` et `refreshCoordinator`. Aucun second système de permission n'existe.

- `data/LocalNetworkPermission.kt` : la décision **pure** `resolveLocalNetworkPermissionStatus()` (testable sur JVM), les 5 statuts (`NotRequired`, `Granted`, `Required`, `Refused`, `PermanentlyRefused`), `canAskAgain`, `isRequestInFlight` (anti-doublon) et `blocksLocalNetwork`.
- `data/AndroidLocalNetworkPermissionGateway.kt` : lit `ContextCompat.checkSelfPermission()` synchroniquement, délègue à la fonction pure, expose un `StateFlow`. `refreshStatus()` ne déclenche **jamais** de demande.
- `MainActivity` : seul pont Android — un seul launcher `RequestPermission`, `bindRationaleProvider`, `onResume()` (re-vérification, détection de révocation), appel `onRequestResult()` avec le rationale lu **avant** le résultat. **Plus aucune demande automatique au démarrage** (l'ancien `LaunchedEffect(Unit)` est supprimé).
- `RouterRepository` et `SpeedTestRepository` reçoivent le gateway en paramètre optionnel (compatibilité des tests historiques) ; en production, le conteneur passe toujours le gateway.

### Parcours d'autorisation

1. L'utilisateur ouvre l'écran de connexion : si l'état est `Required`/`Refused`, un bandeau explique que WiFi Admin lève l'autorisation pour joindre le routeur (local uniquement, jamais d'exfiltration).
2. L'utilisateur tape **« Autoriser l'accès »** → `gateway.requestIfNeeded { launcher.launch(...) }` (idempotent : sans effet si `NotRequired`/`Granted`/`PermanentlyRefused`/`inFlight`, re-vérification synchrone juste avant le lancement).
3. Le dialogue système s'ouvre **une seule fois**. `onRequestResult(granted, rationale)` traduit le résultat en transition d'état.
4. **Accord** → `RouterRepository.onLocalNetworkPermissionGranted()` efface l'erreur (`Error → Disconnected` ou nettoyage de `Connected`), `LoginScreen` efface `LoginResult.LocalNetworkPermissionDenied` (`LaunchedEffect` sur `Granted`), `RefreshCoordinator.resetFailures()` + reprise d'**une seule** lecture si la session est déjà ouverte (polling idempotent). Aucun nouveau benchmark, aucun redémarrage, aucune connexion automatique (le mot de passe reste en état Compose local, jamais en ViewModel).
5. **Refus simple** (`Refused`, `canAskAgain = true`) → le bouton « Autoriser l'accès » reste disponible ; le compteur de refus du groupe `NEARBY_DEVICES` est géré par le système.
6. **Refus permanent** (`PermanentlyRefused`, `canAskAgain = false`) → `requestIfNeeded` sans effet, bouton **« Ouvrir les paramètres »** (`ACTION_APPLICATION_DETAILS_SETTINGS`) proposé. Jamais de boucle infinie de demandes.

### Refus et révocation

**Refus** (premier refus) :
- Aucun socket n'est ouvert (`RouterRepository.connect()` court-circuite sous le verrou de session, avant même l'ouverture TCP — jamais de timeout générique).
- L'UI affiche « Accès au réseau local requis pour joindre le routeur. » avec « Autoriser l'accès » (si `canAskAgain`) et toujours « Ouvrir les paramètres ». Aucune demande concurrente, aucun affichage indéfini du dialogue.

**Révocation** (depuis les réglages Android, retour dans l'app) :
- `MainActivity.onResume()` → `gateway.refreshStatus()` synchrone → transition détectée (`Granted → Required/Refused/PermanentlyRefused`).
- `RouterRepository.onLocalNetworkPermissionRevoked()` : `invalidateFreshness()`, snapshot **conservé**, session **non expirée** (le routeur connaît encore la session ; seul le trafic est bloqué). Le polling est arrêté (`RefreshCoordinator.stopPolling()`), un speed test en cours est annulé (`SpeedTestRepository.onLocalNetworkPermissionRevoked()`), et — depuis la correction revue — aussi annulé directement depuis `MainActivity` même sans session routeur ouverte.
- Les lectures demandées pendant la révocation échouent **immédiatement** (`LocalNetworkPermissionDenied`), sans timeout répété, sans incrémenter le back-off de façon agressive.
- Au **retour des réglages** après réaccord (`onResume → Granted`), `onLocalNetworkPermissionGranted()` + `resetFailures()` + reprise d'une seule lecture. Le ViewModel « Login » efface l'ancien état d'erreur (`clearLocalNetworkPermissionDenied`), de sorte qu'un nouvel essai réussit immédiatement.

**Rotation / recréation d'activité** : l'état vit dans le gateway (scope application) et survit aux rotations ; `isRequestInFlight` survit au dialogue ouvert. Aucune demande dupliquée au `LaunchedEffect`.

### Compatibilité API

| Plateforme | Ce que fait l'app |
|---|---|
| API 26-36 ou `targetSdk <= 36` | `NotRequired` : aucune demande, aucun bandeau, aucun gate. Les sockets LAN passent tel quel (comportement historique préservé). |
| API 37+ avec `targetSdk 37` non accordée | Gate synchrone dans `connect()`/`performRead()`/`SpeedTestRepository.start()`. Aucune connexion LAN prématurée. |
| API 37+ avec `targetSdk 37` accordée | Gate passée, comportement inchangé (`Granted`). |
| Groupe `NEARBY_DEVICES` déjà accordé | Pré-accord du système (Bluetooth, etc.) : `checkSelfPermission()` retourne `Granted`, aucune re-demande. |

Le manifest garde la déclaration `ACCESS_LOCAL_NETWORK` (obligatoire pour `targetSdk 37`) ; le commentaire a été mis à jour (API 37, et non plus 36).

## Fichiers

- Production : `data/LocalNetworkPermission.kt`, `data/AndroidLocalNetworkPermissionGateway.kt`, `data/RouterAdapter.kt` (`LocalNetworkPermissionDenied`), `data/RouterRepository.kt` (gates + `onLocalNetworkPermissionRevoked/Granted`), `data/speedtest/SpeedTestEngine.kt` + `SpeedTestResult.kt` (`PermissionRequired`), `ui/login/LoginViewModel.kt` + `LoginScreen.kt` (mapping exhaustif, bandeau, clear), `ui/components/Presentation.kt` (`PERMISSION_DENIED`, message), `ui/dashboard|devices/*ViewModel.kt` + screens (bannière prioritaire), `ui/speedtest/*` (message + actions), `MainActivity.kt`, `WifiAdminApplication.kt`, `navigation/WifiAdminNavHost.kt` (propagation de l'état).
- Tests JVM : `LocalNetworkPermissionTest`, `RouterRepositoryPermissionGateTest`, `LoginPermissionGateTest`, `SpeedTestPermissionGateTest`, `NetworkDiagnosticsTest`.
- Tests instrumentés : `LocalNetworkPermissionInstrumentedTest` (3 cas Compose sur Pixel, sans `pm grant`).

## Limites

- Le parcours dialogue système / réglages n'est pas automatisable proprement (nécessite l'interaction utilisateur) : voir la recette manuelle dans `MISSION_15_TEST_REPORT.md`.
- La détection « refus permanent » repose sur `shouldShowRequestPermissionRationale()` lu **avant** le résultat système + mémoire interne des refus. Sur certains OEM, le système peut remettre le compteur à zéro (groupe `NEARBY_DEVICES`) : l'app re-proposera alors « Autoriser » — comportement acceptable, jamais une boucle.
- Aucun benchmark n'est déclenché par une autorisation (garde explicite `Idle` après accord).
