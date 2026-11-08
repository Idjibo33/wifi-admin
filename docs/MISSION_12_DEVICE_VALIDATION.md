# Mission 12 — Validation sur Pixel 7 Pro

> Date : 07/11/2026 · Pixel 7 Pro cheetah `2B091FDH3004DG` (Android 17, API 37), USB.
> ADB : `/home/ismael/Android/Sdk/platform-tools/adb`. **Aucun speed test réel n'a été lancé.**
> Captures avec moteur simulé falsifié (`Moteur simulé (fictif)`, `sample-*` / `192.168.1.10`).

## 1. Harnais et captures

Harnais instrumenté `SpeedTestInstrumentedTest` : `createComposeRule()` + `captureToImage()`,
`WifiAdminTheme(dynamicColor=false)`, état construit à la main, `FakeSpeedTestEngine` stub.
Stocké hors puce, `docs/screenshots/mission-12/mission12_screenshots/` (4 PNG) :

| Fichier | État |
|---|---|
| `ready-sombre.png` | Prêt, jauge « — », liaison fictive 866 Mbit/s −58 dBm 5 GHz Wi-Fi 6 |
| `running-clair.png` | En cours, jauge 42 Mbit/s Téléchargement |
| `error-disabled-sombre.png` | Idéal écran réel prod (moteur désactivé + liens externes) |
| `result-sombre.png` | Terminé fictif 42,5 / 18,2 / 24 ms — étiquette « Moteur simulé (fictif) » |

## 2. Tests exécutés sur le Pixel

| Suite | Résultat |
|---|---|
| `DevicesListInstrumentedTest` | 14 OK |
| `DeviceAccessibilityInstrumentedTest` + `SessionExpiryInstrumentedTest` + `NavigationInstrumentedTest` | 3 + 4 + 3 OK |
| `SpeedTestInstrumentedTest` | 5 OK |
| JVM (`router-core` 193 + `poc-cli` 56 + `app` 363 dont 41 speedtest) | 612 tests au total, 0 échec |
| FakeRouter | pare-feu 10/10, expiration 3/3, aucune écriture |

Suite complète 54 tests non lancée d'un bloc (polling 30 s) ; `NetworkChangeInstrumentedTest`
seul OK en ~78 s le 07/11.

**Réserve (revue corrective finale, 08/11)** : le Pixel 7 Pro n'était plus connecté
(`adb devices` vide) lors de la dernière correction de concurrence. Les tests JVM, les builds
`assembleDebug`/`assembleDebugAndroidTest` et les scénarios FakeRouter ont été rejoués ; les
tests instrumentés et les captures n'ont pas été régénérés. Les captures restent des maquettes
de démonstration issues de la révision `e14b39e` : leur bouton « Relancer » actif ne reflète
plus l'écran actuel (bouton désactivé, bandeau d'indisponibilité en tête).

## 3. Procédure manuelle (à effectuer après branchement d'un fournisseur)

1. Installer l'APK debug, ouvrir l'app, se connecter au routeur.
2. Tableau de bord → « Ouvrir le test de vitesse » → lire l'écran désactivé en production.
3. Une fois le fournisseur branché : laisser le consentement devant soi (serveur, données,
   consommation 30 Mo/test en prépayé).
4. Lancer **1 seul test** depuis le Wi-Fi ; suivre jauge, Annuler, perte Wi-Fi (couper le Wi-Fi
   en cours → test annulé, jamais de bascule mobile).
5. Comparer débit Internet au **débit de liaison** affiché (ne jamais les confondre).
6. Vérifier l'historique local et le stockage DataStore (`speedtest_consent`), puis effacer.

## 4. Vérification du routage Wi-Fi

Avant tout test réel : `ADB` + `NetworkMonitor = LocalWifi`, hotspot exclu, et le test ne
débute que si le Wi-Fi est le réseau courant. Le statut est relu une seconde fois après la prise
du slot, pour éviter une fenêtre de changement. En cas de perte ou de changement d'identité,
annulation immédiate ; aucun fallback mobile.

Aucun moteur réseau n'existe dans cette version : il n'y a donc **pas** de socket à lier
aujourd'hui. Quand un fournisseur validé sera branché, il devra lier ses sockets au `Network`
Wi-Fi (`bindProcessToNetwork` + `socketFactory`) — ce n'est pas implémenté et ne doit pas être
présenté comme acquis.

## 5. Budget de données et critères d'acceptation

Budget : 20 Mo/10 Mo/30 s par test, 1 test à la fois. Acceptation : moteur désactivé honnête,
liaison distincte, UI Material 3 OK clair/sombre/TalkBack, tests au vert, captures ci-dessus
avec étiquette fictive, revue `Mission12BoundaryTest` au vert. Aucune mesure Internet réelle
n'est requise pour clore.
