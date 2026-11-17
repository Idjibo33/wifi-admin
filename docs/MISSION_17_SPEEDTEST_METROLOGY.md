# Mission 17 — Suppression du plafonnement artificiel du SpeedTest

Date : 2026-11-17.
Branche : `feature/mission-17-speedtest-metrology`.
PR #11 (Mission 16) fusionnée dans `develop` au commit `bfbcf8d`.

## 1. Problème identifié

La Mission 16 a validé le premier benchmark réel. Résultats : latence 9 ms,
Download 25,3 Mbps (plafonné), Upload 19,1 Mbps (plafonné). Budget : 1 Mio /
256 Kio / 10 s.

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

## 2. Nouveau modèle de mesure

### Principe

Mesure **contrôlée par la durée**, pas par le volume. Le moteur télécharge et
envoie des données jusqu'à ce que :
- la durée maximale (`maxDurationMillis`) soit atteinte, OU
- le plafond de sécurité (`maxBytes`) soit atteint,

le premier des deux qui survient arrêtant la mesure proprement.

### Warm-up

Les premiers 256 Kio sont exclus du calcul du débit (stabilisation TCP/TLS).
Le chronomètre de mesure démarre après le warm-up. Si la durée expire pendant
le warm-up, la mesure est marquée `incomplète`.

### Download (time-based)

- Boucle de requêtes `garbage.php?ckSize=N` (N = min(20, restant/1Mo)).
- Lecture en blocs de 64 Kio.
- Vérification de la durée à chaque bloc : arrêt si `elapsed >= maxDuration`.
- Arrêt si `counted >= maxBytes` (plafond de sécurité).
- Arrêt si EOF (serveur a envoyé tout ce qu'il avait).
- Compteur d'octets réels (jamais de `Content-Length` fictif).

### Upload (time-based)

- Transfert chunked (`Content-Length = -1`) pour pouvoir arrêter à la durée.
- Écriture en blocs de 64 Kio avec entropie fraîche par bloc.
- Vérification de la durée à chaque bloc : arrêt si `elapsed >= maxDuration`.
- Arrêt si `written >= maxBytes` (plafond de sécurité).
- Annulation vérifiée tous les 64 Kio.

### Statut `capped`

`SpeedTestCalculator.isCapped(downloadBytes, uploadBytes, policy)` :
- `true` si `downloadBytes >= maxDownloadBytes` ou `uploadBytes >= maxUploadBytes`
  (le plafond de sécurité a été atteint avant la fin de la fenêtre).
- `false` si la durée a expiré en premier (mesure complète sur la fenêtre).

Ne jamais forcer `capped = false` pour masquer une limite réelle.

### Statut de validité

- Mesure terminée et fenêtre ≥ 1 s : résultat valide, débit affiché sans
  mention « plafonné » si `capped = false`.
- Plafond atteint avant la fin de la fenêtre : « mesure limitée » avec
  explication (valeur minimale, pas débit maximal).
- Timeout : `SpeedTestFailure.Timeout`, aucune valeur fictive.
- Échec réseau : `SpeedTestFailure.ServerUnavailable` / `Incomplete`.
- Annulation : `SpeedTestState.Cancelled`, aucune mesure déclarée réussie.
- Fenêtre trop courte (< 1 s) : `isTooShortToTrust()` retourne true.

## 3. Plafonds de sécurité

### Nouveaux plafonds de production

| Paramètre | Ancien | Nouveau | Justification |
|---|---|---|---|
| Download max | 20 Mio | **50 Mio** | Fenêtre complète à 25 Mbps (20 Mio → 6,4 s ; 50 Mio → 16 s) |
| Upload max | 10 Mio | **25 Mio** | Fenêtre complète à 13 Mbps |
| Durée max | 30 s | 30 s (inchangé) | Contrôle principal |
| Sondes latence | 5 | 5 (inchangé) | |

### Volumes maximaux et implications

- Volume applicatif maximal par run : 50 + 25 = **75 Mio** (~78,6 Mo sur le réseau avec TLS/TCP).
- C'est 2,5× l'ancien volume (30 Mio). Sur un lien rapide (100 Mbps), le
  plafond de 50 Mio est atteint en ~4 s → toujours plafonné (floor).
- Sur un lien moyen (25 Mbps), le plafond est atteint en ~16 s → fenêtre plus
  longue qu'avant (6,4 s) mais toujours plafonnée.
- Sur un lien lent (≤ 13 Mbps download, ≤ 6,7 Mbps upload), la durée expire
  en premier → mesure complète sur 30 s, non plafonnée.
- Le plafond reste une limite de sécurité absolue : aucun transfert ne peut
  dépasser 50 Mio / 25 Mio, retries inclus.

### Plafonds de validation (debug)

| Paramètre | Valeur |
|---|---|
| Download max | 1 Mio (inchangé) |
| Upload max | 256 Kio (inchangé) |
| Durée max | 10 s (inchangé) |

Les plafonds de validation restent réduits pour les tests de fumée. Le build
réduit s'active via `-Pwifiadmin.speedtestMaxDownloadBytes=1048576` etc.

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
- État de validité : « Mesure stabilisée sur la fenêtre prévue » si non plafonné,
  « Plafond atteint avant stabilisation » si plafonné.
- Le dialogue de consentement indique les plafonds effectifs.

## 6. Tests

### Tests existants (mis à jour)

- `SpeedTestBudgetTest` : caps de production mis à jour (50/25 MiB).
- `SpeedTestPresentationTest` : valeurs de production mises à jour (50 Mo, 25 Mo,
  75 Mo total).
- `LibreSpeedEngineTest` : attentes ajustées (download lit jusqu'au cap avec
  MockWebServer).
- `SpeedTestReuseTest`, `SpeedTestRoutingTest` : attentes ajustées.

### Tests à ajouter (déterministes, JVM)

- Mesure terminée sur sa fenêtre prévue (non plafonnée) : utiliser un serveur
  lent (`setBodyDelay`) pour que la durée expire avant le cap.
- Débit variable : vérifier que le calcul utilise la fenêtre stabilisée.
- Débit stable : vérifier que `capped = false` quand la durée expire.
- Plafond de volume atteint prématurément : vérifier `capped = true`.
- Timeout : vérifier `SpeedTestFailure.Timeout`.
- Annulation : vérifier `SpeedTestState.Cancelled` et fermeture des sockets.
- Perte du Wi-Fi : vérifier l'annulation sur changement de réseau.
- Concurrence : vérifier le rejet du second run.
- Respect des budgets : vérifier que `withTimeout` et le watchdog fonctionnent.
- Calcul et affichage du statut `capped`.

### Résultats des tests automatisés

- `./gradlew testDebugUnitTest --offline` : **467 tests, 0 échec** (après
  modification des caps).
- `./gradlew assembleDebug compileReleaseKotlin --offline` : **BUILD SUCCESSFUL**.
- Les tests JVM ne lancent aucun benchmark réel ni ne dépendent du serveur
  LibreSpeed sur Ubuntu.

## 7. Risques résiduels

1. **Fenêtre courte sur liens rapides** : même avec 50 MiB, un lien à 100 Mbps
   est plafonné à ~4 s. Pour une fenêtre de 30 s, il faudrait ~375 MiB.
   Présenté comme compromis (volume vs. précision).
2. **Warm-up fixe** : 256 Kio est court pour les liens très lents (le warm-up
   peut prendre plusieurs secondes). Amélioration possible : warm-up temporel.
3. **Upload en chunked** : le serveur LibreSpeed doit gérer le transfert
   chunked. Testé en JVM (MockWebServer), à valider sur le serveur réel.
4. **Biais de fenêtre Download** : le départ de fenêtre est pris après la
   lecture qui franchit 256 Kio (biais positif possible de quelques %).
5. **Résultat précédent** : après un échec, l'écran peut laisser le résultat
   précédent affiché.
6. **Caps dupliqués** : `SpeedTestPolicy` (Kotlin) et `build.gradle.kts`
   (BuildConfig) contiennent des littéraux de production. Un commentaire croisé
   le signale. Risque latent si les defaults divergent.

## 8. Livrables

- `docs/MISSION_17_SPEEDTEST_METROLOGY.md` (ce document).
- Modifications du moteur (LibreSpeedClient, LibreSpeedEngine).
- Mise à jour des caps de production (50/25 MiB).
- Mise à jour de l'UI (durée de la mesure, statut de validité).
- Tests JVM mis à jour.
- PR vers `develop` (à créer).

## 9. Interdictions respectées

- Aucun nouveau benchmark réel sur le Pixel.
- Aucune modification des tests de permissions Android (Mission 15.1).
- Aucune augmentation silencieuse des limites.
- Aucune fusion de PR sans autorisation.