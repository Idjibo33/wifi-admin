# Mission 13 — Notes UI Speedtest (implementation Compose)

> Date : 08/11/2026 · Agent « Implémentation Jetpack Compose » pour WiFi Admin.
> Périmètre strictement respecté : **aucun fichier sous `data/speedtest/`, aucun fichier de `router-core/`,
> aucun `build.gradle.kts` touché** (vérifié par `git diff --name-only`). Un autre agent possède le moteur ;
> ce document note ce qui manque côté data au lieu de modifier ce layer.

## Fichiers modifiés

| Fichier | Rôle |
|---|---|
| `app/src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestPresentation.kt` | Helpers purs : `providerBannerText`, `sanitizeHostLabel`, `extractTargetHost`, `effectiveTargetHost`, `formatMbpsCapped`, `formatMo`, `formatConsumption`, `consumptionLine`, `consentServerLine` + chaînes `CONFIGURED`, `INTERNET_TITLE`, `CONSUMPTION_LABEL` |
| `app/src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestViewModel.kt` | `SpeedTestUiState.providerLabel`, `targetHost` (défauts `null`) ; constructeur avec `providerLabel`, `targetHost`, `initiallyDisabled` ; `Done` propage `result.providerLabel` et en extrait l'hôte |
| `app/src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestScreen.kt` | Bandeau conditionnel + tag `speedtest_provider_banner`, liste taguée `speedtest_list`, bouton `enabled = !isDisabled` + libellé `Lancer le test`, jauge `≥ … (plafonné)`, cartes `Download (Internet)` / `Upload (Internet)` / `Latence (Internet)` + titre `Débit Internet (téléphone ↔ serveur de mesure)`, ligne consommation taguée `speedtest_consumption`, consentement branché, preview `SpeedTestConfiguredPreview` |
| `app/src/main/java/dev/wifiadmin/app/navigation/WifiAdminViewModelFactory.kt` | Cablage UI (3 lignes) : `providerLabel = container.speedTestEngine.providerLabel`, `initiallyDisabled = container.speedTestEngine is DisabledSpeedTestEngine` |
| `app/src/androidTest/java/dev/wifiadmin/app/SpeedTestInstrumentedTest.kt` | 6 nouveaux tests Compose (voir § Tests) |
| `app/src/test/java/dev/wifiadmin/app/ui/speedtest/SpeedTestPresentationTest.kt` | **Nouveau** : 10 tests JVM des helpers purs (seam S4-pure) |

## Seams testés (règle `tdd`, confirmés par la mission)

| Seam | Ce qu'il attrape | Ce qu'il manque | Type |
|---|---|---|---|
| S4-pure : `SpeedTestPresentation` (`providerBannerText`, `formatMbpsCapped`, `formatMo`, `formatConsumption`, `consumptionLine`, `failureMessage`) | bandeau + `vers <hôte>` sans URL/IP, `≥ X Mbps (plafonné)`, `X Mo / Y Mo`, messages FR distincts | le rendu Compose lui-même | JVM `test/` |
| S4 : `SpeedTestContent(state construit à la main, callbacks vides)` | bouton désactivé/actif, bandeau configuré/désactivé, `≥` + consommation, consentement explicite | l'erreur réseau réelle, le vrai moteur | Instrumenté `androidTest/` (loopback uniquement, aucun réseau) |
| `SpeedTestInstrumentedTest.start_button_is_disabled_without_a_provider` (existant, inchangé dans son intention) | régression du garde-fou « désactivé sans fournisseur » | — | Instrumenté |

Aucun test ne mock un interne, aucun ne recalcule l'attendu avec la formule de production : les attendus sont des littéraux indépendants de la spec (ex. `Five MiB → « 5,0 Mo »`, `42.5 + capped → « ≥ 42,5 Mbps (plafonné) »`).

## Comportement livré (réponse aux 6 points de la mission)

1. **Bandeau fournisseur.** Si `failure is Disabled` → bandeau existant « Test de vitesse Internet indisponible… » + liens externes. Sinon → `providerBannerText(providerLabel, targetHost)` = `Fournisseur : <label> · vers <hôte> (HTTPS) · Consommation max. 30 Mo/test`. `sanitizeHostLabel` ne conserve que l'hôte : schéma, identifiants (`user:pass@`), port, chemin, query et fragment sont retirés ; un littéral IPv4/IPv6 est rejeté (jamais d'IP affichée). Si `label` contient déjà `vers <hôte>` (style LibreSpeed `« LibreSpeed — vers speed.example.org »`), il est affiché tel quel sans duplication de `vers`.
2. **Bouton.** `enabled = !state.isDisabled`, libellé constant `Lancer le test` (l'ancien `Relancer` a disparu, conformément à la spec). Le `testTag("speedtest_start_button")` est conservé ; pendant un run, le bouton `Annuler` reste inchangé.
3. **Résultat.** Liaison Wi-Fi (carte `Liaison Wi-Fi (téléphone ↔ routeur)` + `Débit de liaison`, `Signal`, `Fréquence`, `Standard`) et débit Internet (cartes `Download (Internet)`, `Upload (Internet)`, `Latence (Internet)` sous le titre `Débit Internet (téléphone ↔ serveur de mesure)`) restent deux groupes séparés ; la note existante « Un débit de liaison élevé ne garantit pas un débit Internet élevé » est conservée. Si `result.capped`, les deux cartes affichent `≥ X Mbps (plafonné)` via `formatMbpsCapped`, la jauge affiche `≥ X Mbit/s (plafonné)`, et la phrase « Plafond atteint avant stabilisation : valeur minimale, pas débit maximal » reste en métadonnées. La jauge conserve son échelle `SpeedTestCalculator.gaugeScaleMaxMbps`. La consommation est affichée quand un résultat existe : `DetailRow("Consommation (réception / envoi)", "5,0 Mo / 2,5 Mo")` taguée `speedtest_consumption`, via `formatConsumption(downloadBytes, uploadBytes)`.
4. **Consentement.** Flow inchangé côté ViewModel (`start()` → `showConsent = true` si `!hasConsented` ; `acceptConsentAndStart()` → `preferences.setConsent(true)` + `dismiss` + `repository.start()` ; `dismissConsent()`). Premier test = consentement explicite avant tout trafic, comme en Mission 12. **Texte du dialogue branché** : en mode désactivé, les 3 lignes d'origine ; en mode configuré, `consentServerLine` = `Serveur : <label> — vers <hôte> (HTTPS). Pendant la mesure, le serveur voit l'adresse IP publique du Wi-Fi.` + ligne `Données` précisant que cette IP reste visible du serveur pendant la mesure + ligne plafonds inchangée (20 Mo / 10 Mo / 30 s).
5. **Échec.** `failureMessage()` (inchangé dans son mapping, désormais couvert par test) : `NoWiFi` → « Wi-Fi indisponible… », `InternetUnavailable` → « Wi-Fi connecté, mais Internet est indisponible. », `ServerUnavailable` → « Le serveur de test est injoignable… », `Timeout` → « Le test a dépassé le délai autorisé. », `Incomplete` → « La mesure a été interrompue… », `Unexpected` → « Une erreur est survenue. Réessayez. », `Disabled` → phrase du bandeau, `TooBusy` → phrase dédiée. Aucun `Exception`, aucune stacktrace, aucun secret, aucun `@` dans les messages (assertés en test). La carte d'erreur reste exclue pour `Disabled` (gérée par le bandeau), carte `Assertive` inchangée.
6. **Contraintes.** Moteur toujours désactivé par défaut aujourd'hui (`AppContainer.speedTestEngine = DisabledSpeedTestEngine()` intouché) ; aucun serveur public en dur ajouté (le test d'hygiène `Mission12BoundaryTest` reste vert : aucun `https://` dans `data/speedtest/`) ; aucune télémétrie ; plafonds 20 Mo / 10 Mo / 30 s rappelés dans le bandeau et le consentement, jamais relevés.

## Décisions et points à valider

- **Unité de consommation : 1 Mo = 1 048 576 octets** (unités de `SpeedTestPolicy`, qui définit ses plafonds en `1024*1024`). Au plafond, l'écran affichera donc exactement `20,0 Mo / 10,0 Mo`, cohérent avec les plafonds annoncés. En SI (1 000 000), le plafond réel s'afficherait `21,0 Mo / 10,5 Mo` — trompeur. Le `formatMo` est documenté dans le code ; si le produit préfère le SI, il suffit de changer la constante mais il faudra alors aligner les plafonds affichés.
- **Extraction de l'hôte = parsing d'un texte libre** (`vers <tok>` dans `providerLabel`). Fonctionnelle pour le format LibreSpeed attendu, mais fragile : elle dépend de la formulation du futur moteur. Voir « Champ manquant » ci-dessous.
- **Liens externes** conservés dans les deux bandeaux (navigateur uniquement, jamais appelés par l'app, exigés par `Mission12BoundaryTest`).

## Champ manquant côté data (signalé, non modifié — consigne mission)

1. **Aucun champ structuré `targetHost` / `endpointHost` n'existe** dans `SpeedTestResult`, `SpeedTestUiState` (avant cette mission) ni `SpeedTestEngine`. L'UI a donc ajouté `providerLabel`/`targetHost` à son propre `UiState` (fichier UI, autorisé) et extrait l'hôte par parsing de `providerLabel`. **Demandé à l'agent moteur** : exposer un champ dédié et typé, ex. `SpeedTestEndpoint.hostLabel` déjà prévu à l'architecture §4.3, propagé tel quel dans `SpeedTestResult.providerHost` (ou `SpeedTestEngine.targetHost`), pour que l'UI n'ait plus à parser une phrase. En attendant, `extractTargetHost()` est isolée et testée.
2. **Le `SpeedTestRepository` n'expose pas `engine.providerLabel`.** L'UI lit donc le label via l'injection de la factory (`container.speedTestEngine.providerLabel`), sans toucher au data layer. Si l'agent moteur préfère une source unique, le repository devrait exposer `providerLabel: String` (lecture seule) et le ViewModel s'y brancherait directement.
3. **`initiallyDisabled` est déduit de `engine is DisabledSpeedTestEngine`** dans la factory. C'est correct tant que `AppContainer` choisit le moteur par type. Si le choix se fait par config (`BuildConfig` / DataStore) avec un même adaptateur configuré ou non, cette déduction devra suivre la même source de vérité (même condition que celle qui choisit l'adaptateur).
4. **État initial** : avec le moteur `Disabled` actuel, le comportement est inchangé (bandeau désactivé, bouton bloqué). Dès qu'un moteur configuré est branché, la factory passe `initiallyDisabled = false` et l'écran passe en mode « configuré » **avant** le premier test (bandeau `vers <hôte>`, bouton actif, consentement explicite). Aucun test réel vers Internet n'a été réalisé pendant cette mission.

## Tests lancés et résultats exacts

### Cycle TDD observé

- **Rouge** : `SpeedTestPresentationTest` créé avant l'implémentation → échec de compilation `Unresolved reference 'providerBannerText'`, `'formatMbpsCapped'`, `'formatMo'`, `'formatConsumption'`, `'consumptionLine'`. Puis ajouts instrumentés → échec `No parameter with name 'providerLabel' found`. Chaque rouge a été suivi de l'implémentation minimale correspondante.
- **Vert** : après chaque implémentation, relance du test ciblé (`:app:testDebugUnitTest --tests "dev.wifiadmin.app.ui.speedtest.SpeedTestPresentationTest"` → `BUILD SUCCESSFUL`), puis compile AndroidTest.

### Unitaires — `./gradlew :app:testDebugUnitTest --rerun-tasks` (31 octobre 2026, appareil Pixel non requis)

```
BUILD SUCCESSFUL in 29s
26 actionable tasks: 26 executed
tests=373 failures=0 errors=0
dont SpeedTestPresentationTest = 10 tests (bandeau, URL/identifiants, extraction, IP masquée, capped, non-capped, consommation, ligne consommation, échecs FR distincts, timeout vs incomplete)
```

La suite complète (y compris `Mission12BoundaryTest`, `Mission11*`, `ArchitectureBoundaryTest`) est verte : aucune régression.

### Instrumentés — `./gradlew :app:connectedDebugAndroidTest` (Pixel 7 Pro — 17)

```
Running tests on devices: Pixel 7 Pro - 17
BUILD SUCCESSFUL in 4m 50s
70 actionable tasks: 10 executed, 60 up-to-date
tests=64 failures=0 errors=0
dont SpeedTestInstrumentedTest = 10 tests PASS :
  consent_dialog_is_explicit_before_first_test
  start_button_is_disabled_without_a_provider
  capped_result_shows_floor
  capture_result_dark
  configured_banner_shows_provider_and_host_without_url
  capture_disabled_dark
  disabled_banner_hides_configured_text
  capture_ready_light
  capture_running
  consumption_is_shown_after_result
```

Deux échecs intermédiaires ont été corrigés avant le vert final, tous deux sur le défilement de la `LazyColumn` dans `consumption_is_shown_after_result` : d'abord `« 5,0 Mo / 2,5 Mo » is not displayed` (nœud hors écran, `performScrollTo` insuffisant pour une liste lazy), puis `No node found that matches TestTag = 'speedtest_consumption' in scrollable container` (mauvais usage de `performScrollToNode` sur la racine). Correction : ajout du tag `speedtest_list` sur la `LazyColumn` et usage `onNodeWithTag("speedtest_list").performScrollToNode(hasTestTag("speedtest_consumption"))`, avec le test scindé en deux (haut de liste / bas de liste) pour ne pas asserter des nœuds recyclés hors écran. Aucune correction de comportement produit n'a été nécessaire — seul le test et un tag d'ergonomie ont bougé au final.

## Checklist mission

- [x] Bandeau `providerLabel` + `vers <hôte>` sans URL complète ni IP ; bandeau désactivé conservé sinon.
- [x] Bouton désactivé si `Disabled`, sinon `Lancer le test` actif.
- [x] Liaison Wi-Fi vs Internet distinguées ; `capped` → `≥ X Mbps (plafonné)` + jauge `≥ … (plafonné)` ; consommation `X Mo / Y Mo` quand disponible.
- [x] Consentement explicite au premier test (`showConsent` / `acceptConsentAndStart` inchangés), textes branchés sur l'hôte configuré.
- [x] Échecs `NoWiFi / Disabled / Timeout / ServerUnavailable / Incomplete / Unexpected` (+ `InternetUnavailable`, `TooBusy`) en français, sans stacktrace ni secret.
- [x] Aucun fichier `data/speedtest/`, `router-core/`, `build.gradle.kts` modifié.
- [x] Tests TDD écrits d'abord, suite unitaire + instrumentée relancées avec preuves ci-dessus.
- [ ] PR vers `develop` — **non réalisée** (mission interdite de fusion ; aucune PR ouverte par cet agent en sous-session, à ouvrir par l'orchestrateur principal).
