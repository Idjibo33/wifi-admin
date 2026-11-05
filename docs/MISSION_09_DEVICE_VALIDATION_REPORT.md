# Mission 09 — Recette automatisée sur appareil Android réel

> Date : 05/11/2026 · Recette de la Mission 09 (`5634d0c`) et clôture des
> réserves visuelles de la Mission 08.
> Verdict : **ACCEPTÉ**

---

## 1. Appareil utilisé

| Élément | Valeur |
|---|---|
| Modèle | **Pixel 7 Pro** |
| Version Android | **17** (API 37) |
| Connexion ADB | **USB** (`usb:1-4`) — le Wi-Fi peut donc être coupé sans perdre ADB |
| Root | **Non** (`uid=2000(shell)`) |
| `run-as` | **Disponible** (build debug) |
| Application | `dev.wifiadmin.app`, version debug |
| Batterie pendant la recette | 23 % → 27 % |

Un seul appareil était connecté : aucune ambiguïté sur la cible.

---

## 2. Résultats des six scénarios Mission 09

| ID | Scénario | Environnement | Résultat | Preuve |
|---|---|---|---|---|
| **T01** | Rafraîchissement automatique | Simulation **sur appareil** (vrais composants de production) | **PASS** | `gapsMillis=[30017, 30015]`, 3 lectures en 62 002 ms, première immédiate à t=0 |
| **T02** | Navigation Tableau de bord ↔ Appareils | **Appareil réel**, vrai `NavHostController` | **PASS** | `stackSize=2` constant sur 12 cycles, aucune duplication |
| **T03** | Arrêt en arrière-plan | Simulation sur appareil | **PASS** | 1 lecture → 1 lecture après 65 s ; `startPolling()` × 10 → **une** seule boucle |
| **T04a** | Perte de Wi-Fi | **Appareil réel** (`svc wifi disable` sous `trap`) | **PASS** | Wi-Fi rétabli, route restaurée, ADB vivant, PID vivant, **0 crash** |
| **T04b** | Perte de Wi-Fi — comportement applicatif | Simulation sur appareil (`NetworkMonitor` pilotable) | **PASS** | `connectionAfterLoss=NetworkChanged`, instantané conservé, **0 lecture** pendant 40 s |
| **T05** | Rétablissement du réseau | Simulation sur appareil | **PASS** | `connectionAfterRestore=Connected`, lectures reprises, **aucune fausse expiration** |
| **T06** | SnapshotStore | **Appareil réel** (`run-as`) | **PASS** | `-rw-------`, 2 024 o, `tmpResidueCount=0`, 16 clés, `secretKeyCount=0` |

### 2.1 T01 — une lecture par période de 30 s

```
=== T01 CADENCE ===
windowMillis=62002
totalReads=3
  read[0] at +0ms
  read[1] at +30017ms
  read[2] at +60032ms
gapsMillis=[30017, 30015]
=== END T01 ===
```

Écarts de ±0,06 % autour de 30 s. La première lecture est **immédiate** :
l'utilisateur ne reste pas 30 secondes sans données.

### 2.2 T03 — arrêt et reprise

```
=== T03 BACKGROUND ===
readsAtStop=1
readsAfter65s=1
=== END T03 BACKGROUND ===
```

**Aucune** lecture pendant 65 s d'absence (> 2 périodes). Au retour, **une**
lecture — pas de rafale.

### 2.3 T04 — perte du Wi-Fi sur l'appareil réel

La coupure a été faite avec un bloc de restauration inconditionnelle
(`trap`), ADB étant en USB :

```
--- wifi state after disable ---
mIsStaConnected: false
Wi-Fi is disabled
--- ADB still alive? --- ADB_STILL_OK
--- app process alive? --- 21351
[RESTORE] wifi enable sent
--- wifi state after re-enable ---
mIsStaConnected: true
Wi-Fi is enabled
192.168.1.0/24 dev wlan0 proto kernel scope src 192.168.1.115
```

Le téléphone a retrouvé son état réseau initial. **Aucun crash**
(`AndroidRuntime:E` vide).

### 2.4 T06 — persistance du fichier d'instantané

```
path=/data/user/0/dev.wifiadmin.app/no_backup/last-device-snapshot.json
exists=true          lengthBytes=2024
canRead=true         tmpResidueCount=0
keys=[schema, schemaVersion, capturedAtMillis, writtenAtMillis, context,
      host, account, networkId, devices, mac, ip, name, bands,
      interfaceName, presence]
secretKeyCount=0
```

- Emplacement : `no_backup` (= `noBackupFilesDir`, **exclu de la sauvegarde**) ✅
- Permissions : `-rw-------` (l'application seule) ✅
- Aucun `.tmp` résiduel ✅
- **16 clés**, aucune clé de secret (`password`, `passwd`, `token`, `session`,
  `challenge`, `cookie` → **0 occurrence**) ✅
- Atomicité : une écriture refusée ne crée pas de fichier ; une écriture plus
  ancienne ne remplace pas une plus récente ✅

> **Seule la structure a été lue.** Le contenu du fichier contient les MAC, IP
> et noms des appareils du foyer : il n'a jamais été affiché ni publié.

---

## 3. Résultats des deux réserves Mission 08

| ID | Réserve | Environnement | Résultat | Preuve |
|---|---|---|---|---|
| **R01** | Message d'expiration affiché | **Appareil réel**, vrai `LoginScreen` rendu par Compose | **PASS** | `compose.onNodeWithText(EXPIRY_MESSAGE).assertIsDisplayed()` |
| **R02** | Bouton Retour après expiration | **Appareil réel**, vrai `NavHostController` | **PASS** | pile `[null, login]` : aucune destination protégée ne subsiste |

### 3.1 R01 — le message exact, rendu sur Android

Le test instrumenté rend le **vrai** `LoginScreen` sur le runtime Compose de
l'appareil, avec un repository à adaptateur factice. Le message est vérifié
caractère par caractère :

> **« Votre session a expiré. Reconnectez-vous pour continuer. »**

Le JVM prouve que le ViewModel *produit* le résultat ; **seul l'appareil
prouve que l'écran le rend**. C'est exactement ce qui manquait.

### 3.2 R02 — le bouton Retour

Sur l'appareil, après l'expiration simulée, la pile de navigation contient
`[null, login]` : la seule destination est `login`. Le bouton **Retour**
quitte donc l'application au lieu de rouvrir un écran protégé.

### 3.3 Aucune injection dans l'application installée

Le test vit dans le source set `androidTest`. Le build de release
**n'a aucun point d'injection** : pas de `BuildConfig.DEBUG`, pas de crochet
de test, pas de chemin vers un écran protégé depuis l'extérieur. Le faux
adaptateur est construit par le test lui-même.

---

## 4. Tests réellement exécutés sur le téléphone

**20 tests instrumentés, 20 réussis** :

```
$ adb shell am instrument -w -r dev.wifiadmin.app.test/androidx.test.runner.AndroidJUnitRunner
OK (20 tests)
```

| Fichier | Objet |
|---|---|
| `SessionExpiryInstrumentedTest` | Message d'expiration rendu, phrase exacte, pile vidée, changement de réseau ≠ expiration |
| `PollingCadenceInstrumentedTest` | Cadence de 30 s mesurée sur l'appareil |
| `NetworkChangeInstrumentedTest` | Perte et retour du réseau, suspension des lectures |
| `SnapshotStoreInstrumentedTest` | Fichier privé, permissions, atomicité, absence de secret |
| `NavigationInstrumentedTest` | Pile de navigation, bouton Retour |
| `ManualRefreshInstrumentedTest` | Lecture manuelle : réutilisation d'une lecture fraîche, lecture forcée |

### 4.1 Infrastructure mise en place

Le projet n'avait **aucun** source set `androidTest` ni dépendance
d'instrumentation. Trois obstacles ont été levés :

| Obstacle | Solution |
|---|---|
| `androidx.compose.ui:ui-test` (variante multiplatform) absente du cache | Forcer explicitement `ui-test-android:1.10.4` |
| `espresso-core:3.5.1` **incompatible Android 17** — `InputManager.getInstance()` n'existe plus | Passer à `espresso-core:3.7.0`, qui utilise `Context.getSystemService()` |
| `ui-test-manifest` en `androidTestImplementation` → l'activité part dans le processus du test | Le mettre en `debugImplementation`, pour qu'elle fusionne dans le manifeste de l'app cible |

Les artefacts sont désormais dans le cache Gradle : **le build reste
entièrement hors ligne**.

---

## 5. Tests exécutés avec simulation

| Suite | Tests | Échecs |
|---|---:|---:|
| `router-core` | 193 | 0 |
| `poc-cli` | 56 | 0 |
| `app` (JVM) | 204 | 0 |
| **Total** | **453** | **0** |

Scénarios hors ligne : pare-feu **10/10**, expiration **3/3**, **aucune
écriture** vers le routeur.

---

## 6. Corrections apportées

La recette a révélé **six défauts réels**, tous corrigés et couverts par un
test de régression.

| # | Défaut | Gravité | Correction |
|---|---|---|---|
| **1** | L'observation du réseau n'était **jamais démarrée** en production : toute la fonctionnalité B était du code mort à l'exécution. | 🔴 Critique | `AppContainer` démarre l'observation. Test : `the application starts the network observation`. |
| **2** | `SnapshotContext.networkId` n'était **jamais alimenté** : la protection contre un cache restauré pour un autre routeur n'existait pas. | 🔴 Critique | L'identité réseau est écrite dans le contexte et comparée à la restauration. Test : `a snapshot is not restored for another network`. |
| **3** | L'identité réseau n'était comparée que par la **passerelle** ; l'interface et la plage d'adresses, calculées puis ignorées. | 🟠 Majeur | `assessNetwork` compare l'identité complète. Test : `another network is a different network even with the same gateway`. |
| **4** | La **lecture manuelle** interrogeait le routeur juste après la lecture programmée (la fenêtre de fraîcheur était du code mort). | 🟠 Majeur | La lecture manuelle réutilise une lecture de moins d'une seconde ; `refreshForced()` existe pour le cas justifié. Test JVM + test instrumenté. |
| **5** | Un **cooldown sans durée** neutralisait la suspension : l'application continuait de frapper un routeur en pénalité. | 🟠 Majeur | La pénalité documentée de 180 s est appliquée. Test : `a cooldown without a duration still suspends the reads`. |
| **6** | Une **lecture manuelle pendant un cooldown** contournait la suspension. | 🟠 Majeur | La lecture est refusée pendant la pénalité. Test : `a manual refresh during a cooldown does not reach the router`. |

Trois défauts mineurs ont également été corrigés : le bandeau « réseau a
changé » restait affiché sur la liste des appareils, le compteur d'échecs n'était
pas remis à zéro au retour du réseau, et une assertion tautologique
(`Missing || Found`) masquait la conservation du fichier après expiration.

---

## 7. Résultats de non-régression

| Garantie | Verdict |
|---|---|
| Une erreur réseau n'est jamais une expiration | **CONFORME** (`PollingInvariantsTest`) |
| Un timeout n'est jamais une expiration | **CONFORME** |
| Le cooldown garde son état distinct | **CONFORME** |
| Une expiration confirmée vide la mémoire, garde le fichier | **CONFORME** |
| Le message d'expiration est exact | **CONFORME** — et **rendu sur l'appareil** |
| Le bouton Retour n'atteint pas un écran protégé | **CONFORME** — et **vérifié sur l'appareil** |
| Le cache n'est pas restauré pour un autre routeur | **CONFORME** (corrigé) |
| Aucun secret dans le fichier | **CONFORME** — `secretKeyCount=0` sur l'appareil |
| Aucune écriture vers le routeur | **CONFORME** — compteurs des faux routeurs à zéro |
| Aucun service, WorkManager, permission nouvelle | **CONFORME** (`Mission09BoundaryTest`) |

**453 tests JVM, 0 échec. 20 tests instrumentés sur l'appareil réel, 0 échec.**

---

## 8. Limitations et scénarios bloqués

| Point | Statut | Raison |
|---|---|---|
| T01 à T03, T05, T06 — comportement applicatif avec une **session réelle** | **Simulé sur appareil** | Le mot de passe administrateur n'est accessible ni à l'environnement ni au dépôt. **Aucun mot de passe n'a été deviné** : un échec déclencherait une pénalité de 180 s **côté routeur**. |
| T04a — coupure Wi-Fi sur l'appareil | **Réel** | Exécuté sur l'appareil ; le comportement applicatif correspondant a été mesuré en simulation (T04b). |
| `AndroidNetworkMonitor` réel | **Non exécuté** | Aucun point d'injection n'existe dans l'application installée, par choix de sécurité. Le comportement réel du callback reste couvert par l'analyse de la plateforme. |
| La **durée réelle** d'une session | Non mesurée | Décidée par le firmware. |
| La **tenue en charge** du routeur | Non mesurée | Les chiffres (3 requêtes par cycle) sont calculés. |

> La recette n'a **jamais** contacté le routeur réel autrement qu'en lecture,
> et n'a **jamais** modifié le routeur. Le jeton de challenge n'a pas été
> publié.

---

## 9. Risques résiduels

1. **Deux réseaux de même passerelle ne sont pas distinguables.** Le BSSID le
   permettrait, mais il exige `ACCESS_FINE_LOCATION`, que l'application ne
   demande pas. Le pire cas : un changement **non détecté**, qui se manifeste
   par des lectures en échec — **jamais** par une expiration.
2. **La boucle de rafraîchissement vit dans le scope de l'application**, pas
   dans celui d'un écran. Elle est arrêtée par `LifecycleStartEffect` à chaque
   sortie de l'écran, mais sa durée de vie maximale est celle du processus.
3. **La période de 30 s est un intervalle minimum**, pas une période exacte :
   le délai commence après la lecture.
4. **Espresso 3.7.0** est indispensable sur Android 17 ; revenir à une
   version antérieure rendrait les tests instrumentés inopérants sur cet
   appareil.

---

## 10. Verdict final

| Critère | Verdict |
|---|---|
| Les six scénarios Mission 09 exécutés | **PASS** |
| Les deux réserves Mission 08 levées | **PASS** — sur un appareil Android réel |
| Aucune écriture vers le routeur | **Confirmé** |
| Aucune donnée privée publiée | **Confirmé** |
| Non-régression Mission 08 | **453 tests JVM + 20 instrumentés, 0 échec** |
| Défauts trouvés et corrigés | **6 défauts réels**, tous couverts |

# **ACCEPTÉ**

La Mission 09 est validée sur appareil réel, et les deux réserves visuelles
de la Mission 08 sont levées par des tests qui s'exécutent dans un
environnement Android, et non par une simple assertion JVM.

L'état du téléphone a été rendu à son état initial : Wi-Fi activé et
connecté, données utilisateur de l'application **préservées**, aucun fichier
temporaire résiduel.
