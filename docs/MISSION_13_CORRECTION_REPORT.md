# Mission 13 — Rapport de correction et de stabilisation

> Date : 08/11/2026 · Branche `feature/mission-13-speedtest-reel` · PR #6 (base `develop`), **non fusionnée**.
> skills : `systematic-debugging` (phase 1 cause racine d'abord), `tdd` (rouge→vert),
> `code-review` (deux axes), `security-audit` (guidance), `verification-before-completion`,
> `finishing-a-development-branch` (option PR, aucune fusion — outil `gh` utilisé, choix de
> l'humain : conservation sans force push). Sous-agents : 4 (`general`) spécialisés, voir §7.
> REA non utilisé : revue de sources Kotlin ordinaires, aucun artefact de rétro-ingénierie.

## 1. Correction Git (§2 de la demande)

| Anomalie | Cause racine | Correction |
|---|---|---|
| PR #6 en conflit avec `develop` (`DIRTY`/`CONFLICTING`), 4 commits Mission 12 historiques en plus | `develop` a reçu le squash-merge de la PR #5 (`fc39959`, arbre identique à `5aadf6b`, diff vide) ; la branche conservait ses commits propres + Mission 13 | **Fusion de `origin/develop` dans la branche** (`8826a70`), résolution « version Mission 13 » sur 7 fichiers divergents (arbre `develop` confirmé identique au parent, aucune perte) — **sans force push**, PR #6 conservée |
| Diff final suspect | Commits historiques inclus | Vérifié : `git diff origin/develop...HEAD` = 23 fichiers Mission 13 uniquement ; aucune régression Mission 12, aucune duplication fonctionnelle |

Statut Git final (§8) : `develop = fc39959`, branche = `8826a70` (merge) + commit correctif à venir ;
PR #6 ouverte, sans conflit après fusion ; PR #5 déjà `MERGED`.

## 2. Anomalies traitées

### 2.1 Routage non contraint au Wi-Fi

`OkHttpClient` suivait le routage par défaut Android — la garde `LocalWifi` annulait trop tard.
Corrigé par `WifiNetworkRouteProvider` (JVM) + `AndroidWifiNetworkRouteProvider` :
`Network.socketFactory` + `Dns { network.getAllByName() }`, résolu **par run**, exclusion du
hotspot (`WIFI && !CELLULAR`), `null` → `NoWiFi` avant tout octet. `bindProcessToNetwork`
rejeté (portée globale sur le trafic routeur). Limites §4 de `WIFI_ROUTING` maintenues
(TOCTOU, VPN, OEM) : la liaison physique reste à valider sur appareil.

### 2.2 Réutilisation d'un client annulé définitivement

`LibreSpeedClient.cancelled` était collant et le repository appelait `engine.cancel()` au
`finally` même après un succès : le run suivant levait `CancellationException` à la première
sonde. Prouvé par `SpeedTestReuseTest` (2 rouges avant correction, stack
`requireActive ← pingMillis ← runMeasurement`). Corrigé par un client frais par run
(`LibreSpeedEngine`) + drapeau du moteur : aucun état ne survit à un run, aucun `cancel()`
ancien n'atteint un run successeur (slot Mission 12 inchangé).

### 2.3 Métrologie non représentative

- Download `ckSize=1` (~1 Mio) dominé par TCP/TLS sur liaison rapide → réponse longue
  dimensionnée au plafond (`ckSize` = plafond en Mio), warm-up 256 Kio exclu de la fenêtre
  mais compté au plafond et à la consommation, réponses vides 200 → `Incomplete`.
- Upload zéros compressibles + retour de `maxBytes` supposé (backstop inopérant) → corps
  PRNG déterministe incompressible (`Random(0x5EED)`, `Content-Encoding: identity`),
  compteur = octets réellement écrits, `ensureActive` par chunk.
- Métadonnées : jitter `moyenne |Δ|` sur les 5 sondes (test littéral `[20,22,25,30,40] → 5.0 ms`,
  `null` si < 2 sondes, jamais `0`) ; latence sous charge **reportée « indisponible »**
  (aucun protocole LibreSpeed ne la définit, budget insuffisant, non testable — valeurs
  fictives interdites) ; durée, serveur (hôte seul), consommation (Mio) affichés séparément
  de la liaison Wi-Fi ; aucune équivalence WiFiman/FAST.com/Ookla revendiquée.

### 2.4 Portée du backstop sur-étendue

Le commentaire promettait une garantie « wire » qu'un contrôle sur événements de progression
ne peut pas donner (un moteur mensonger peut transférer entre deux événements). Corrigé :
portée réécrite (filet sur le rapport ≠ garantie sur le fil ; seule la borne en flux du
client garantit le fil) + contrôle sur-cap sur le résultat terminal (moteur silencieux).

## 3. Nouveaux tests (tous JVM, loopback uniquement)

| Fichier | Couverture |
|---|---|
| `SpeedTestReuseTest` (3, sous-agent) | Deux runs successifs, cancel entre runs, cancel mid-run → `Done` à chaque fois |
| `SpeedTestNetworkLossRepoTest` (1, sous-agent) | Changement d'identité Wi-Fi → `Cancelled`, aucun retry |
| `SpeedTestRoutingTest` (3, parent) | `null` → `NoWiFi` sans requête, DNS/socket du fake exploités, 2 runs → 2 résolutions |
| `SpeedTestRepositoryBackstopTest` +1 | Résultat terminal sur-cap → `Failed(Incomplete)` |
| `SpeedTestCalculatorTest` +2 | Jitter littéral `5.0 ms`, cas limites `null` |
| `LibreSpeedEngineTest` (réécrit T7) | Annulation à latch (contrat single-flight), contrat sticky abandonné |

## 4. Revue de sécurité finale (validation)

Vérifié sur source finale : TLS OkHttp par défaut sans bypass + validateur strict
(query/`@`/fragment rejetés + re-parse), `followRedirects=false` (`3xx → ServerUnavailable`),
seuls `empty.php`/`garbage.php` appelés (grep `getIP`/`telemetry` : commentaires « jamais »),
zéro télémétrie/log de secret, compteurs en flux sans `Content-Length`, `use{}` systématique,
`LocalWifi` sans repli + route Wi-Fi physique, isolation ZLT (0 hit), `router-core` intact
(`git diff origin/develop...HEAD router-core/` vide), aucun `https://` en dur dans le data
layer (boundary tests verts). `CORE-01` (SEC-02) partiellement traité : `networkSecurityConfig`
à préparer séparément pour ne pas impacter le routeur LAN.

## 5. Validations exécutées (preuves fraiches)

- `./gradlew test assembleDebug` — **BUILD SUCCESSFUL** : app 401 tests, 0 échec ; `router-core`/
  `poc-cli` inclus dans `./gradlew test` (612 historique inchangé sur les modules non touchés).
- MockWebServer : moteur, réutilisation, routage, backstop — boucles ci-dessus.
- Build debug : `assembleDebug` OK.
- Instrumentés : **non relancés après correction** (UI jitter ajoutée) — à relancer sur appareil
  avant fusion ; `SpeedTestInstrumentedTest` inchangé depuis la Mission 13 déjà validée (64/64).

## 6. GitHub Actions

Dernier commit poussé après correction — statut à vérifier (`gh run list` / checks PR #6).
CI verte attendue avant tout avis de fusion ; **aucune fusion sans autorisation**.

## 7. Sous-agents réellement utilisés (revue corrective)

| Sous-agent | Contribution |
|---|---|
| Architecture réseau Android | `docs/MISSION_13_WIFI_ROUTING.md` (WifiRoute, provider, limites) |
| Protocole et métrologie LibreSpeed | `docs/MISSION_13_METROLOGY.md` (download/UL/jitter, arbitrages) |
| Tests Kotlin et concurrence | `SpeedTestReuseTest` + `SpeedTestNetworkLossRepoTest` (2 rouges prouvant le bug) |
| Revue de sécurité indépendante | `docs/MISSION_13_CORRECTIVE_REVIEW.md` (CORR-01→05, axes Standards/Spec) |

## Additif de clôture (08/11/2026, commit `b9c89db`)

| Anomalie | Cause racine | Correctif |
|---|---|---|
| Upload PRNG répété | `random.nextBytes(chunk)` appelé une fois **hors boucle** (`LibreSpeedClient.kt:191-192`), même bloc réécrit jusqu'au plafond | `nextBytes` **à chaque itération** dans `writeTo` ; comptage = octets réellement écrits dans le flux applicatif (pas des octets fil physiques) |
| Annulation upload brute | `IOException` du socket annulé non mappée par le client | `CancellationException` quand le client est annulé (même contrat que le moteur) |
| Choix Wi-Fi arbitraire | `allNetworks.firstOrNull` sans lien avec l'identité validée (le run pouvait se lier à B alors que le repository validait A) | `AndroidWifiNetworkRouteProvider` re-lit `currentStatus()` au run et filtre les candidats sur `interfaceName` validé (+ repli `activeNetwork` filtré) ; sans `bindProcessToNetwork`, sans changement JVM |

Nouveaux tests : `LibreSpeedUploadTest` (4/4 — blocs ≠, volume ≤ plafond et serveur = retourné,
annulation à latch, reproductibilité) ; `SpeedTestInstrumentedTest` +2 (jitter clair/sombre,
« Latence sous charge : indisponible »).

Validations finales : JVM **405 tests, 0 échec** ; instrumentés Pixel 7 Pro **22/22**
(SpeedTest 12, Navigation 3, SessionExpiry 4, NetworkChange 3, jitter clair et sombre) ;
aucun test de débit Internet réel lancé ; CI `42506084696` **vert** sur `b9c89db`.
Statut Git : PR #6 `OPEN`/`CLEAN`, commit `b9c89db` — **non fusionnée, en attente d'autorisation**.
