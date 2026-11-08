# Mission 13 — Rapport d'implémentation : moteur LibreSpeed réel

> Date : 08/11/2026 · Branche `feature/mission-13-speedtest-reel` (base `feature/mission-12-speedtest`,
> moteur réel **désactivé** en Mission 12). Revue : PR vers `develop` uniquement, **aucune fusion**.
> skills : `research`, `codebase-design`, `tdd`, `test-driven-development`, `systematic-debugging`,
> `security-audit` (guidance), `code-review`, `pr`, `verification-before-completion`,
> `reverse-engineer-anything` (constat REA). Aucun plugin installé.

## 1. Découverte (prérequis obligatoire)

| Élément | Disponible | Utilisation |
|---|---|---|
| Skills de développement | Oui : `codebase-design`, `tdd`, `test-driven-development` (67 skills globaux, 10 locaux projet, aucun `AGENTS.md`). Pas de skill Kotlin/Android dédié. | Architecture module profond + TDD rouge→vert. |
| Skills de recherche | Oui : `research`, `reverse-engineer-anything`. | Étude LibreSpeed sur sources primaires (`docs/MISSION_13_LIBRESPEED_RESEARCH.md`). |
| Skills de tests | Oui : `tdd`, `systematic-debugging`, `verification-before-completion`. | Cause racine avant correction, preuve avant affirmation. |
| Skills de revue | Oui : `code-review`, `security-audit` (guidance), `pr`, `finishing-a-development-branch`. | Revue Standards/Spec séparées, audit sécurité guidance, corps de PR. |
| Plugin REA | Oui : MCP local `rea` (`rea-agents@6.8.0`, Hopper dispo ; Ghidra/IDA/Kuna non configurés, aucune cible ouverte). | Constater seulement : `binary_session` (`open: false`), `list_documents`/`current_document` → `target_unavailable`. Utilité nulle pour cette mission (source complète, aucun artefact binaire fourni). |
| Sous-agents | Oui : `explore`, `general`. | 4 sous-agents réels : recherche, architecture, Compose, tests/sécurité/revue. |

## 2. Résultat

Un véritable moteur de mesure Internet LibreSpeed, **désactivé par défaut** (aucun endpoint configuré
→ `DisabledSpeedTestEngine`, zéro octet quittant le téléphone). Aucun serveur public n'est codé en dur ;
l'endpoint est injecté par le build (`BuildConfig.SPEEDTEST_BASE_URL`, vide par défaut).

```text
data/speedtest/
  SpeedTestEngine.kt      interface + Disabled + Fake + Repository (backstop SEC-01)
  SpeedTestEndpoint.kt    NEW — validation pure (HTTPS, loopback tests, pas d'identifiants)
  SpeedTestEngineFactory.kt NEW — choisit Disabled/LibreSpeed selon l'endpoint
  LibreSpeedClient.kt     NEW — HTTP OkHttp borné (empty.php ping/upload, garbage.php download)
  LibreSpeedEngine.kt     NEW — latence médiane → DL → UL, Mbps par fenêtre, capped honnête
```

- **Sécurité** : HTTPS obligatoire (loopback uniquement en tests), rejet des identifiants/fragment/query
  sensibles, pas de redirection cross-hôte, jamais d'appel `getIP.php`/`telemetry.php`, plafonds durs
  **20 Mo / 10 Mo / 30 s** comptés en flux + **backstop indépendant** dans le repository, annulation
  `Call.cancel()` + `ensureActive` par buffer, Wi-Fi strict sans repli cellulaire.
- **UI** : bandeau « Fournisseur : … · vers \<hôte\> », bouton désactivé sans endpoint, `≥ X Mbps
  (plafonné)`, consommation `X Mo / Y Mo`, consentement inchangé, liaison Wi-Fi jamais confondue avec
  le débit Internet.
- **Réserves restantes** (voir `docs/MISSION_13_SECURITY_REVIEW.md`) : `usesCleartextTraffic` globale
  sans `networkSecurityConfig` (contrôle applicatif appliqué, durcissement à suivre), validation LGPL
  par juriste, backend sans auth → rate-limit au reverse-proxy obligatoire, aucun test réel vers
  Internet n'a été exécuté (loopback uniquement).

## 3. Preuves (verification-before-completion)

- `./gradlew :app:testDebugUnitTest --tests "LibreSpeedEngineTest"` : 5 tests verts (post correction
  `Buffer` vs `BufferedSource`, preuve : `expected 1000000 but was 0` avant correction).
- `./gradlew test assembleDebug` : **BUILD SUCCESSFUL** ; 391 tests app, 0 échec.
- Cycle TDD suivi : chaque tranche rouge avant vert (validator, comptage, plafond, backstop, annulation,
  usine).

## 4. Sous-agents réellement utilisés

| Sous-agent | Contribution |
|---|---|
| Recherche LibreSpeed et protocoles | `docs/MISSION_13_LIBRESPEED_RESEARCH.md` (licence, protocole, recommandation). |
| Architecture Kotlin et réseau Android | `docs/MISSION_13_ARCHITECTURE.md` (seams, plan T1→T10, arbitrages). |
| Implémentation Jetpack Compose | UI + 6 tests instrumentés + 10 tests JVM (`SpeedTestPresentationTest`). |
| Tests, sécurité et revue indépendante | `docs/MISSION_13_SECURITY_REVIEW.md` (5 findings SEC-01→05, revue Standards/Spec). |
