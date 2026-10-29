# Compilation et installation (Ubuntu)

Ce document décrit comment construire l'application Android **WiFi Admin** et
l'installer sur un téléphone ou un appareil connected depuis Ubuntu.

---

## 1. Prérequis

| Outil | Version | Vérification |
|---|---|---|
| **JDK** | 17 | `java -version` |
| **Android SDK** | platform **android-37**, build-tools **37** | `sdkmanager --list` |
| **Gradle** | fourni par le wrapper (9.7.1) | `./gradlew --version` |
| **Android Studio** | Ladybug ou plus récent | — |

Le SDK Android peut être installé via Android Studio :
**Settings → Languages & Frameworks → Android SDK → SDK Platforms**, puis
cocher **Android 17 (API 37)**.

### Chemin du SDK

Le build lit `sdk.dir` depuis `local.properties` (non versionné) :

```properties
sdk.dir=/home/<votre-utilisateur>/Android/Sdk
```

ou via la variable d'environnement `ANDROID_HOME` :

```bash
export ANDROID_HOME="$HOME/Android/Sdk"
export PATH="$ANDROID_HOME/platform-tools:$PATH"
```

---

## 2. Compilation

### Tests unitaires (aucun téléphone requis)

```bash
cd wifi-admin-app
./gradlew test
```

Cela exécute les tests des trois modules :

- `router-core` — le protocole ZLT (58 tests)
- `poc-cli` — les rendus console / JSON / diagnostic (16 tests)
- `app` — les ViewModels Android (29 tests)

### APK de debug

```bash
cd wifi-admin-app
./gradlew :app:assembleDebug
```

L'APK est produit dans :

```text
wifi-admin-app/app/build/outputs/apk/debug/app-debug.apk
```

### APK signé (release)

La configuration de signature n'est volontairement **pas** fournie : chaque
développeur crée la sienne. Voir `app/build.gradle.kts` (bloc `release`) et
ajoutez un `keystore.properties` **non versionné** :

```properties
storeFile=/chemin/vers/keystore.jks
storePassword=...
keyAlias=...
keyPassword=...
```

> Ne committez **jamais** un keystore ni ses mots de passe. Le `.gitignore`
> exclut `*.keystore`, `*.jks` et `local.properties`.

---

## 3. Installation sur un appareil

### 3.1 Activer le mode développeur

Sur le téléphone : **Paramètres → À propos du téléphone → Numéro de build**,
appuyer 7 fois. Puis **Paramètres → Options du développeur → Débogage USB**.

### 3.2 Brancher et autoriser

```bash
adb devices
```

L'appareil doit apparaître avec l'état `device` (et non `unauthorized`). Dans
ce cas, acceptez la demande d'autorisation sur l'écran du téléphone.

### 3.3 Installer

```bash
cd wifi-admin-app
./gradlew :app:installDebug
```

ou manuellement :

```bash
adb install -r app/build/outputs/apk/debug/app-debug.apk
```

### 3.4 Lancer

```bash
adb shell am start -n dev.wifiadmin.app/.MainActivity
```

### 3.5 Permissions

| Permission | Rôle |
|---|---|
| `INTERNET` | Socket TCP vers le routeur (OkHttp). |
| `ACCESS_NETWORK_STATE` | Vérifier qu'un réseau est disponible avant de tenter. |
| `ACCESS_LOCAL_NETWORK` | **Android 16+ (API 36)** : autorise les accès aux adresses privées (`192.168.1.1`). |

Sur Android 16 et ultérieurs, l'application demande la permission « réseau
local » au démarrage. **Sans elle, la connexion au routeur est bloquée par le
système** — c'est le comportement attendu d'Android, pas un bug.

---

## 4. Test sur un appareil réel

### 4.1 Prérequis réseau

- Le téléphone doit être **connecté au Wi-Fi du routeur** (ou en filaire).
- L'application ne parle **qu'à** l'adresse du routeur : aucune requête ne
  sort du réseau local.

### 4.2 Déroulé

1. Lancer l'application.
2. Sur l'écran de connexion, vérifier l'adresse (par défaut `192.168.1.1`) et
   le nom d'utilisateur (par défaut `admin`).
3. Saisir le mot de passe administrateur.
4. Appuyer sur **Se connecter**.
5. Le tableau de bord affiche le nombre d'appareils **lu sur le routeur à
   l'instant de la requête** — ces nombres ne sont pas figés.

### 4.3 Vérifier que rien ne part sur Internet

L'application n'a qu'une seule destination réseau : l'adresse du routeur que
vous saisissez. Pour le vérifier sur l'appareil :

```bash
adb shell dumpsys netstats | grep -A 5 dev.wifiadmin.app
```

Aucune connexion vers une adresse publique ne doit apparaître.

---

## 5. Diagnostic depuis le poste Ubuntu

Le module `poc-cli` permet de vérifier la connectivité **sans** téléphone.
C'est utile quand l'application échoue à se connecter.

### Depuis le routeur

```bash
cd wifi-admin-app
./gradlew :poc-cli:installDist
poc-cli/build/install/poc-cli/bin/poc-cli --host 192.168.1.1 --user root
```

Le mot de passe est demandé de manière interactive et n'est jamais écrit dans
l'historique du shell.

### Sans exposer les données du réseau

```bash
# Statistiques anonymisées : aucun MAC, IP, hostname ni SSID
poc-cli/build/install/poc-cli/bin/poc-cli \
    --host 192.168.1.1 --user root --format diagnostic

# Idem, plus la lecture du filtre MAC Wi-Fi (cmd 278, lecture seule)
poc-cli/build/install/poc-cli/bin/poc-cli \
    --host 192.168.1.1 --user root --format diagnostic --wifi-filter
```

> Sur le firmware Airtel 6.07.0, la lecture `cmd 278` renvoie `LIMITED_ACCESS`
> : le filtrage MAC Wi-Fi n'est pas disponible. C'est un comportement du
> firmware, pas une erreur de l'application.

### Faux routeur local (aucun matériel requis)

```bash
python3 tools/fake_router.py &          # écoute sur 127.0.0.1:18080
poc-cli/build/install/poc-cli/bin/poc-cli \
    --host 127.0.0.1:18080 --user root --format diagnostic
```

---

## 6. Dépannage

| Symptôme | Cause probable | Solution |
|---|---|---|
| `The router is not reachable` alors que le téléphone est sur le bon Wi-Fi | **Bogue corrigé** : l'appel réseau partait du thread principal, ce qu'Android interdit. Le client remontait alors une erreur réseau trompeuse. | Version actuelle : le réseau est exécuté sur `Dispatchers.IO`. Reconstruisez l'application. |
| `The router is not reachable` | Adresse erronée, pas sur le bon Wi-Fi, ou permission réseau local refusée | Vérifiez l'adresse (elle est détectée automatiquement), le Wi-Fi, et acceptez la permission réseau local |
| L'adresse du routeur est pré-remplie mais fausse | Détection par passerelle indisponible sur certains réseaux | Corrigez-la manuellement : le champ reste modifiable, et la détection ne l'écrase plus |
| `The router refused the credentials` | Mot de passe ou nom d'utilisateur incorrect | Vérifiez les identifiants ; le hash est `SHA-256(jeton + mot de passe)` |
| La liste est vide | Aucun bail DHCP, ou le routeur n'a rien remonté | Rafraîchissez ; vérifiez que des appareils sont bien connectés |
| L'application se connecte puis perd la session | Session expirée côté routeur | Le client se reconnecte automatiquement ; si cela persiste, vérifiez l'heure du routeur |
| `LIMITED_ACCESS` sur `cmd 278` | **Attendu** sur le firmware Airtel 6.07.0 | Aucune action : le filtrage Wi-Fi n'est pas exposé par ce firmware |
| La compilation échoue sur `local.properties` | `sdk.dir` absent | Créez le fichier (voir §1) |
| `SDK location not found` | ANDROID_HOME non défini | Exportez `ANDROID_HOME` ou créez `local.properties` |

---

## 7. Commandes utiles

```bash
./gradlew test                       # tous les tests
./gradlew :router-core:test          # protocole seul
./gradlew :app:testDebugUnitTest     # tests Android
./gradlew :app:assembleDebug         # APK debug
./gradlew :app:installDebug          # build + installation
./gradlew clean                      # nettoyage
./gradlew projects                   # liste des modules
```
