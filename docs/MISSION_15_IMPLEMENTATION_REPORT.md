# Mission 15 — Rapport d'implémentation

## Travaux réalisés

**Objectif** : rendre les connexions réseau robustes et compréhensibles (routeur ZLT, appareils connectés, rafraîchissements, LibreSpeed, changements Wi-Fi, permissions, VPN) sans modifier le protocole ZLT ni les algorithmes LibreSpeed, sans redémarrage après autorisation.

20 fichiers modifiés (~+743/−68 sur la branche) + 9 fichiers créés :
- **Nouveaux (production)** : `data/LocalNetworkPermission.kt` (décision pure), `data/AndroidLocalNetworkPermissionGateway.kt` (implémentation Android), `data/NetworkDiagnostics.kt` (`diagnoseNetwork()` pure).
- **Modifiés (production)** : `data/RouterAdapter.kt` (`LocalNetworkPermissionDenied`), `data/RouterRepository.kt` (gates `connect`/`performRead`, `onLocalNetworkPermissionRevoked/Granted`), `data/NetworkMonitor.kt` + `data/AndroidNetworkMonitor.kt` (`isVpnPresent()`, `currentStatus()` corrigé), `data/speedtest/SpeedTestEngine.kt` + `SpeedTestResult.kt` (`PermissionRequired` + gate + `onLocalNetworkPermissionRevoked`), `ui/login/LoginViewModel.kt` + `LoginScreen.kt` (mapping exhaustif, bandeau, clear), `ui/components/Presentation.kt` (`PERMISSION_DENIED`, messages) + `CommonComponents.kt` (slot `actions` dans `CachedDataBanner`), `ui/dashboard|devices/*ViewModel.kt` + screens (bannière prioritaire permission), `ui/speedtest/*` (message permission), `MainActivity.kt` (seul launcher, `onResume`, paramètres), `WifiAdminApplication.kt` (gateway singleton + callbacks), `navigation/WifiAdminNavHost.kt` (propagation), `AndroidManifest.xml` (commentaire API 37).
- **Nouveaux (tests)** : `LocalNetworkPermissionTest`, `RouterRepositoryPermissionGateTest`, `LoginPermissionGateTest`, `SpeedTestPermissionGateTest`, `NetworkDiagnosticsTest` (JVM) + `LocalNetworkPermissionInstrumentedTest` (Compose sur Pixel).
- **Docs** (4) : `MISSION_15_ANDROID_PERMISSIONS.md`, `MISSION_15_VPN_NETWORKING.md`, `MISSION_15_TEST_REPORT.md`, `MISSION_15_IMPLEMENTATION_REPORT.md`.

Aucun nouveau écran complet. Aucun second système de permission. Dossier non suivi `assets/` préservé (hors diff, hors commit).

## Causes racines

**Problème A — `ACCESS_LOCAL_NETWORK` déclarée mais non accordée** (validation Mission 14, Pixel `192.168.1.115`) :
- `MainActivity` demandait la permission au démarrage (`LaunchedEffect(Unit)`) avec un **callback vide** : accord/refus ignorés, aucune relance, aucun affichage.
- Seuil faux `Build.VERSION_CODES.BAKLAVA` (API 36) au lieu d'API 37 : demande inutile sur Android 16.
- Aucune gate avant `connect()`/`refresh()`/`speedTest.start()` : une connexion pouvait démarrer **avant** le résultat du dialogue → timeout réseau générique au lieu d'un message « permission requise ».
- Aucune détection de révocation : l'utilisateur révoquait dans les réglages, l'app continuait de poller (timeouts répétés, back-off).

**Problème B — VPN Proton** (validation Mission 14, `from /10.2.0.2`) :
- `AndroidNetworkMonitor.currentStatus()` lisait `activeNetwork` — sous VPN, c'est le réseau VPN, donc `NotOnWifi` abusif (alors que `observeStatus()` restait `LocalWifi`). Conséquence : `AndroidWifiNetworkRouteProvider.resolveForRun()` retournait `NoWiFi` à tort.
- `TRANSPORT_VPN` présent était le seul signal existant, sans classement prudent : risque de message « VPN bloquant » affirmé comme cause certaine.

## Décisions techniques

1. **Un seul gateway, fonction pure** : la décision `sdkInt >= 37 && targetSdk >= 37` est une fonction pure JVM (`resolveLocalNetworkPermissionStatus`), testable sans émulateur. `MainActivity` reste le seul pont Android (launcher, `onResume`, rationale). Les repositories et les écrans observent l'état via `StateFlow` — pas de nouveau écran, pas de second API.
2. **Gate synchrone avant tout socket** : `RouterRepository.connect()`/`performRead()` et `SpeedTestRepository.start()` vérifient `gateway.state.blocksLocalNetwork` **avant** tout appel réseau. Aucune connexion LAN prématurée sous Android 17+. Un timeout ne devient jamais « permission refusée » (seule la vérification réelle pose l'erreur).
3. **Session conservée à la révocation** : `onLocalNetworkPermissionRevoked()` invalide la fraîcheur, conserve session et snapshot (comme `NetworkChanged`), arrête le polling, annule le speed test. `MainActivity` annule en plus le speed test même sans session ouverte (polish revue).
4. **Reprise sans redémarrage, sans doublon** : accord → `onLocalNetworkPermissionGranted()` (effacement, `Error → Disconnected`, `resetFailures()`) + reprise d'**une seule** lecture si la session est déjà ouverte (`startPolling` idempotent + `ReadCoordinator` single-flight). Aucune connexion automatique (mot de passe jamais conservé), aucun benchmark auto (`Idle` après accord, seul le tap utilisateur relance).
5. **VPN : fait, pas verdict** : `isVpnPresent()` = présence de `TRANSPORT_VPN` dans `allNetworks` (lecture seule). `diagnoseNetwork()` (pur) ne pose `VPN_POSSIBLY_AFFECTING` qu'en indication prudente quand un échec `Unreachable`/`Timeout` coexiste avec un VPN. Jamais de désactivation, jamais de contournement, jamais de `bindProcessToNetwork`.
6. **États explicites, `when` exhaustifs** : `StaleDataReason.PERMISSION_DENIED` prioritaire, `LoginResult.LocalNetworkPermissionDenied`, `SpeedTestFailure.PermissionRequired`, `RouterUnavailableException.LocalNetworkPermissionDenied` (groupe « session conservée », jamais `expireSession`). L'ancien `else → InvalidCredentials` de `LoginViewModel`, qui aurait masqué le refus en « mauvais mot de passe », est remplacé par un `when` exhaustif.
7. **Testabilité** : gates en paramètres optionnels (`null` par défaut) — les tests historiques passent inchangés. `FakeLocalNetworkPermissionGateway` / `FakeNetworkMonitor.setVpnPresent()` permettent les cas JVM 1-14 de la Mission 15 §11.

## Skills utilisés

Skills réellement disponibles et appliqués (chargés via le tool `skill` avant toute modification) :

- `systematic-debugging` (Phases 1-4 : lecture d'erreur, reproduction, preuve au niveau composants — `tcpdump`/compteurs/route App UID de la Mission 14 réutilisés comme cause racine).
- `tdd` + `test-driven-development` (vertical-slice : `resolveStatus` → gate routeur → diagnostics → gate vitesse → login ; chaque test d'abord en RED, puis implémentation minimale GREEN ; red-green vérifié par revert mental).
- `verification-before-completion` (chaque claim précédé d'une commande fraîche : `./gradlew test assembleDebug`, `BUILD SUCCESSFUL`, 439 tests verts, XML instrumentés `tests=3/12/3 failures=0`).
- `security-audit` (mode guidance : aucune connexion prématurée, aucun contournement de permission, aucun secret dans les logs, aucune télémétrie, aucune double connexion, aucune boucle de retry, aucune mesure auto).
- `code-review` (revue indépendante 2 axes ci-dessous).

Non utilisés (raison) :
- `research` : la doc Android officielle a été lue via les sous-agents (règle API 37) ; pas de nouveau besoin de recherche externe pendant l'implémentation.
- `finishing-a-development-branch` / `pr` : la PR n'est pas encore ouverte — voir « Git et PR » ci-dessous (ils seront appliqués à l'ouverture, sans fusion).
- `browser`, `angular-*`, `firebase-*`, `laravel-*`, `xcode-project-setup`, `wizard` : hors périmètre Android local.

## Sous-agents effectivement mobilisés

Obligatoires par la Mission 15 §3 — **quatre sous-agents parallèles** (avant toute modification), reçus :

1. **Android Permissions** — audit `MainActivity`, cycle de vie, `RequestPermission`, refus/révocation/réautorisation, API 26-37+. Proposition gateway unique (pure `resolveStatus` + `Fake`), seuils API 37, anti-doublon, `onResume` révocation, reprise 1 lecture. *Suivie pour l'essentiel (facteur correctif « onResume » et « pas de dialogue auto » déjà appliqués).*
2. **Android Networking / VPN** — audit `AndroidNetworkMonitor`, `ConnectivityManager`, `TRANSPORT_VPN`, routage Wi-Fi/DNS, asymétrie `OkHttp` défaut vs `socketFactory` LibreSpeed, bug `currentStatus()` sous VPN. Proposition `NetworkDiagnostics` pur + correction `currentStatus` + sonde optionnelle (non retenue, pas de trafic LAN non sollicité). *Suivie (fonction pure retenue, sonde écartée avec raison).*
3. **Architecture Compose / UX** — états de connexion, navigation, messages, actions « Autoriser »/« Ouvrir les paramètres »/« Réessayer », synchronisation ViewModels, slot `actions` dans `MessageCard`/`CachedDataBanner`. *Suivie (pas de carte globale, pas de nouvel écran).*
4. **QA et sécurité** — matrice JVM 12 cas, non-régression FakeRouter/navigation/lifecycle, risques de concurrence, interdictions §15. *Suivie (matrice utilisée pour `*_TEST_REPORT.md`).*

**Revue indépendante post-implémentation** (5e agent, `code-review` 2 axes) :
- **Standards : 10/10 OK** — pas de 2e système, aucune connexion prématurée, aucun `bindProcessToNetwork`, VPN jamais désactivé/contourné, timeout jamais classé permission, aucun secret/télémétrie, aucun endpoint distant, `when` exhaustifs, pas d'écriture routeur, aucun littéral interdit.
- **Spec §6-10/§16 : OK** — seuil API 37, demande après action utilisateur, `onResume` révocation, accord → reprise sans redémarrage ni doublon ni benchmark, états explicites sans casser les tests.
- **Non-bloquants corrigés** : (1) `clearLocalNetworkPermissionDenied` orphelin → `LaunchedEffect(Granted)` dans `LoginScreen` ; (2) annulation speed test couplée à la session → annulation directe depuis `MainActivity.onResume` même sans session ; (3) commentaire manifest API 36 → 37. Code mort `hint && false` dans `NetworkDiagnostics` laissé tel quel (sans effet, lisible) ; `assets/` exclu du commit.

REA n'a pas été utilisé : aucun véritable travail de rétro-ingénierie n'a justifié de recourir à la décompilation (Mission 15 §3).

## Revue de sécurité

- Aucune connexion LAN prématurée lorsque l'autorisation est nécessaire mais absente (gates synchrones, jamais de socket).
- Aucun contournement de permission (jamais de `pm grant` comme preuve ou forçage dans le parcours utilisateur ; tests instrumentés rendent l'état Compose via fakes).
- Aucun accès routeur avec identifiants invalides (mot de passe jamais en ViewModel, jamais dans les logs — aucun `Log.` ajouté).
- Aucun envoi de secrets dans les logs, aucune télémétrie, aucun comportement réseau en arrière-plan non sollicité (polling arrêté à la révocation, speed test annulé).
- Aucune double connexion après changement de permission (idempotence `requestIfNeeded`, single-flight `ReadCoordinator`, garde `isConnecting`).
- Aucune boucle infinie de retry (back-off `RefreshCoordinator`, `PermissionsRefused` = polling suspendu, jamais de redemande auto).
- Aucun test de débit automatique après accord de permission (gate `Idle` + seul tap utilisateur).
- Annulation LibreSpeed préservée (`engine.cancel()`, `requireActive()` tous les 64 KiB, slot libéré — `SpeedTestRepositoryConcurrencyTest` vert).
- Isolation préservée entre le moteur Internet et le protocole routeur (aucun import croisé, `Mission12BoundaryTest` vert).

## Résultats CI

- **Local** : `./gradlew test assembleDebug` → **BUILD SUCCESSFUL** (439 tests JVM, 0 échec). Instrumentés Pixel : `LocalNetworkPermissionInstrumentedTest` (3/3), `SpeedTestInstrumentedTest` (12/12), `NetworkChangeInstrumentedTest` (3/3), `NavigationInstrumentedTest` vert.
- **CI GitHub Actions** : en attente (PR non encore ouverte). La CI est par design indépendante du routeur et d'Internet (jobs `jvm-tests` + `router-scenarios` loopback) ; en attente de ses 2 checks verts avant toute fusion.

## Git et Pull Request

- Branche : `feature/mission-15-android-network-permissions` (depuis `develop` `12ebc88`, `develop` synchronisé, `assets/` préservé non suivi).
- PR vers `develop` : **ouverte prochainement** (corps selon le skill `pr` : Summary/Evidence/Merge Danger). **Pas de fusion** sans autorisation explicite finale (attente du verdict CI).
- `main` non modifiée.

## Risques résiduels

1. **Dialogue système / réglages non automatisés** : la recette manuelle datée (`MISSION_15_TEST_REPORT.md`) reste à exécuter par un humain sur un Pixel Android 17 (ne rien forcer avec `pm grant`).
2. **Aucune validation sur routeur réel** : protocole ZLT inchangé et tests offline verts, mais la connexion HTTPS Mission 14 (Pixel ↔ Ubuntu) n'a pas été relancée depuis Mission 15 (intentionnel : aucun port `8443` ré-ouvert).
3. **VPN Always-on/lockdown** : comportement OS-dépendant, non validé (recette manuelle) ; l'app affiche l'indication prudente sans prétendre connaître la politique du VPN.
4. **Diversité OEM** : le groupe `NEARBY_DEVICES` et le reset du compteur de refus sont OS-dépendants ; l'app s'adapte via l'état réel mais ne peut pas garantir une 2e demande après « Ne plus demander ».

## Interdictions respectées (§15)

Aucun firmware modifié, aucune commande d'écriture routeur, commandes `232/100/223/224/225/278` inchangées, contrôle MAC non activé, limites LibreSpeed inchangées (`SpeedTestPolicy` 20/10/30 s), aucun serveur distant, aucun benchmark Download/Upload, port LAN `8443` non ouvert, UFW/iptables inchangés, certificats TLS inchangés, aucun VPN désactivé automatiquement, aucune permission forcée, aucune fusion sans autorisation.
