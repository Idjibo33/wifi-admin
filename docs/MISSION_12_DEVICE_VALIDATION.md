# Mission 12 — Validation sur Pixel 7 Pro

> Date : 07/11/2026, tests et captures régénérés le 08/11/2026 · Pixel 7 Pro cheetah
> `2B091FDH3004DG` (Android 17, API 37), USB.
> ADB : `/home/ismael/Android/Sdk/platform-tools/adb`. **Aucun speed test réel n'a été lancé.**
> Captures avec moteur simulé falsifié (`Moteur simulé (fictif)`, `sample-*` / `192.168.1.10`).

## 1. Harnais et captures

Harnais instrumenté `SpeedTestInstrumentedTest` : `createComposeRule()` + `captureToImage()`,
`WifiAdminTheme(dynamicColor=false)`, état construit à la main, `FakeSpeedTestEngine` stub.
Stocké sur l'appareil puis copié dans `docs/screenshots/mission-12/` (4 PNG, régénérés le
08/11 depuis le code courant) :

| Fichier | État |
|---|---|
| `ready-clair.png` | État initial réel : bandeau « indisponible » en tête, jauge « — », bouton désactivé. La carte de liaison (866 Mbit/s, −58 dBm, 5 GHz, Wi-Fi 6) est fournie à l'écran mais sous la ligne de flottaison. |
| `running-clair.png` | Démonstration de l'état en cours (42 Mbit/s, Téléchargement), bouton Annuler |
| `error-disabled-sombre.png` | Écran de production : moteur désactivé + liens externes manuels |
| `result-sombre.png` | Résultat fictif 42,5 / 18,2 / 24 ms — étiquette « Moteur simulé (fictif) », bouton Relancer désactivé |

## 2. Tests exécutés sur le Pixel

| Suite | Résultat |
|---|---|
| `DevicesListInstrumentedTest` | 14 OK |
| `DeviceAccessibilityInstrumentedTest` + `SessionExpiryInstrumentedTest` + `NavigationInstrumentedTest` | 3 + 4 + 3 OK |
| `SpeedTestInstrumentedTest` | 5 OK (dont `start_button_is_disabled_without_a_provider`) |
| `NetworkChangeInstrumentedTest` | 3 OK (~82 s) |
| JVM (`router-core` 193 + `poc-cli` 56 + `app` 363 dont 41 speedtest) | 612 tests au total, 0 échec |
| FakeRouter | pare-feu 10/10, expiration 3/3, aucune écriture |

Les 32 tests instrumentés concernés ont été rejoués le 08/11 après la correction de
concurrence : `SpeedTestInstrumentedTest` 5/5, `DevicesListInstrumentedTest` 14/14,
`DeviceAccessibilityInstrumentedTest` 3/3, `SessionExpiryInstrumentedTest` 4/4,
`NavigationInstrumentedTest` 3/3, `NetworkChangeInstrumentedTest` 3/3. La suite complète
(54 tests) n'est pas lancée d'un bloc à cause du polling de 30 s. Les captures ont été
régénérées depuis le code courant ; elles restent des **maquettes de démonstration**, pas des
mesures.

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
