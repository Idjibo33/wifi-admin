# Mission 15 — Rapport de tests

Date : 2026-11-09 (UTC). Device : Pixel 7 Pro (Android 17, API 37, `2B091FDH3004DG`).

Aucun benchmark n'a été lancé (ni Internet, ni LAN). Aucun port LAN ouvert (`8443` non ré-ouvert, UFW inchangé). Aucune modification routeur. La CI ne dépend ni du routeur physique ni de serveurs Internet.

## Matrice de tests JVM (déterminants)

Tous dans `app/src/test/.../` avec des fakes (`FakeLocalNetworkPermissionGateway`, `FakeNetworkMonitor`, `FakeRouterAdapter`, `StandardTestDispatcher`). Durée totale : ~20 s (`./gradlew test assembleDebug`).

| # | Cas (§11) | Test | Résultat |
|---|---|---|---|
| 1 | Permission déjà accordée | `LocalNetworkPermissionTest: Android 17 avec permission accordee passe` ; `RouterRepositoryPermissionGateTest: permission accordee autorise la connexion` (`connectCalls=1`) | **Vert** |
| 2 | Permission initialement refusée | `LocalNetworkPermissionTest: premiere demande requise...`, `refus simple reste redemandable` ; `RouterRepositoryPermissionGateTest: permission requise/refusee bloque la connexion sans socket` (`connectCalls=0`, `error is LocalNetworkPermissionDenied`) | **Vert** |
| 3 | Autorisation accordée après refus | `LocalNetworkPermissionTest: le fake gateway retient le refus puis le passage Granted` ; `RouterRepositoryPermissionGateTest: autorisation apres refus...` (1 seule connexion après accord) ; `LoginPermissionGateTest: apres accord un nouvel essai reussit` | **Vert** |
| 4 | Révocation pendant l'utilisation | `RouterRepositoryPermissionGateTest: revocation conserve la session...` (snapshot conservé, `connection Connected`, pas `SessionExpired`) ; `SpeedTestPermissionGateTest: revocation annule le run en cours` | **Vert** |
| 5 | Retour depuis les paramètres Android | `RouterRepositoryPermissionGateTest: retour des reglages apres re-accord...` (lecture unique après réaccord, erreur effacée) ; `LoginScreen` `LaunchedEffect(Granted)` efface le résultat | **Vert** |
| 6 | Absence de double retry | `LocalNetworkPermissionTest: le fake gateway ne lance jamais en double` (`launchCount=1` pendant le vol) ; appelants in-flight sans effet ; `ReadCoordinator` single-flight préserve 1 seule requête | **Vert** |
| 7 | Wi-Fi déconnecté | `NetworkDiagnosticsTest: wifi absent prime avant routeur et VPN` (`NO_WIFI`) + non-régression `NetworkChangeTest` et instrumenté `NetworkChangeInstrumentedTest` (3 cas, 81 s) | **Vert** |
| 8 | Changement d'identité Wi-Fi | `NetworkDiagnosticsTest: changement identite wifi...` (`NETWORK_CHANGED`) + `NetworkChangeTest` JVM | **Vert** |
| 9 | VPN actif avec LAN accessible | `NetworkDiagnosticsTest: VPN actif avec LAN accessible donne aucun probleme` (succès → `NONE`, sans hint) | **Vert** |
| 10 | VPN actif avec LAN inaccessible | `NetworkDiagnosticsTest: VPN actif avec echec router montre indication prudente` + `VPN actif avec timeout...` (hint prudent) | **Vert** |
| 11 | Timeout sans preuve VPN/permission | `NetworkDiagnosticsTest: timeout sans VPN ni permission reste indetermine...` (`TIMEOUT_UNKNOWN` ≠ `PERMISSION_REQUIRED`, sans hint) | **Vert** |
| 12 | Aucun benchmark déclenché par une autorisation | `SpeedTestPermissionGateTest: accord n-auto-demarre pas de benchmark` (`Idle`, `runCount=0` à l'accord, `=1` au tap) ; `permission requise bloque le demarrage...` (`runCount=0`, `PermissionRequired`) | **Vert** |
| 13 | API < 37 jamais demandée | `LocalNetworkPermissionTest: API 26 a 36...` + `cible SDK 36 ou moins...` (`NotRequired`, `launchCount=0`) | **Vert** |
| 14 | Seuil API 36 vs 37 | `LocalNetworkPermissionTest` (`requiresLocalNetworkPermission(36,37)=false`, `(37,37)=true`) — corrige l'ancien `BAKLAVA` | **Vert** |

**Total JVM : 439 tests, 0 échec, 0 skip** (`./gradlew test assembleDebug` → `BUILD SUCCESSFUL`). Les 3 non-régressions initialement rouges (`Mission09BoundaryTest`, `Mission10BoundaryTest` sur `https://` dans le KDoc, `SessionExpiryFlowTest` sur la purge `errorMessage`) ont été corrigées sans modifier les tests.

Non-régression historique préservée : `RouterRepositoryTest` (dispatcher IO, connect/refresh), `ReadCoordinatorTest` (single-flight, fresh 25 s), `RefreshCoordinatorTest` (back-off 30/60/120/300 s, cooldown), `PollingInvariantsTest` (timeout n'est pas expiry), `NetworkChangeTest`/`NetworkObservationTest`, `SpeedTestRoutingTest`/`SpeedTestRepositoryConcurrencyTest` (annulation libère le slot), `CacheDisplayTest`, `SessionExpiryFlowTest` (expiry seule renvoie au login), `Mission12BoundaryTest` (isolation ZLT vs LibreSpeed), FakeRouter scénarios (scénarios offline, pas de write, pas d'exposition MAC).

## Tests instrumentés sur Pixel 7 Pro

### Exécutés (preuves appareil réel)

| Test | Commande | Résultat |
|---|---|---|
| **Permission Compose** (nouveau) | `connectedDebugAndroidTest -P...class=dev.wifiadmin.app.LocalNetworkPermissionInstrumentedTest` | **BUILD SUCCESSFUL — tests=3 failures=0 skipped=0** (bandeau visible avant erreur ; refus affiche message + actions ; pas de bandeau quand `NotRequired`) — sans `pm grant` |
| Navigation/lifecycle | `NavigationInstrumentedTest` (via doublon de classe, puis re-run) | **Vert** (non-régression ; `BUILD SUCCESSFUL`) |
| Speed test (fake engine) | `SpeedTestInstrumentedTest` | **BUILD SUCCESSFUL — tests=12 failures=0** (consentement, pas de provider, plafonds, thèmes clair/sombre) |
| Changement de réseau | `NetworkChangeInstrumentedTest` | **BUILD SUCCESSFUL — tests=3 failures=0** (suspension 40 s / reprise 32 s réelles, sans routeur réel) |

Aucun test réseau public n'a été exécuté. `SpeedTestHttpsPingInstrumentedTest` n'a **pas** été relancé (Mission 14 déjà validée ; HTTPS local non ré-ouvert en Mission 15, aucun port `8443`).

### Non automatisables → recette manuelle détaillée

| Cas | Pourquoi non automatisable | Recette manuelle (à tracer datée) |
|---|---|---|
| **Premier lancement avec permission refusée** | Le dialogue système ne peut pas être cliqué par instrumentation de façon déterministe. | 1) Désinstaller l'app. 2) Lancer sur Pixel Android 17. 3) **Refuser** la permission dans le dialogue. 4) Vérifier : bandeau visible, aucun timeout réseau, bouton « Se connecter » reste disponible. 5) Taper « Se connecter » → erreur permission immédiate (pas de spinner 8 s). |
| **Accord depuis le dialogue système** | Interaction système hors processus. | 1) Depuis le bandeau, taper « Autoriser l'accès ». 2) **Accorder** dans le dialogue. 3) Vérifier : bandeau disparaît sans redémarrage. 4) Taper « Se connecter » avec identifiants valides → connexion OK, Dashboard chargé (une seule réponse). |
| **Accès au routeur après accord** | Nécessite un routeur réel (ZLT) sur le même Wi-Fi. | 1) Mêmes SSID + routeur. 2) Après accord, connecter → liste d'appareils chargée. 3) Taper « Actualiser » → une seule lecture (compteur Fake / logs réseau). |
| **Révocation depuis les paramètres** | Nécessite la navigation Réglages Android. | 1) Connecté au Dashboard avec données visibles. 2) Réglages → Applications → WiFi Admin → Autorisations → **Retirer** l'accès au réseau local. 3) Revenir dans l'app → polling arrêté, données conservées marquées « Accès au réseau local requis », pas de retour au login. |
| **Réautorisation** | Idem. | 1) Réaccorder dans les réglages. 2) Revenir → une seule lecture reprise, bandeau effacé, pas de double benchmark (écran vitesse reste `Idle`). |
| **Navigation et retour depuis les paramètres** | Idem. | Vérifier que le retour `onResume` recharge l'état (pas de stale « Autoriser » après accord). |
| **Récréation d'activité** | Rotation / process death. | 1) Ouvrir le dialogue d'autorisation. 2) Pivoter le téléphone. 3) Vérifier : pas de second dialogue (anti-doublon `isRequestInFlight`). |
| **VPN actif / inactif** | Nécessite la configuration VPN de l'appareil et, pour le full-tunnel, un serveur VPN. | 1) Sans VPN : connecter normalement. 2) Avec VPN split-tunnel autorisant le LAN : connecter, vérifier qu'aucune indication VPN n'est affichée en succès. 3) Avec VPN full-tunnel bloquant le LAN : tenter de joindre le routeur → indication **prudente** (« peut affecter »), jamais « bloque », avec bouton « Réessayer ». 4) Vérifier qu'aucune désactivation automatique du VPN ne se produit, et qu'aucun `bindProcessToNetwork` n'est utilisé. |

## Limites non validées

- Le **dialogue système et les réglages** ne sont pas automatisés : la recette manuelle ci-dessus n'a pas été exécutée dans cette session (aucun `pm grant` utilisé comme preuve — les instrumentés ci-dessus rendent l'état Compose via fakes).
- **Poignées réelles** : aucune connexion au routeur ZLT, aucun benchmark (ni Internet ni LAN), aucun port LAN ouvert, aucun pare-feu modifié.
- **Seuil API 37 exact** : le Pixel est en Android 17 (API 37) avec `targetSdk 37` ; la branche `targetSdk <= 36` sur appareil 37+ est couverte en JVM (`resolveLocalNetworkPermissionStatus`) mais pas sur un appareil graduellement rétrogradé.
- **VPN Always-on / lockdown** : comportement OS-dépendant, non simulé en JVM ni instrumenté (recette manuelle).
- **Types d'OS** : le groupe `NEARBY_DEVICES` et le reset du compteur de refus sont OS-dépendants ; l'app s'y adapte via l'état réel, sans boucle.
