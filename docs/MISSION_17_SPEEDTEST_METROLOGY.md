# Mission 17 — Suppression du plafonnement artificiel du SpeedTest

Date : 2026-11-17.
Branche : `feature/mission-17-speedtest-metrology`.
PR #11 (Mission 16) fusionnée dans `develop` au commit `bfbcf8d`.
PR #12 (Mission 17) en cours de revue.

## 1. Problème identifié

La Mission 16 a validé le premier benchmark réel. Résultats : latence 9 ms,
Download 25,3 Mbps (plafonné), Upload 19,1 Mbps (plafonné). Budget : 1 Mio /
256 Kio / 10 secondes.

Le résultat était **systématiquement plafonné** car le moteur transférait
exactement le volume du plafond (`maxBytes`) en une seule requête, puis
s'arrêtait. Sur un lien rapide, le transfert se terminait en une fraction de
seconde, produisant une fenêtre de mesure trop courte pour être fiable.

### Cause racine

`LibreSpeedClient.download(maxBytes)` faisait une seule requête `garbage.php`
dimensionnée à `maxBytes` et lisait jusqu'à `maxBytes` ou EOF. Le débit était
calculé sur la fenêtre post-warm-up. Comme `maxBytes` était toujours atteint
(le serveur envoie exactement `ckSize` Mo), `isCapped()` retournait toujours
`true`.

De même, `upload(maxBytes)` envoyait exactement `maxBytes` octets. Le débit
était calculé sur la durée totale de l'upload, qui incluait l'établissement
de connexion.

De plus, le repository imposait une durée globale de 30 s, mais le moteur
transmettait `maxDurationMillis` à chacune des deux phases (Download et Upload).
Le Download pouvait consommer les 30 s, et le watchdog global annulait le test
avant la fin de l'Upload.

## 2. Nouveau modèle de mesure

### Principe

Mesure **contrôlée par la durée**, pas par le volume. Le moteur télécharge et
envoie des données jusqu'à ce que :
- la durée maximale de la phase soit atteinte, OU
- le plafond de sécurité (`maxBytes`) soit atteint,

le premier des deux qui survient arrêtant la mesure proprement.

### Budgets temporels distincts (correction bloquante)

Le repository impose une durée globale (`maxDurationMillis`). Le watchdog global
reste la protection de sécurité. Mais chaque phase dispose de son propre budget :

- **Latence** : `latencyProbes × 1 s`, plafonné à `maxDuration / 5`.
- **Marge** : 10 % de `maxDuration` pour les connexions, réponses HTTP et
  nettoyage.
- **Download** : `(maxDuration - latence - marge) / 2`.
- **Upload** : `(maxDuration - latence - marge) / 2`.

Exemple production (30 s) : latence 5 s, marge 3 s, download 11 s, upload 11 s.
Exemple validation (10 s) : latence 2 s, marge 1 s, download 3,5 s, upload 3,5 s.

Le watchdog global tue à `maxDurationMillis` si une phase déborde. Ce n'est pas
le mécanisme normal de terminaison.

### Warm-up

Les premiers 256 Kio sont exclus du calcul du débit (stabilisation TCP/TLS).
Le chronomètre de mesure démarre après le warm-up. Si la durée expire pendant
le warm-up, la mesure est marquée `incomplète`.

### Download (time-based)

- Boucle de requêtes `garbage.php?ckSize=N` (N = min(20, restant/1Mo)).
- Lecture en blocs de 64 Kio.
- Vérification de la durée à chaque bloc : arrêt si `elapsed >= downloadBudget`.
- Arrêt si `counted >= maxBytes` (plafond de sécurité).
- Arrêt si EOF (serveur a envoyé tout ce qu'il avait).
- Compteur d'octets réels (jamais de `Content-Length` fictif).

### Upload (time-based)

- Transfert chunked (`Content-Length = -1`) pour pouvoir arrêter à la durée.
- Écriture en blocs de 64 Kio avec entropie fraîche par bloc.
- Vérification de la durée à chaque bloc : arrêt si `elapsed >= uploadBudget`.
- Arrêt si `written >= maxBytes` (plafond de sécurité).
- Annulation vérifiée tous les 64 Kio.

### Statut `capped`

`SpeedTestCalculator.isCapped(downloadBytes, uploadBytes, policy)` :
- `true` si `downloadBytes >= maxDownloadBytes` ou `uploadBytes >= maxUploadBytes`
  (le plafond de sécurité a été atteint avant la fin de la fenêtre).
- `false` si la durée a expiré en premier (mesure complète sur la fenêtre).

Ne jamais forcer `capped = false` pour masquer un plafond réel.

### Statut de validité

- Les deux phases terminées par `TIME_WINDOW`, fenêtre ≥ 1 s et aucun plafond :
  résultat stabilisé, débit affiché sans mention « plafonné ».
- Plafond atteint (`VOLUME_CAP`) : « mesure limitée » avec explication (valeur
  minimale, pas débit maximal).
- Réponse vide / EOF avant la deadline (`PREMATURE`) : résultat indicatif,
  jamais « mesure stabilisée » — même si la fenêtre dépasse 1 s et que
  `capped = false`.
- Fenêtre trop courte (< 1 s) : avertissement « résultat indicatif » via
  `isTooShortToTrust()`.
- Timeout : `SpeedTestFailure.Timeout`, aucune valeur fictive.
- Échec réseau : `SpeedTestFailure.ServerUnavailable` / `Incomplete`.
- Annulation : `SpeedTestState.Cancelled`, aucune mesure déclarée réussie.

## 3. Plafonds de sécurité

### Plafonds de production (inchangés)

| Paramètre | Valeur | Justification |
|---|---|---|
| Download max | 20 Mio | Protection des forfaits prépayés |
| Upload max | 10 Mio | Protection des forfaits prépayés |
| Durée max | 30 s | Contrôle principal |
| Sondes latence | 5 | — |

Les plafonds de production n'ont **pas** été augmentés. La correction se
concentre sur l'architecture des délais. L'augmentation à 50/25 Mio proposée
dans la première version de la PR a été abandonnée : avec le modèle
temps-contrôlé, un lien à 25 Mbps télécharge 20 Mio en 6,4 s (plafonné),
mais la mesure tourne maintenant sur la fenêtre complète au lieu de timeout.
Les plafonds peuvent être relevés ultérieurement si les tests le justifient.

### Plafonds de validation (debug)

| Paramètre | Valeur |
|---|---|
| Download max | 1 Mio |
| Upload max | 256 Kio |
| Durée max | 10 s |

### Configurabilité

Les plafonds sont configurables via `SpeedTestPolicy` et les propriétés Gradle
(`SpeedTestBudget.fromCaps` re-clampe sur la production). Aucune configuration
ne peut élargir le budget.

## 4. Sécurité conservée

- Routage Wi-Fi explicite (`AndroidWifiNetworkRouteProvider`, pas de fallback
  cellulaire, pas de `bindProcessToNetwork`).
- Contrôle des permissions Android (`ACCESS_LOCAL_NETWORK` vérifié par le
  repository avant tout socket).
- Watchdog (`engine.cancel()` à l'échéance, classé `Timeout`).
- Limites de consommation (plafonds de sécurité).
- Annulation immédiate des sockets (`cancel()` ferme tous les `Call`).
- Protection contre les tests concurrents (single-flight slot).
- Absence de télémétrie (`TELEMETRY=false`, `DISABLE_IPINFO=true`).
- Isolation de l'administration ZLT (aucun credential, aucun accès routeur).

## 5. Interface utilisateur

- Download en Mbps (`formatMbpsCapped` : « ≥ X Mbps (plafonné) » si capped,
  sinon « X Mbps »).
- Upload en Mbps (même logique).
- Latence et jitter (jitter indicatif, jamais 0 ms fictif).
- Durée de la mesure (affichée dans le panneau méta).
- Volume réellement consommé (réception / envoi).
- État de validité, désormais fondé sur une cause de fin explicite par phase
  (`PhaseEndReason`) et non sur la seule valeur de `capped` :
  - « Mesure stabilisée sur la fenêtre prévue » uniquement si les deux phases
    ont fini par `TIME_WINDOW`, sans plafond et avec une fenêtre ≥ 1 s
    (`SpeedTestCalculator.isStabilised`).
  - « Arrêt prématuré du serveur : résultat indicatif » si `PREMATURE`
    (réponse HTTP 200 vide ou corps terminé avant la deadline). Ce libellé est
    prioritaire sur celui du plafond si les deux cas se cumulent sur le run.
  - « Plafond atteint avant stabilisation, valeur minimale » si `VOLUME_CAP`.
  - « Fenêtre de mesure trop courte : résultat indicatif » si < 1 s.
  - « Fin de mesure non qualifiée : résultat indicatif » pour les anciens
    résultats sans cause enregistrée (`UNKNOWN`).

`TIMEOUT` et `CANCELLED` font partie du même vocabulaire mais interrompent le
run : ils donnent `SpeedTestFailure.Timeout` ou `SpeedTestState.Cancelled`,
jamais un résultat affiché comme réussi. Priorité de classification du Download :
plafond > arrêt prématuré > expiration de la fenêtre. Celle de l'Upload est
mesurée dans `LibreSpeedClient.uploadStats()` (deadline atteinte ou plafond),
jamais déduite du seul `cappedUpload`.

## 6. Tests

### Tests existants (mis à jour)

- `SpeedTestBudgetTest` : caps de production (20/10 MiB).
- `SpeedTestPresentationTest` : valeurs de production (20 Mo, 10 Mo, 30 Mo).
- `LibreSpeedEngineTest` : attentes ajustées (download lit jusqu'au cap).
- `SpeedTestReuseTest`, `SpeedTestRoutingTest` : attentes ajustées.

### Quatre vérifications demandées — scénarios réellement couverts

Les quatre scénarios exigés par la revue sont couverts par des tests
déterministes contre `MockWebServer` en boucle locale (`127.0.0.1`), avec
classification explicite de la cause de fin :

| # | Test | Mécanisme déterministe | Assertion clé |
|---|---|---|---|
| 1 | `LibreSpeedEngineTest.download ends by time window without cap` | `garbage.php` débité à 64 Kio/100 ms (`throttleBody`) ; 2 Mio servis, plafond de test 3 Mio, budget Download ~2,2 s | `downloadEndReason == TIME_WINDOW`, `cappedDownload == false`, octets > 256 Kio et < 3 Mio, fenêtre ≥ 1 s |
| 2 | `LibreSpeedEngineTest.upload ends by time window without cap` | plafond de test 500 Mio + intercepteur **uniquement dans le test** qui cadence les écritures à ~8 Mio/s (1 ms par chunk Okio) : fin déterministe par la deadline, même sur un CI rapide (~385 Mio/s sinon) | `uploadEndReason == TIME_WINDOW` (mesuré par le client), `cappedUpload == false`, fenêtre ≥ 1 s, volume < 250 Mio |
| 3 | `LibreSpeedEngineTest.full run ends by both time windows without cap` | Download lent + même cadencement d'upload côté test, plafonds hors d'atteinte, les deux budgets expirent | les deux raisons `TIME_WINDOW`, `capped` / `cappedDownload` / `cappedUpload` tous `false`, `isStabilised == true` |
| 4 | `LibreSpeedEngineTest.partial download then empty response is premature` | 1re réponse `garbage.php` = 100 000 octets, 2e = HTTP 200 vide (arrêt sans boucle rapide) | `downloadBytes == 100_000`, `cappedDownload == false`, `downloadEndReason == PREMATURE`, `isStabilised == false` |

Le cadencement d'upload est appliqué par un intercepteur local au test
(`PacedSink`) : aucune limite de débit n'a été ajoutée au client de production
et les plafonds de la `SpeedTestPolicy` restent 20 Mio / 10 Mio / 30 s.

Le test initialement nommé `download completes by time without cap` a été
renommé : il ne mesurait pas une expiration temporelle mais une réponse vide.
Le scénario de fin temporelle est désormais le test #1 ci-dessus, qui ne
renvoie jamais de réponse vide.

Des tests purs complètent la garde-fou d'affichage dans
`SpeedTestCalculatorTest` :
`both time windows are stabilised`, `premature phase is never stabilised`,
`capped or unknown or short phase is never stabilised`. Un arrêt `PREMATURE`,
un plafond, une cause `UNKNOWN` ou une fenêtre < 1 s ne peuvent pas produire
« Mesure stabilisée ».

### Autres scénarios déjà couverts

Plafond Download/Upload atteint (`capped`), timeout de latence
(`latency deadline exceeded throws timeout`), annulation avec fermeture du
socket (`cancel closes the in-flight call`), HTTP 200 entièrement vide →
`Incomplete`, erreur HTTP 503 → `ServerUnavailable`, budget debug réduit,
consommation maximale (backstop), réutilisation sans état résiduel, routage
Wi-Fi et métrique de présentation.

### Résultats des tests automatisés

- `./gradlew testDebugUnitTest --offline --rerun-tasks` : **477 tests, 0 échec,
  0 erreur, 0 ignoré** (53 classes de test).
- `./gradlew assembleDebug compileReleaseKotlin --offline --rerun-tasks` :
  **BUILD SUCCESSFUL**.
- Les tests JVM ne lancent aucun benchmark réel, n'ouvrent aucun port LAN et ne
  dépendent pas du serveur LibreSpeed sur Ubuntu.

## 7. Risques résiduels

1. **Fenêtre courte sur liens rapides** : même avec 20 Mio, un lien à 100 Mbps
   est plafonné à ~1,6 s. Pour une fenêtre de 30 s, il faudrait ~375 Mio.
   Présenté comme compromis (volume vs. précision).
2. **Warm-up fixe** : 256 Kio est court pour les liens très lents (le warm-up
   peut prendre plusieurs secondes). Amélioration possible : warm-up temporel.
3. **Upload en chunked** : la compatibilité avec LibreSpeed 6.3.1 réel reste à
   valider séparément (aucun benchmark réel dans cette correction).
4. **Biais de fenêtre Download** : le départ de fenêtre est pris après la
   lecture qui franchit 256 Kio (biais positif possible de quelques %).
5. **Résultat précédent** : après un échec, l'écran peut laisser le résultat
   précédent affiché.
6. **Caps dupliqués** : `SpeedTestPolicy` (Kotlin) et `build.gradle.kts`
   (BuildConfig) contiennent des littéraux de production. Un commentaire croisé
   le signale. Risque latent si les defaults divergent.
7. **EOF sur corps court** : un serveur qui renvoie systématiquement des corps
   plus petits que `ckSize` est classé `PREMATURE`, même si la fenêtre a expiré
   ensuite. Choix volontairement conservateur : jamais de « mesure stabilisée »
   à tort, mais possible sous-estimation sur un serveur LibreSpeed atypique.
   À réévaluer seulement avec un benchmark réel autorisé.
8. **Anciens résultats** : les entrées d'historique sans cause de fin
   (`UNKNOWN`) et le moteur simulé affichent « Fin de mesure non qualifiée »
   au lieu de « Mesure stabilisée ». Valeur par défaut sûre, effet purement
   d'affichage.

## 8. Livrables

- `docs/MISSION_17_SPEEDTEST_METROLOGY.md` (ce document).
- Modifications du moteur (LibreSpeedClient, LibreSpeedEngine).
- Budgets temporels distincts par phase.
- Mise à jour de l'UI (durée de la mesure, statut de validité).
- Tests JVM mis à jour.
- PR #12 mise à jour.

## 9. Interdictions respectées

- Aucun nouveau benchmark réel sur le Pixel.
- Aucune modification des tests de permissions Android (Mission 15.1).
- Aucune augmentation silencieuse des limites (caps inchangés à 20/10).
- Aucune fusion de PR sans autorisation.