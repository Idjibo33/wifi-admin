# Plan d'implémentation — phase 1 (POC lecture seule)

> **Objet** : plan d'exécution de la phase 1 du projet `wifi-admin` — une bibliothèque Kotlin pure (`:router-core`) capable de lire la liste des appareils connectés sur un routeur ZLT W304VA PRO / X17M (firmware 6.07.0, passerelle `192.168.1.1`), plus une application console (`:poc-cli`) qui affiche le résultat.
> Le protocole est décrit dans **[`ROUTER_PROTOCOL.md`](ROUTER_PROTOCOL.md)**. Ce plan y renvoie explicitement plutôt que de le redéfinir.

---

## 1. Objectif et hors périmètre

### 1.1 Objectif

Livrer un **POC en lecture seule** qui :

1. s'authentifie sur le routeur (défi `cmd 232` puis `cmd 100`) ;
2. récupère la table DHCP (`cmd 223`) et les deux listes d'associations Wi-Fi (`cmd 224`, `cmd 225`) ;
3. fusionne ces trois sources en une **liste d'appareils uniques** avec les **bandes** associées et un **état** (`ACTIVE`, `CABLED`, `STALE`, `UNKNOWN`) ;
4. affiche le résultat en console **ou** en JSON (`--format json`) ;
5. fournit une base de code **réutilisable telle quelle** par l'application Android (phase 3).

### 1.2 Hors périmètre explicite

| # | Hors périmètre | Raison |
|---|---|---|
| **H1** | **Aucune interface utilisateur Android.** | La phase 1 est un POC console ; l'UI Compose arrive en phase 3. |
| **H2** | **Aucun filtrage / blocage MAC.** | Une règle de blocage est une **écriture** sur le routeur : hors de l'allowlist lecture seule (§8). |
| **H3** | **Aucune modification de configuration du routeur.** | Ni DNS, ni redémarrage, ni bascule Wi-Fi, ni verrouillage de bande/cellule, ni `cmd 3`, ni `cmd 6`, ni `cmd 25`, ni `cmd 233`. |
| **H4** | **Aucun débit par appareil.** | Le firmware n'expose pas de compteur par client (`ROUTER_PROTOCOL.md` §6, F4). |
| **H5** | **Aucun débit WAN / métriques cellulaires / histogramme.** | Hors sujet du POC « liste d'appareils » ; les commandes concernées (`cmd 18`, `cmd 401`, `cmd 113`, `cmd 1018`) ne sont pas dans l'allowlist. |
| **H6** | **Aucun renommage / alias persistant.** | Le POC affiche ce que le routeur renvoie. |

---

## 2. Mise en place Gradle

| Élément | Valeur |
|---|---|
| **Gradle** | **9.7.1** (wrapper `gradle-9.7.1-bin.zip`) |
| **Langage de build** | **Kotlin DSL** (`.kts`) |
| **Catalogue de versions** | `gradle/libs.versions.toml` |
| **Toolchain JVM** | **Java 17** (`kotlin { jvmToolchain(17) }`) |
| **Plugin appliqué** | `kotlin-jvm` (alias `libs.plugins.kotlin.jvm`), déclaré à la racine avec `apply false` |
| **Modules** | `:router-core` (bibliothèque Kotlin/JVM pure) et `:poc-cli` (application console) |

### 2.1 Catalogue de versions

`gradle/libs.versions.toml` :

```toml
[versions]
kotlin = "2.2.10"
junit = "4.13.2"
okhttp = "4.12.0"
orgJson = "20240303"

[libraries]
okhttp = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
# Real org.json implementation; android.jar only ships throwing stubs.
org-json = { group = "org.json", name = "json", version.ref = "orgJson" }
junit = { group = "junit", name = "junit", version.ref = "junit" }

[plugins]
kotlin-jvm = { id = "org.jetbrains.kotlin.jvm", version.ref = "kotlin" }
```

> `org-json` est utilisé car l'implémentation `org.json` d'`android.jar` ne contient que des stubs qui lèvent des exceptions : la même dépendance sert donc aux tests JVM **et** à l'app Android plus tard, sans divergence de comportement.

### 2.2 Modules

| Module | Type | Rôle |
|---|---|---|
| `:router-core` | `kotlin-jvm` — **bibliothèque Kotlin/JVM pure** | Toute la logique protocole : transport, authentification, modèles, fusion, classification. **Aucune dépendance à Android.** Réutilisable tel quel depuis l'app Android (phase 3). |
| `:poc-cli` | `application` + `kotlin-jvm` | Application console : parsing des arguments, lecture interactive du mot de passe, rendu console / JSON. Dépend de `:router-core`. |

`settings.gradle.kts` déclare les deux modules ; `gradle.properties` fixe `org.gradle.jvmargs=-Xmx2048m`, le parallélisme, le cache et `kotlin.code.style=official`.

> L'API d'Android n'étant pas disponible dans `:router-core`, **toute la sérialisation JSON passe par `org.json`** (et non par `kotlinx.serialization`), et le hachage par `java.security.MessageDigest` — les deux fonctionnent à l'identique sur JVM et Android.

---

## 3. Arborescence des modules et responsabilités

```
wifi-admin-app/
├── settings.gradle.kts              # inclut :router-core et :poc-cli
├── build.gradle.kts                 # plugins { alias(libs.plugins.kotlin.jvm) apply false }
├── gradle.properties
├── gradle/
│   ├── libs.versions.toml           # catalogue de versions
│   └── wrapper/gradle-wrapper.properties   # Gradle 9.7.1
├── docs/
│   ├── ROUTER_PROTOCOL.md           # le protocole (source de vérité)
│   └── IMPLEMENTATION_PLAN.md       # ce document
├── router-core/                     # bibliothèque Kotlin/JVM pure
│   ├── build.gradle.kts
│   └── src/
│       ├── main/kotlin/dev/wifiadmin/router/
│       │   ├── RouterSettings.kt    # interface : hôte, utilisateur, mot de passe
│       │   ├── RouterTransport.kt   # interface : une requête HTTP -> corps brut
│       │   ├── OkHttpRouterTransport.kt  # implémentation OkHttp (timeouts, pas de rejeu)
│       │   ├── ZltRouterClient.kt   # login(), fetchDevices() : cmd 223/224/225
│       │   ├── RouterModels.kt      # WifiBand, DevicePresence, DeviceSignal,
│       │   │                         #   RouterDevice, DeviceSnapshot
│       │   ├── RouterPayloadParser.kt  # DhcpLease, WifiAssociation + parsing JSON
│       │   ├── DeviceMerger.kt      # merge() : fusion DHCP + Wi-Fi, dé-dup, classification
│       │   ├── RouterExceptions.kt  # RouterException + les 3 sous-types
│       │   └── wifi/
│       │       ├── WifiMacFilterModels.kt     # WifiBandFilter (subcmd), WifiMacMode
│       │       ├── WifiMacFilterState.kt      # état d'une bande + validation MAC
│       │       ├── WifiMacFilterExceptions.kt # 4 catégories d'erreurs
│       │       └── WifiMacFilterClient.kt     # read / checkWritable / write / restore
│       └── test/kotlin/dev/wifiadmin/router/
│           ├── FakeRouter.kt        # transport factice + objet Fixtures
│           ├── ZltRouterClientTest.kt
│           ├── DeviceMergerTest.kt
│           ├── OkHttpRouterTransportTest.kt  # serveur HTTP sur 127.0.0.1
│           └── wifi/
│               ├── WifiMacFilterClientTest.kt       # 30 tests
│               └── WifiFilterReadOnlyBoundaryTest.kt # frontière lecture seule
├── poc-cli/                         # application console
│   ├── build.gradle.kts             # application { mainClass = "dev.wifiadmin.poc.MainKt" }
│   └── src/
│       ├── main/kotlin/dev/wifiadmin/poc/
│       │   ├── Main.kt              # args, prompt du mot de passe, confirmation, orchestration
│       │   ├── ConsoleRenderer.kt   # rendu texte
│       │   ├── JsonRenderer.kt      # rendu JSON
│       │   ├── DiagnosticRenderer.kt # statistiques anonymisées (phase 1)
│       │   └── WifiFilterRenderer.kt # rapport anonymisé du filtre MAC Wi-Fi
│       └── test/kotlin/dev/wifiadmin/poc/
│           ├── RendererTest.kt      # garanties de non-divulgation, schéma JSON
│           ├── DiagnosticRendererTest.kt
│           └── WifiFilterRendererTest.kt
├── app/                             # application Android (MVVM + Compose)
│   ├── build.gradle.kts             # AGP 9.4.1, Kotlin 2.4.0, Compose
│   ├── proguard-rules.pro           # règles R8 de la variante release
│   └── src/
│       ├── main/
│       │   ├── AndroidManifest.xml  # INTERNET, ACCESS_NETWORK_STATE, ACCESS_LOCAL_NETWORK
│       │   ├── res/                 # icônes, thèmes, chaînes
│       │   └── java/dev/wifiadmin/app/
│       │       ├── WifiAdminApplication.kt   # conteneur de dépendances
│       │       ├── MainActivity.kt           # activité unique + permission réseau local
│       │       ├── data/
│       │       │   ├── RouterAdapter.kt      # frontière : lecture seule
│       │       │   ├── ZltRouterAdapter.kt   # seul point de contact avec le protocole
│       │       │   ├── RouterRepository.kt   # état + rafraîchissement + erreurs
│       │       │   └── SettingsStore.kt      # DataStore : hôte + utilisateur (jamais le mdp)
│       │       ├── navigation/
│       │       │   ├── WifiAdminNavHost.kt   # Navigation Compose + barre inférieure
│       │       │   └── WifiAdminViewModelFactory.kt
│       │       └── ui/
│       │           ├── theme/Theme.kt        # Material 3, clair/sombre, couleurs dynamiques
│       │           ├── components/           # StatCard, LoadingState, ErrorState, EmptyState
│       │           ├── login/                # LoginScreen + LoginViewModel
│       │           ├── dashboard/            # DashboardScreen + DashboardViewModel
│       │           ├── devices/              # DevicesScreen + DevicesViewModel
│       │           └── settings/             # SettingsScreen + SettingsViewModel
│       └── test/java/dev/wifiadmin/app/
│           └── ui/                          # LoginViewModelTest, DevicesViewModelTest
└── tools/
    └── fake_router.py               # faux CGI local (lecture seule) pour les essais hors ligne
```

### 3.1 Responsabilités par fichier / classe

#### `:router-core` — `dev.wifiadmin.router`

| Fichier / classe | Responsabilité | Points clés |
|---|---|---|
| `RouterSettings.kt` | Source des paramètres de connexion. | `interface RouterSettings { val routerHost: String; val username: String; val password: String }`. Le POC fournit une implémentation anonyme à partir des arguments + du prompt ; l'app Android fournira une implémentation persistée (hors périmètre). |
| `RouterTransport.kt` | **Frontière réseau testable.** | `fun interface RouterTransport { fun post(url: String, body: String, timeoutSeconds: Long): String }`. Aucune dépendance OkHttp ici : c'est ce qui rend le client testable sans réseau. |
| `OkHttpRouterTransport.kt` | Implémentation HTTP réelle. | Un seul `POST` vers `http://{host}/cgi-bin/http.cgi`. En-têtes `Content-Type: application/json; charset=UTF-8` et `User-Agent`. Timeout par défaut **4 s** (connect/read/write), redéfini par requête via `newBuilder()`. **`retryOnConnectionFailure(false)`** : OkHttp ne doit jamais rejouer un POST tout seul. Une erreur HTTP non 2xx est convertie en `{"success": false, "error": "HTTP <code>"}` afin que le client la traite comme un échec protocolaire structuré. **Ne journalise jamais le corps des requêtes** (il porte le hash du mot de passe, le jeton et le `sessionId`). |
| `ZltRouterClient.kt` | Logique protocolaire. | `login()` (232 → SHA-256 → 100) et `fetchDevices()` (223 + 224 + 225 → `DeviceSnapshot`). Maintient le `sessionId`, détecte `NO_AUTH`/`LOGIN_TIMEOUT`, se reconnecte et **répète la lecture une seule fois**. **Aucune méthode générique exposée** : pas de `query(cmdId)` public. |
| `RouterModels.kt` | Modèle de données pur. | `WifiBand` (`BAND_2_4_GHZ`, `BAND_5_GHZ`), `DevicePresence` (`ACTIVE`, `CABLED`, `STALE`, `UNKNOWN`), `DeviceSignal` (rssiDbm, txRateMbps, rxRateMbps, ssid), `RouterDevice` (mac, ip, name, bands, signal, interfaceName, leaseExpiry, ipv6, presence), `DeviceSnapshot` (devices, capturedAtMillis + compteurs `activeCount`/`cabledCount`/`staleCount`/`unknownCount`). |
| `RouterPayloadParser.kt` | Conversion des réponses brutes en données typées. | `DhcpLease` (mac, ip, hostname, interfaceName, leaseExpiry, ipv6) et `WifiAssociation` (mac, ip, rssiDbm, txRateMbps, rxRateMbps, ssid) — **les champs sont conservés tels que le routeur les a envoyés**, l'interprétation se fait dans `DeviceMerger`. L'objet interne `RouterPayloadParser` porte les constantes `CMD_DHCP_LIST = 223`, `CMD_WIFI_2_4 = 224`, `CMD_WIFI_5 = 225` et fait le parsing JSON (tolérant aux champs manquants, conversion des nombres reçus en chaîne). |
| `DeviceMerger.kt` | Fusion et classification. | `DeviceMerger.merge(leases, wifi24, wifi5, nowEpochSeconds)` : applique les règles **R1–R5** et la classification **§5** de `ROUTER_PROTOCOL.md`. Ne contient **aucune** logique réseau. `nowEpochSeconds` est injecté pour rendre l'expiration des baux **testable et déterministe**. |
| `RouterExceptions.kt` | Hiérarchie d'exceptions typées. | `RouterException` (base), `RouterAuthenticationException`, `RouterNetworkException`, `RouterProtocolException` (§5). |

#### `:poc-cli` — `dev.wifiadmin.poc`

| Fichier / classe | Responsabilité |
|---|---|
| `Main.kt` | Parse les arguments, **lit le mot de passe de manière interactive** (non écho), demande une **confirmation explicite** avant d'envoyer quoi que ce soit, construit `RouterSettings`, instancie `ZltRouterClient`, produit le snapshot, choisit le rendu. Traduit les exceptions en message court + **code de sortie non nul** (3 / 4 / 5). |
| `ConsoleRenderer.kt` | Rendu texte stable et lisible. **N'imprime ni identifiants ni secrets.** |
| `JsonRenderer.kt` | Rendu JSON du snapshot. Schéma versionné, **sans secret**. |
| `RendererTest.kt` | Vérifie le schéma JSON et l'**absence de secrets** dans la sortie. |

### 3.2 Note sur la granularité des bandes

Un `RouterDevice` porte un **ensemble** de bandes (`Set<WifiBand>`), pas une bande unique. Un MAC présent dans les deux listes produit **un seul** `RouterDevice` portant les deux bandes — ce qui est conforme à la règle **R3** de `ROUTER_PROTOCOL.md` §4.2 (« les deux bandes sont conservées pour un MAC dual-band ») : aucune information n'est perdue, contrairement au défaut **D1** du client de référence. Le signal exposé est celui de la bande la plus forte.

---

## 4. Flux de données du POC

```text
args (CLI)
  │
  ▼
RouterSettings (host, username, password saisie interactive)
  │
  ▼
RouterTransport ──(OkHttpRouterTransport)──► POST http://{host}/cgi-bin/http.cgi
  │
  ▼
ZltRouterClient
  ├─ login()       : cmd 232 (token)  →  SHA-256(token + password)  →  cmd 100 (sessionId)
  ├─ cmd 223       : dhcp_list_info      → List<DhcpLease>
  ├─ cmd 224       : wlan24g_wifi_info   → List<WifiAssociation>
  └─ cmd 225       : wlan5g_wifi_info    → List<WifiAssociation>
  │
  ▼
DeviceMerger.merge(leases, wifi24, wifi5, nowEpochSeconds)
  │   jointure par MAC → repli IP, dé-dup DHCP par MAC, deux bandes conservées,
  │   classification ACTIVE / CABLED / STALE / UNKNOWN
  ▼
DeviceSnapshot (liste d'appareils, ordre déterministe)
  │
  ├──► ConsoleRenderer.render(snapshot.devices)   → stdout
  └──► JsonRenderer.render(snapshot.devices)      → stdout (--format json)
```

### 4.1 Détail du handshake

```text
1. cmd 232, method GET, sessionId ""        → { success, token }
2. passwd = sha256Hex(token + password)    → 64 caractères hexadécimaux
3. sessionId = UUID1.hex + UUID2.hex       → 64 caractères hexadécimaux (générés par le client)
4. cmd 100, method POST                     → { success | message | sessionId }
5. succès si success == true OU message == "success" OU la réponse contient "sessionId"
6. si la réponse ne contient pas de sessionId, on conserve celui généré à l'étape 3
```

> Détail complet et justifications : `ROUTER_PROTOCOL.md` §2.

---

## 5. Politique de gestion des erreurs

### 5.1 Exceptions typées

| Exception | Signification |
|---|---|
| `RouterException` | Base de toutes les erreurs du module. |
| `RouterAuthenticationException` | Échec d'authentification (`cmd 232` sans jeton, `cmd 100` rejeté) **ou** session toujours expirée après reconnexion (`NO_AUTH` / `LOGIN_TIMEOUT` persistants). |
| `RouterNetworkException` | Échec réseau : timeout, reset de connexion, hôte injoignable. |
| `RouterProtocolException` | Réponse HTTP illisible, JSON invalide, champ attendu absent ou de type inattendu. |

Le POC convertit ces exceptions en **message utilisateur court + code de sortie non nul**, sans jamais afficher le mot de passe, le jeton de défi ni le `sessionId` (le message d'une `RouterAuthenticationException` ne doit contenir ni le jeton ni l'identifiant de session).

### 5.2 Timeouts

| Paramètre | Valeur | Raison |
|---|---|---|
| Timeout par défaut (`OkHttpRouterTransport.DEFAULT_TIMEOUT_SECONDS`) | **4 s** | Le routeur est sur le LAN ; au-delà c'est un problème réseau, pas une latence du CGI. |
| `connectTimeout` / `readTimeout` / `writeTimeout` | **4 s** (la valeur par défaut, redéfinie par requête) | Symétrique sur les trois opérations, cohérent avec le client de référence. |
| Timeout par commande | **4 s** | Reprend la valeur du client de référence (`timeoutSeconds: Long = 4`). |

> Sources : `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt:37-44` (4 s / 5 s / 4 s) et `:174-179` (`timeoutSeconds: Long = 4`). L'implémentation `:router-core` unifie les trois timeouts sur **4 s**.

### 5.3 Nombre de tentatives

| Situation | Comportement |
|---|---|
| **Lecture** qui échoue côté réseau | **1 nouvelle tentative** (connexion pool obsolète), puis `RouterNetworkException`. **Au maximum 2 appels.** |
| **Lecture** qui reçoit `NO_AUTH` / `LOGIN_TIMEOUT` | **Reconnexion** (`login()`), puis **1 seule** répétition de la lecture. |
| **Lecture** qui réussit | 1 appel. |
| **Session toujours expirée après la reconnexion** | `RouterAuthenticationException`. |
| **Écriture** | **Interdite en phase 1.** Aucune écriture n'est implémentée, donc aucun rejeu ambigu n'est possible. Si une écriture était ajoutée plus tard, elle **ne doit jamais** être rejouée sur erreur réseau (voir `ROUTER_PROTOCOL.md` §2.5). |

> **Pourquoi le rejeu est sûr ici** : la phase 1 n'implémente que des **lectures**
> (`223`, `224`, `225`). Une lecture rejouée ne fait que dupliquer une requête
> d'interrogation. Le client de référence applique exactement la même règle
> (`reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt:190-199`) :
> il rejoue une lecture, jamais une écriture.
>
> Sources : `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt:190-213`, `reference-zlt/app/src/test/java/com/airtel/monitor/data/remote/ZltRouterClientTest.kt:86-140`.

### 5.4 Journalisation

* **Aucun secret dans les logs** : ni mot de passe, ni `SHA-256(token + password)`, ni jeton de défi, ni `sessionId`.
* Les logs ne contiennent que : la commande (`cmd`), le statut, la durée, et le message d'erreur du routeur.
* Aucun log en production par défaut ; le POC n'écrit que sur `stdout`/`stderr`.

---

## 6. Règles de classification (rappel)

La classification est **conservatrice**. Chaque état exige une **preuve positive** ; en l'absence de preuve, l'état est `UNKNOWN`.

| État | Condition résumée |
|---|---|
| `ACTIVE` | Présent dans `wlan24g_wifi_info` **ou** `wlan5g_wifi_info` (association radio courante). |
| `CABLED` | `interface` filaire **ET** absent des **deux** listes Wi-Fi. |
| `STALE` | `interface` = `wlan` **ET** absent des **deux** listes Wi-Fi **ET** `expires` objectivement analysable comme déjà expiré. |
| `UNKNOWN` | Tout le reste (défaut), y compris un bail Wi-Fi absent des listes dont `expires` est absent ou de format inconnu. |

> **Rappel critique** : l'absence dans les listes Wi-Fi **n'est pas** une preuve de connexion active et **ne rend pas** un appareil filaire ; un bail DHCP **n'est pas** une preuve de connexion active ; le format de `expires` **n'est pas** validé.
> Règles complètes, preuves et limites : **`ROUTER_PROTOCOL.md` §5**.

---

## 7. Contrat de sortie du POC

### 7.1 Format console (défaut)

```
Devices reported by the router (2):

pixel-6         192.168.1.192  aa:bb:cc:dd:ee:ff  2.4 GHz, 5 GHz  signal: -52 dBm (tx 1200 / rx 1200)  active
nas-ethernet    192.168.1.50   11:22:33:44:55:66  -               signal: -                                 cabled
```

Règles :

* une ligne par **appareil** (un MAC dual-band apparaît sur **une** ligne portant ses deux bandes) ;
* colonnes : nom, IP, MAC, bandes, signal, état ;
* les valeurs inconnues sont rendues par `-` ;
* **aucun identifiant, aucun secret** dans la sortie.

### 7.2 Format JSON (`--format json`)

```json
{
  "schemaVersion": 1,
  "deviceCount": 2,
  "devices": [
    {
      "name": "pixel-6",
      "ip": "192.168.1.192",
      "mac": "aa:bb:cc:dd:ee:ff",
      "bands": ["5GHZ"],
      "signal": { "rssiDbm": -52, "txRateMbps": "1200", "rxRateMbps": "1200", "ssid": "Wifi-5G" },
      "interface": "wlan",
      "leaseExpiry": "3600",
      "ipv6": "",
      "presence": "ACTIVE"
    }
  ]
}
```

### 7.3 Garanties sur la sortie JSON

| # | Garantie |
|---|---|
| **G1** | Le JSON **ne contient ni mot de passe, ni hash du mot de passe, ni jeton de défi (`token`), ni `sessionId`**, ni nom d'utilisateur. Le test `json output contains no credentials tokens or session fields` le vérifie en cherchant les chaînes `password`, `passwd`, `token`, `session`, `username`. |
| **G2** | Seules des données **renvoyées par le routeur** pour les appareils sont exportées. |
| **G3** | `presence` ne prend que les valeurs `ACTIVE`, `CABLED`, `STALE`, `UNKNOWN`. |
| **G4** | `bands` est trié et ne contient que `2_4GHZ` et `5GHZ`. |
| **G5** | `schemaVersion` est incrémenté à **tout changement incompatible** du format (valeur actuelle : `1`). |
| **G6** | `rssiDbm` est un **nombre** (nullable) ; `txRateMbps`/`rxRateMbps`/`ssid` restent des **chaînes** (le firmware renvoie des chaînes — cf. `ROUTER_PROTOCOL.md` §6, F1). |
| **G7** | Un signal absent est sérialisé en `null` JSON (pas une chaîne vide). |

### 7.4 Format diagnostic (`--format diagnostic`)

Un troisième format, **anonymisé par construction**, destiné au partage des
observations : il ne contient **aucune** donnée identifiante.

```
=== Diagnostic anonymisé du routeur ===

Commandes envoyées (lecture seule) : 232, 100, 223, 224, 225

Appareils détectés : 3

Répartition des états :
  ACTIVE   1
  CABLED   1
  STALE    0
  UNKNOWN  1

Appareils par bande Wi-Fi :
  2.4 GHz   1
  5 GHz     1
  sans bande 2

Valeurs de `interface` (catégorisées) :
  filaire            1   (valeurs distinctes : 1)
  sans fil           2   (valeurs distinctes : 1)

Format du champ `expires` (forme uniquement, jamais la valeur) :
  absent ou vide                     2
  entier (durée ?), 4 chiffres       1

Champs `hostname` :
  renseignés              2
  non renseignés / "NULL" 1

Présence du signal (device) :
  avec RSSI               1
  sans RSSI               2
  SSID présent            1
  bandes multiples        1
```

**Ce que ce format garantit** :

| # | Garantie |
|---|---|
| **D1** | **Aucune** adresse MAC, IP, hostname, SSID ou remarque n'est imprimée. |
| **D2** | La valeur de `expires` n'est **jamais** imprimée : seule sa **forme** est décrite (absent / entier court / entier long / timestamp / durée / date-heure / texte). |
| **D3** | Les valeurs d'`interface` sont **catégorisées** (`sans fil` / `filaire` / `inconnue` / `autre`) ; le nombre de valeurs distinctes est indiqué, pas les valeurs. |
| **D4** | Le test `the diagnostic never exposes a mac an ip a name or a ssid` vérifie l'absence de MAC, IP, hostname et SSID connus dans la sortie. |
| **D5** | Le test `the diagnostic describes the shape of expires without showing the value` vérifie que la valeur brute n'apparaît pas. |

> Ce format est celui à utiliser pour consigner les observations du routeur
> réel dans la documentation, sans divulguer d'information sur le réseau.

---

## 8. Règles de sécurité et de sûreté

| # | Règle | Mise en œuvre |
|---|---|---|
| **S1** | **Allowlist stricte des commandes** : `{232, 100, 223, 224, 225}` en phase 1, étendue en mission 04 à `{23, 30}` **en lecture seule**. | `ZltRouterClient` n'expose publiquement que `login()` et `fetchDevices()`. Les commandes du pare-feu passent par une porte `internal readOnly(cmd)` qui refuse tout numéro hors allowlist **avant** de construire la requête. Aucune méthode publique générique `query(cmdId)`. |
| **S2** | **Aucune commande mutante.** | Ni reboot (`cmd 6`), ni DNS (`cmd 3`), ni Wi-Fi (`cmd 2`/`cmd 211`), ni mode avion (`cmd 226`), ni données mobiles (`cmd 222`), ni verrouillage de bande (`cmd 161`) ou de cellule (`cmd 160`), ni QoS (`cmd 25`), ni `cmd 233`, ni `cmd 20` (application des règles pare-feu). |
| **S3** | **Aucune écriture de règle / blocage MAC.** | Le module ne fait que **lire** le pare-feu (`GET 23`, `GET 30`) ; aucune fonction de blocage n'existe. Le client de lecture n'expose ni `cmd`, ni `method`, ni `token`. |
| **S4** | **Le mot de passe n'est jamais passé en argument de ligne de commande.** | Il est lu **de manière interactive** sur le terminal (prompt non écho). Un argument `--password` est **proscrit** (il fuiterait dans l'historique du shell et dans `ps`). Le test `login never sends the password in clear text` vérifie que le corps de la requête `cmd 100` ne contient jamais le mot de passe en clair. |
| **S5** | **Aucun secret dans les logs ni dans la sortie JSON.** | Voir G1 et §5.4. |
| **S6** | **Aucun rejeu aveugle d'écriture.** | Sans objet en phase 1 (lecture seule) ; la règle est documentée et devra être respectée si une écriture est ajoutée. |
| **S7** | **Confirmation explicite avant tout accès.** | Le POC demande une confirmation interactive listant les commandes utilisées ; `--yes` permet de la sauter pour les executions non interactives. En cas de refus, **aucune requête n'est envoyée**. |
| **S8** | **Accès limité au réseau local du routeur.** | Aucune donnée n'est transmise à un tiers ; le POC ne fait que parler au routeur. |
| **S9** | **Compte à privilèges minimal.** | Les identifiants du compte `root` du routeur ne sont pas nécessaires pour `cmd 223/224/225` en principe ; **à vérifier sur l'appareil réel** (une validation en lecture seule avec un compte non-admin si possible). |

---

## 9. Stratégie de tests

**Framework : JUnit 4** (`junit = "4.13.2"`), exécuté par la tâche `test` standard de Gradle (`useJUnit()` dans `router-core/build.gradle.kts`).

**Principe : aucun test ne touche le réseau.** `FakeRouter` implémente `RouterTransport`, répond par `(cmd, method)` et **enregistre toutes les requêtes**, ce qui permet d'affirmer *ce qui a été envoyé* autant que *ce qui a été reçu*. L'objet `Fixtures` fournit des constructeurs de réponses réalistes.

### 9.1 `FakeRouter` (classe de test partagée)

* Enregistre chaque requête dans `requests` (données `RecordedRequest` : url, body, timeout).
* `onJson(cmd, method?)` : réponse JSON ; un handler `"cmd:method"` l'emporte sur `"cmd"`.
* `onRaw(cmd, method?)` : corps de réponse arbitraire (JSON invalide).
* `onNetworkFailure(cmd, method?)` : simule une coupure réseau.
* `sent(cmd, method?)` : toutes les requêtes envoyées pour une commande.

### 9.2 `ZltRouterClientTest` — comportements couverts

| # | Test | Ce qu'il vérifie |
|---|---|---|
| T1 | `login sends sha256 of challenge token plus password` | Le champ `passwd` vaut exactement `SHA-256(token + password)` en hex minuscule ; le **token précède** le mot de passe dans l'ordre. |
| T2 | `login forwards the username` | Le champ `username` est transmis tel quel. |
| T3 | `login never sends the password in clear text` | Le corps de la requête `cmd 100` ne contient jamais le mot de passe en clair. |
| T4 | `the session id returned by the router is used for later requests` | Le `sessionId` renvoyé par le routeur est repris dans les requêtes suivantes. |
| T5 | `the client generated session id is kept when the router omits it` | `sessionId` = **64 caractères hexadécimaux** ; conservé si le routeur n'en renvoie pas. |
| T6 | `the challenge token request has the expected shape` | `cmd 232`, `method GET`, `sessionId` vide. |
| T7 | `the login request has the expected shape` | `cmd 100`, `method POST`, `isAutoUpgrade = "0"` (chaîne), `subcmd = 0` (entier), `sessionId` de 64 caractères. |
| T8 | `login fails when the router rejects the credentials` | Un refus explicite du routeur ⇒ échec de la connexion. |
| T9 | `an expired session triggers re-login and repeats the read exactly once` | `NO_AUTH` ⇒ `login()` puis **une seule** répétition ; `cmd 100` émis deux fois. |
| T10 | `a login timeout triggers re-login and repeats the read exactly once` | Idem pour `LOGIN_TIMEOUT`. |
| T11 | `a session that stays expired after re-auth raises an authentication exception` | Exception typée, **sans fuite de jeton ni de session id** dans le message. |
| T12 | `a network failure raises a network exception` | Une coupure réseau ⇒ `RouterNetworkException`. |
| T13 | `malformed json raises a protocol exception` | Une réponse non-JSON ⇒ `RouterProtocolException`. |
| T14 | `a non-success reply raises a protocol exception` | Une réponse `success: false` non gérée ⇒ `RouterProtocolException`. |
| T15 | `an http error reply raises a protocol exception` | Une erreur HTTP ⇒ `RouterProtocolException` (et non un `{}` silencieux). |
| T16 | `only the read commands are ever sent` | **Test d'allowlist** : sur l'exécution complète du POC, les `cmd` émis appartiennent à `{232, 100, 223, 224, 225}` (plus `{23, 30}` si `--firewall-filter`) — **c'est le test de sécurité central**. |
| T17 | `dhcp entries are parsed into devices` | Les entrées `cmd 223` sont bien converties en appareils. |
| T18 | `the hostname NULL is normalized to Unknown Device` | Le littéral `"NULL"` ⇒ `"Unknown Device"`. |
| T19 | `a blank hostname is normalized to Unknown Device` | Un nom vide ⇒ `"Unknown Device"`. |
| T20 | `numeric fields arriving as strings are parsed` | `rssi`/`txrate`/`rxrate` reçus en **chaîne** sont bien analysés en nombre. |
| T21 | `wifi 24ghz associations are parsed` | Les associations `cmd 224` sont bien converties. |
| T22 | `wifi 5 ghz associations are parsed` | Les associations `cmd 225` sont bien converties. |
| T23 | `a numeric expires value arriving as a string is parsed` | `expires` reçu en **chaîne** est conservé tel quel, sans interprétation abusive. |
| T24 | `a wired lease without a wifi association is cabled` | Via le client complet : `interface = lan` ⇒ `CABLED`. |
| T25 | `a wifi lease without a current association and an expired lease is stale` | Via le client complet : bail Wi-Fi expiré ⇒ `STALE`. |
| T26 | `every request goes to the router cgi endpoint` | **Test d'URL** : toutes les requêtes visent `http://{hôte}/cgi-bin/http.cgi`, rien d'autre. |
| T27 | `the default request timeout is four seconds` | Le délai par défaut est bien **4 s**. |
| T28 | `the request timeout is configurable` | Le délai est transmissible par requête. |
| T29 | `an exception message never carries the session id or a token` | **Garantie de non-divulgation** : ni `sessionId` ni jeton hexadécimal dans un message d'exception, même si le routeur les renvoie. |
| T30 | `an exception message never carries a non hex challenge token echoed by the router` | Même garantie pour un jeton **non hexadécimal** : le format du jeton n'est pas contraint par le protocole, il doit être retiré explicitement. |
| T31 | `a read that fails on the network is retried once` | Une erreur réseau sur une lecture ⇒ **une** reprise (connexion pool obsolète). |
| T32 | `a read that keeps failing on the network is not retried more than once` | **Au maximum 2 appels** réseau par lecture, puis `RouterNetworkException`. |

### 9.3 `DeviceMergerTest` — comportements couverts

| # | Test | Ce qu'il vérifie |
|---|---|---|
| M1 | `a wifi association is joined to the dhcp lease by mac` | Jointure par MAC (normalisée : `AA:BB:…` = `aa:bb:…`) ⇒ `ACTIVE`. |
| M2 | `ip matching is used only when the mac does not match anything` | L'IP n'est utilisée **qu'en repli**. |
| M3 | `a matching mac wins over a matching ip` | La correspondance MAC l'emporte. |
| M4 | `a mac present in both bands keeps both bands` | Un MAC dual-band donne **un** appareil portant les **deux** bandes. |
| M5 | `a 2.4 ghz entry does not overwrite the 5 ghz signal` | Corrige le défaut **D1** : le signal 5 GHz n'est pas écrasé. |
| M6 | `duplicate dhcp leases with the same mac are deduplicated` | Deux lignes DHCP de même MAC ⇒ **un seul** appareil (corrige **D2**). |
| M7 | `a lease with a blank mac is kept` | Une ligne sans MAC n'est **pas** supprimée (règle **R5**). |
| M8 | `a wired interface without a wifi association is cabled` | `interface = lan` + absent des deux listes ⇒ `CABLED`. |
| M9 | `a wifi interface with a current association is active` | Présence radio ⇒ `ACTIVE`. |
| M10 | `a wifi interface without an association and an unparseable expiry is unknown` | `expires = "24h"` (format inconnu) ⇒ `UNKNOWN` (**pas** `STALE`). |
| M11 | `a wifi interface without an association and an expired lease is stale` | `expires` = timestamp passé ⇒ `STALE`. |
| M12 | `a wifi interface without an association and an expired marker is stale` | Un marqueur d'expiration explicite ⇒ `STALE`. |
| M13 | `a blank interface is unknown` | Une `interface` absente ne permet **aucune** conclusion ⇒ `UNKNOWN`. |
| M14 | `a device missing from dhcp but present in wifi is included as active` | Présence radio sans bail DHCP ⇒ conservé et `ACTIVE`. |
| M15 | `a blank hostname becomes Unknown Device` | Un nom absent ⇒ `"Unknown Device"`. |
| M16 | `the hostname NULL becomes Unknown Device` | Le littéral `"NULL"` ⇒ `"Unknown Device"`. |
| M17 | `the snapshot order is deterministic` | L'ordre de sortie est stable (donc testable et comparable d'une exécution à l'autre). |

### 9.4 `OkHttpRouterTransportTest` — comportements couverts

Un petit serveur HTTP est ouvert sur `127.0.0.1` (port éphémère) : **aucun
trafic externe** n'est produit, et le routeur n'est pas sollicité.

| # | Test | Ce qu'il vérifie |
|---|---|---|
| O1 | `posts a json body and returns the reply` | Le corps JSON envoyé est bien celui attendu, et la réponse brute est restituée. |
| O2 | `turns a non 2xx status into a structured error` | Un statut HTTP ≠ 2xx devient `{"success": false, "error": "HTTP <code>"}` — une **erreur structurée**, pas un `{}` silencieux. |
| O3 | `a refused connection raises an io exception` | Une connexion refusée remonte une `IOException` (que le client traduit en `RouterNetworkException`). |

### 9.5 `RendererTest` — comportements couverts

| # | Test | Ce qu'il vérifie |
|---|---|---|
| R1 | `console output lists name ip mac band signal and presence` | Le rendu console contient nom, IP, MAC, bande, signal et état. |
| R2 | `console output reports an empty device list` | Sortie déterministe quand le routeur ne renvoie rien. |
| R3 | `json output contains no credentials tokens or session fields` | **Garantie G1** : ni `password`, `passwd`, `token`, `session`, `username` dans le JSON. |
| R4 | `json output exposes the documented schema` | `schemaVersion`, `deviceCount`, et la structure d'un appareil. |
| R5 | `json output keeps a null signal as json null` | Un signal absent est `null` JSON (garantie G7). |
| R6 | `json output uses the documented key order` | L'ordre des clés emitted suit l'ordre documenté (`name`, `ip`, `mac`, `bands`, `signal`, `interface`, `leaseExpiry`, `ipv6`, `presence`). |

### 9.6 Configuration de test

`router-core/build.gradle.kts` :

```kotlin
dependencies {
    implementation(libs.okhttp)
    implementation(libs.org.json)
    testImplementation(libs.junit)
}

tasks.test {
    useJUnit()
    testLogging {
        events("passed", "skipped", "failed")
    }
}
```

> `libs.org.json` est nécessaire **aussi dans les tests** : l'implémentation `org.json` d'Android ne fonctionne pas hors Android.

### 9.7 Couverture actuelle

| Module | Tests | Échecs |
|---|---|---|
| `router-core` | **183** | 0 |
| `poc-cli` | **56** | 0 |
| `app` | **35** | 0 |
| **Total** | **274** | **0** |

Le détail par classe est dans les rapports JUnit
(`*/build/test-results/`). Les sections 14.4, 15.5 et 16.4 conservent les
comptes **à la date de chaque mission** ; le compte de référence est celui
ci-dessus, repris en §17.4.

| Module | Total |
|---|---|
| `router-core` | 87 |
| `poc-cli` | 16 |
| `app` | 19 |
| **Total** | **122** |

Aucun de ces tests n'atteint le routeur : le transport est soit factice
(`FakeRouter`), soit un serveur HTTP local sur `127.0.0.1`.

---

## 10. Commandes d'exécution

### 10.1 Tests

```bash
./gradlew :router-core:test
```

Test d'un seul fichier de test, avec sortie détaillée :

```bash
./gradlew :router-core:test --tests 'dev.wifiadmin.router.DeviceMergerTest' --info
```

### 10.2 POC — format console

```bash
./gradlew :poc-cli:run --args="--host 192.168.1.1 --user root"
```

Le mot de passe est demandé **de manière interactive** (non écho) et une **confirmation** est demandée avant tout accès au routeur. Le mot de passe n'est **jamais** passé en argument.

### 10.3 POC — format JSON

```bash
./gradlew :poc-cli:run --args="--host 192.168.1.1 --user root --format json"
```

Pour rediriger vers un fichier :

```bash
./gradlew :poc-cli:run --args="--host 192.168.1.1 --user root --format json" > devices.json
```

> **Flux de sortie** : `stdout` ne contient **que** le résultat (la liste en
> console ou le document JSON). Les invites (mot de passe, confirmation) et les
> messages d'erreur vont sur `stderr`. Le JSON est donc directement exploitable
> par un tube ou une redirection, sans aucun caractère parasite :
>
> ```bash
> poc-cli --host 192.168.1.1 --user root --format json 2>/dev/null | jq '.devices'
> ```
>
> L'ordre des clés JSON suit l'ordre documenté (`name`, `ip`, `mac`, `bands`,
> `signal`, `interface`, `leaseExpiry`, `ipv6`, `presence`). Le JSON reste, par
> définition, un objet **non ordonné** : ne construisez pas de logique sur cet
> ordre.

### 10.4 POC — format diagnostic (anonymisé)

```bash
./gradlew :poc-cli:run --args="--host 192.168.1.1 --user root --format diagnostic"
```

Ne contient **aucune** adresse MAC, IP, hostname, SSID ni valeur d'`expires` :
uniquement des compteurs, la répartition des états et la **forme** des champs.
C'est le format à utiliser pour partager les observations d'un routeur réel.

Pour conserver le résultat dans un fichier sans y laisser d'invite :

```bash
./gradlew :poc-cli:run --args="--host 192.168.1.1 --user root --format diagnostic" 2>/dev/null | tee diagnostic.txt
```

### 10.5 Aperçu des arguments

| Argument | Obligatoire | Défaut | Description |
|---|---|---|---|
| `--host <host>` | non | `192.168.1.1` | Adresse de la passerelle. |
| `--user <username>` | non | `root` | Identifiant du compte sur le routeur. |
| `--format <format>` | non | `console` | `console`, `json` ou `diagnostic`. |
| `--wifi-filter` | non | — | Ajoute la lecture **anonymisée** du filtre MAC Wi-Fi (`cmd 278`, lecture seule). |
| `--yes` | non | — | Saute la confirmation interactive (usage non interactif). |
| `--help` | non | — | Affiche l'usage. |
| `--password` | **interdit** | — | Le mot de passe est lu interactivement, jamais en argument. |

### 10.5bis Lecture du filtre MAC Wi-Fi (lecture seule)

```bash
./gradlew :poc-cli:run --args="--host 192.168.1.1 --user root --format diagnostic --wifi-filter"
```

Envoie uniquement `GET 278` avec `subcmd '1'` (2,4 GHz) et `subcmd '0'`
(5 GHz), en plus des commandes de la phase 1. Le rapport ne contient
**aucune** adresse MAC : uniquement le mode, le nombre d'entrées et la
structure JSON observée.

```
=== Filtre MAC Wi-Fi (cmd 278, lecture seule) ===
Bande 2.4 GHz :
  mode               deny
  entrées            1
  structure observée success, datas, datas.macfilter, datas.maclist, datas.maclist[].mac, datas.maclist[].remarks
  remarque non vide  1 / 1
  MAC toutes normalisées : true
```

> **Frontière garantie par un test** (`WifiFilterReadOnlyBoundaryTest`) : aucun
> `POST 278` n'est envoyé par ce chemin. Les méthodes `write`, `restore` et
> `disable` existent dans `router-core` mais ne sont appelées par aucun code
> du CLI.

### 10.6 Compilation seule

```bash
./gradlew :router-core:build :poc-cli:build
```

### 10.7 Essai hors ligne contre un faux routeur local

`tools/fake_router.py` est un serveur CGI local qui répond aux **cinq** seules
commandes autorisées (232, 100, 223, 224, 225) avec des données fictives. Il
permet de vérifier le POC de bout en bout **sans toucher le routeur**.

```bash
# Terminal 1 — démarrage du faux routeur sur 127.0.0.1:18080
python3 tools/fake_router.py

# Terminal 2 — POC en console
./gradlew :poc-cli:installDist
printf 'fakepassword\ny\n' | poc-cli/build/install/poc-cli/bin/poc-cli \
    --host 127.0.0.1:18080 --user root

# Terminal 2 — POC en JSON
printf 'fakepassword\ny\n' | poc-cli/build/install/poc-cli/bin/poc-cli \
    --host 127.0.0.1:18080 --user root --format json

# Terminal 2 — POC en diagnostic anonymisé
printf 'fakepassword\ny\n' | poc-cli/build/install/poc-cli/bin/poc-cli \
    --host 127.0.0.1:18080 --user root --format diagnostic
```

Le faux routeur couvre les cas suivants : MAC dual-band (2,4 GHz **et** 5 GHz),
bail sur interface `lan`, bail Wi-Fi sans association radio, et `hostname` valant
`NULL`. L'affichage attendu est respectivement `ACTIVE` (avec les deux bandes),
`CABLED`, `UNKNOWN` et `Unknown Device`.

> Le mot de passe saisi ci-dessus est un **factice** : il n'a aucune valeur sur
> un vrai routeur.

---

## 11. Feuille de route

### Phase 1 — Ce travail (POC lecture seule)

* [x] Bibliothèque `:router-core` : transport, client, modèles, fusion, classification.
* [x] Application console `:poc-cli` avec sorties console et JSON.
* [x] Tests JUnit 4 avec transport factice (`FakeRouter`, `Fixtures`).
* [x] Documentation : `ROUTER_PROTOCOL.md` + ce plan.

### Phase 2 — Validation en lecture seule sur l'appareil réel *(en attente de l'accord explicite de l'utilisateur)*

* Exécuter le POC contre le **ZLT W304VA PRO / X17M, firmware 6.07.0**, à `192.168.1.1`.
* Observer et documenter :
  * le **format réel de `expires`** (condition de possibilité de l'état `STALE`) ;
  * les valeurs exactes de `interface` (`wlan` / `lan` et autres éventuelles) ;
  * la présence de `hostname` = `"NULL"` ;
  * le type réel des valeurs (`rssi`, `txrate`, `rxrate`) ;
  * l'encodage du SSID dans `cmd 224` / `cmd 225` ;
  * les MAC vides et les doublons MAC/IP dans la table DHCP.
* Ajuster les règles de classification **et** `ROUTER_PROTOCOL.md` en conséquence.
* **Aucun changement de configuration.** Les seules requêtes envoyées restent 232, 100, 223, 224, 225.

### Phase 3 — Application Android (Compose)

* Nouveau module `:app` (AGP + Kotlin + Compose), consommation de `:router-core` **sans duplication**.
* Écran « Appareils » : liste des appareils, état, bandes, signal, IP ; rafraîchissement périodique.
* Persistance des paramètres de connexion (hôte, utilisateur) ; mot de passe stocké de façon sécurisée (Keystore).
* Permission `android.permission.ACCESS_LOCAL_NETWORK` pour Android 16+ (cf. `reference-zlt/docs/ANDROID_NOTES.md:36-39`).
* **Les fonctions de blocage MAC restent hors périmètre** tant que l'utilisateur n'en a pas fait la demande explicite : elles constitueraient une écriture sur le routeur.

### Hors feuille de route

Toutes les commandes d'écriture (`cmd 2`, `cmd 3`, `cmd 6`, `cmd 25`, `cmd 160`, `cmd 161`, `cmd 211`, `cmd 222`, `cmd 226`, `cmd 233`, `cmd 288`) restent hors périmètre. Toute introduction future exigera un **allowlist explicite distincte**, une **politique de rejeu** et une **validation par relecture** documentées.

---

## 12. Risques et points ouverts

| # | Risque / question ouverte | Impact | Traitement |
|---|---|---|---|
| **R1** | **Format de `expires` inconnu.** | L'état `STALE` est impossible à produire tant que le format n'est pas connu. | Parseur **strict et explicite** : seul un marqueur `expired` ou un **timestamp epoch ≥ 1 000 000 000** dont la valeur est passée donne `STALE`. Une durée courte (`"3600"`) ou un format inconnu (`"24h"`) donne `UNKNOWN`. Le seuil distingue un timestamp d'une durée et évite un `STALE` faux. Validation en phase 2. |
| **R2** | **Le routeur peut exposer `dhcp_list_info` aussi via `cmd 401`** (`ROUTER_PROTOCOL.md` §6, F3). | Sans conséquence en phase 1 (`cmd 401` n'est pas dans l'allowlist). | Reste documenté ; `cmd 401` n'est pas utilisé. |
| **R3** | **Le SSID peut être en Base64 dans `cmd 224`/`cmd 225`.** | Un SSID affiché en Base64 serait trompeur pour l'utilisateur. | Le POC affiche le SSID **brut** ; décodage conditionnel après validation en phase 2. |
| **R4** | **Un appareil hors DHCP mais associé en radio.** | Présence Wi-Fi sans bail DHCP. | Conservé et classé `ACTIVE` (le DHCP n'est pas la source de vérité de la présence radio) — cf. test M14. |
| **R5** | **Dérive du firmware 6.07.0 par rapport au firmware de référence.** | Un champ pourrait manquer ou changer de type. | Toute absence de champ est traitée comme **valeur inconnue**, jamais comme une valeur par défaut inventée. |
| **R6** | **Le compte `root` dispose de privilèges que la phase 1 n'exploite pas.** | Surface d'attaque élargie en cas de fuite d'identifiants. | Vérifier en phase 2 si un compte non-admin suffit pour 223/224/225 (règle **S9**) ; le mot de passe n'est de toute façon jamais transmis ailleurs qu'au routeur. |
| **R7** | **Le prompt du mot de passe désactive l'écho du terminal** (`stty -echo`) quand l'entrée est un vrai terminal. | Le mot de passe ne s'affiche pas à l'écran pendant la saisie. | Sur un **pipe** (script, test), le terminal n'est pas touché et l'écho n'a rien à masquer. Un `SIGKILL` brutal peut laisser l'écho désactivé ; un `SIGINT` (Ctrl-C) est couvert par le *shutdown hook*. L'app Android utilisera un champ `SecureTextField`. Aucune trace n'est écrite dans l'historique du shell. |

---

## 13. Mission 04 — pare-feu `cmd 23` (lecture seule)

Cette section complète le plan de phase 1 ; elle ne le remplace pas.

### 13.1 Périmètre livré

| Élément | Fichier | État |
|---|---|---|
| Analyse et niveaux de preuve | `docs/FIREWALL_ANALYSIS.md` | Livré |
| Client de lecture seule | `router-core/.../firewall/FirewallMacFilterClient.kt` | Livré |
| Modèles | `router-core/.../firewall/FirewallMacModels.kt` | Livré |
| Parseur | `router-core/.../firewall/FirewallMacFilterParser.kt` | Livré |
| Porte de lecture allowlistée | `ZltRouterClient.readOnly(cmd)` (`internal`) | Livré |
| Erreur `LIMITED_ACCESS` | `RouterFeatureUnavailableException` | Livré |
| Tests client + parseur | `router-core/src/test/.../firewall/` | 38 tests |
| Diagnostic CLI | `--firewall-filter` + `FirewallFilterRenderer` | Livré |
| Faux routeur | `GET 23` / `GET 30`, écritures refusées sans mutation | Livré |
| Interface Android | — | **Non modifiée** |
| Écriture / boutons Bloquer | — | **Interdit** |

### 13.2 Commandes

- Lecture : `GET 23`, `GET 30`.
- Jamais : `POST 23`, `POST 30`, `cmd 20`, `cmd 233`.
- `cmd 28` n'est pas utilisé : `cmd 30` donne la même information pour IPv4
  et IPv6.

### 13.3 Tests

| Suite | Total |
|---|---|
| `router-core` | 131 |
| `poc-cli` | 24 |
| `app` | 26 |
| **Total** | **181** |

Le test de frontière échoue **avant l'envoi** si une écriture est tentée
(`AssertionError` dans le transport, handlers pièges dans `FakeRouter`,
multiset exact des couples `(cmd, method)`).

### 13.4 Décision d'architecture

Repli additif retenu : `ZltRouterClient` conserve la session et expose une
porte `internal readOnly(cmd)` allowlistée. L'extraction d'un `ZltSession`
commun est reportée jusqu'à validation réelle : elle déplacerait ~200 lignes
du fichier le plus critique pour un bénéfice nul tant que `GET 23` n'est pas
prouvé.

### 13.5 Condition de sortie

La fonctionnalité n'est **pas** opérationnelle. Elle le deviendra seulement
si :

1. `GET 23` et `GET 30` réussissent sur le routeur réel (pas de
   `LIMITED_ACCESS`) ;
2. la forme réelle de `datas` est confirmée ;
3. la portée du filtre (Internet / LAN / administration) est mesurée ;
4. les conditions **G0–G11** de `FIREWALL_ANALYSIS.md` §8.2 sont satisfaites.

---

## 14. Mission 05 — diagnostic des réponses `GET 23` / `GET 30`

### 14.1 Constat

La lecture réelle a renvoyé, pour `GET 23` **et** `GET 30`, une réponse
`{cmd, message, success}` **sans `datas`**. Le POC affichait « règles lues 0 »
et « (aucune règle) » : une **affirmation fausse** sur l'état du routeur.

### 14.2 Cause

`read()` ne vérifiait que `success`. La présence de la charge utile n'était
jamais contrôlée, et le parseur transformait l'absence de `datas` en
`emptyList()`.

### 14.3 Corrections

| # | Correction |
|---|---|
| 1 | Quatre états distincts : `DATA_RECEIVED`, `RESPONSE_INVALID`, `FEATURE_UNAVAILABLE`, `UNKNOWN`. Un nombre de règles n'est affiché que dans le premier. |
| 2 | `datas: null` et `datas: []` sont distingués (le test qui affirmait la confusion a été inversé). |
| 3 | `LIMITED_ACCESS` est un refus **même quand `success: true`** — bug actif. |
| 4 | Liste blanche stricte pour `message` / `error` ; tout le reste devient `<message>`. |
| 5 | Enveloppe d'observation : `success`, catégorie de `message`, type de `datas`, présence d'erreur, clés, `cmd` renvoyé — **aucun contenu brut**. |
| 6 | Les noms de clés affichés passent par une liste blanche. |
| 7 | Le diagnostic liste les commandes **réellement envoyées** (journal au niveau transport), plus une liste de *possibles* qui n'est plus affichée comme un historique. |
| 8 | `language` est désormais envoyé avec chaque requête, comme le fait l'interface web du routeur (`main.js:1799-1801`). |
| 9 | Une lecture qui échoue n'annule plus l'autre. |
| 10 | Test-frontière exhaustif : les 401 numéros hors allowlist sont rejetés sans requête. |

### 14.4 Tests (à la date de la mission 05)

| Suite | Total |
|---|---|
| `router-core` | **183** |
| `poc-cli` | **39** |
| `app` | **26** |
| **Total** | **225** |

Huit scénarios de bout en bout hors ligne (`tools/fake_router_scenarios.py`) :
tous conformes, **aucune tentative d'écriture** enregistrée.

### 14.5 Faisabilité (historique — tranchée en §17)

> **Périmé.** Cette section décrivait trois pistes de lecture encore ouvertes.
> L'observation de l'interface officielle du 30/10/2026 les a **toutes
> fermées** : la capacité est `UNAVAILABLE`. Voir §17.

---

## 15. Mission 06 — validation de l'interopérabilité

### 15.1 Conformité de l'enveloppe

Le JavaScript **original** du routeur a été retrouvé en cache local et
vérifié. L'enveloppe de `ZltRouterClient.send()` est conforme à
`Page.postJSON` (`main.js:1785-1818`) pour les sept champs : `cmd`,
`method`, `sessionId`, `language`, `token` (absent en GET), `subcmd`,
`datas`.

**Le hash du mot de passe n'est pas affecté** : `login.js:238` calcule
`sha256(token + passwd)`, et `language` est ajouté **après**, juste avant la
sérialisation. Un test le vérifie explicitement.

### 15.2 Quatre défauts corrigés (trouvés par les audits)

| # | Défaut | Correction |
|---|---|---|
| **B1** | Les noms de clés de la réponse étaient affichés bruts : un routeur pouvait nommer un champ d'après un secret, ou y mettre un saut de ligne et **forger une ligne du rapport**. | `SafeKeyNames` : liste blanche exacte, troncature au préfixe, suppression des caractères de contrôle. Partagé par les deux renderers. |
| **B2** | `cmd 223/224/225` sans charge utile → « 0 appareil » ; `cmd 278` sans `datas` → « filtre désactivé ». | `DeviceSnapshot.payloadAbsent` ; `WifiMacFilterState.mode` nullable ; rendu `INCONNU` ; écriture d'un état inconnu **refusée** ; **propagé à l'app Android**. |
| **B3** | Le `message`, le `macfilter` et le `wifiStatus` bruts entraient dans des exceptions affichées par le CLI. | Type fermé `WifiFilterError` ; `describeReply` à catégories fermées ; plus aucune valeur brute dans une exception. |
| **O-1** | **OkHttp suivait les redirections** : un `307` rejouait le corps du `POST 100` (hash du mot de passe, `sessionId`) vers l'hôte désigné par la réponse. | `followRedirects(false)`, `followSslRedirects(false)`. Test de loopback : le `307` n'est pas suivi et le corps n'est pas rejoué. |

### 15.3 Autres correctifs

`language` envoyé par le client Wi-Fi ; `cmd 28` ajouté à l'allowlist
déclarée du CLI ; handler `cmd 28` et scénario de bout en bout ; tripwire
`POST 28` / `POST 30` ; le journal affiche les **multiplicités** (le double
login du client Wi-Fi est visible).

### 15.4 Variantes de lecture

Trois formes, chacune changeant **une seule chose** : `default`, `getfun`
(ajoute `getfun: true` à `GET 23`), `cmd28` (remplace `GET 30` par
`GET 28`). `getfun` reste opt-in : le défaut ne change pas.

### 15.5 Tests (à la date de la mission 06)

| Suite | Total |
|---|---|
| `router-core` | **183** |
| `poc-cli` | **56** |
| `app` | **28** |
| **Total** | **267** |

Scénarios hors ligne : 9/9 conformes, aucune tentative d'écriture.

### 15.6 Défauts connus restants

Le double login du client Wi-Fi (hash transmis deux fois), le rejeu
aveugle d'une écriture Wi-Fi après expiration de session, et le code mort
`redact()`. Documentés dans `FIREWALL_ANALYSIS.md` §15bis. Aucun n'est
atteignable depuis le chemin de lecture.

---

## 16. Mission 07 — investigation finale de l'interopérabilité

### 16.1 Corrections

| # | Correction | Justification |
|---|---|---|
| 1 | La variante `getfun` envoie **`getfun: "true"`** (chaîne) au lieu d'un booléen JSON. | Ce firmware encode tous ses drapeaux en chaîne (`acceptAll: "true"`, `isAutoUpgrade: "0"`, `subcmd: '1'`). Un booléen aurait été ignoré. |
| 2 | Le faux routeur reproduit la réponse **réelle** (`message` vide). | Une fixture qui s'écarte de la réalité ne prouve rien. |
| 3 | Nouveau scénario `fw-msg-empty-datas` + tests `message` vide / null / absent. | Prouve que le message vide ne dégrade pas la distinction liste vide / absence. |
| 4 | `tools/anonymise_observation.py` : anonymisation **locale** des observations DevTools. | Projection structurelle + filet qui refuse tout rapport contenant une valeur secrète. |

### 16.2 Une conclusion de la mission 05 est renversée

L'hypothèse « `language` a changé la réponse du firmware » **n'est pas
soutenue** : le diagnostic n'affiche jamais la valeur du `message`, et
`language` a été ajouté le **30/10**, après l'observation du **29/10**. La
comparaison « avant / après » n'a jamais été faite sur le routeur.

Le `message` vide est un **témoin discriminant**, pas une preuve de
changement de comportement.

### 16.3 Recommandation

**Une seule** observation de l'interface officielle
(`DEVTOOLS_PROCEDURE.md`), puis **arrêt** :

- si l'interface reçoit `datas` et nous non → correction ciblée ;
- si l'interface reçoit la **même** réponse creuse → le firmware ne sert pas
  cette commande à ce compte → **abandonner**, comme pour `cmd 278`.

### 16.4 Tests (à la date de la mission 07)

| Suite | Tests | Échecs |
|---|---|---|
| `router-core` | **183** | 0 |
| `poc-cli` | **56** | 0 |
| `app` | **28** | 0 |
| **Total** | **267** | **0** |

Scénarios hors ligne : **10/10** conformes, aucune tentative d'écriture.

> Ces chiffres sont ceux de la mission 07 **avant** l'ajout des tests de
> frontière d'architecture. Le compte final de la clôture est en §17.4.

---

## 17. Clôture — filtrage MAC `UNAVAILABLE` (30/10/2026)

### 17.1 Verdict

> **La capacité de filtrage MAC du pare-feu est `UNAVAILABLE` sur le
> firmware 6.07.0 d'Airtel Niger.**

Ce n'est **pas** `UNVERIFIED` : le doute est levé. L'interface web
**officielle** du routeur, observée dans Chrome DevTools (*Pare-Feu → Règles
de filtrage → Filtrage MAC*), reçoit **exactement** la même réponse que notre
client :

```json
{"success": true, "cmd": 23, "message": ""}
```

HTTP 200, **sans `datas`**. Deux clients indépendants, deux codes différents,
même réponse : la requête n'est pas en cause.

### 17.2 Décisions

| Objet | Décision |
|---|---|
| Code d'analyse `router-core/.../firewall/` | **Conservé** avec ses tests, comme archive. Jamais appelé par l'app. |
| Diagnostic CLI (`--firewall-filter`, `--firewall-variant`) | **Conservé mais gelé.** |
| Fonctionnalité Android | **Non exposée** : aucun écran, aucun bouton, aucun import. |
| Écriture (`POST 23` / `POST 30` / `cmd 20`) | **Interdite.** Jamais tentée. |
| Variantes `getfun`, `cmd 28`, `language` | **Interdites** sans fait nouveau (§17.3). |
| MVP Android | **Inchangé**, sauf corrections indispensables. |

### 17.3 Ce qui rouvrirait le dossier

Un **fait nouveau**, pas une hypothèse : autre firmware, autre opérateur ou
autre compte dont l'interface officielle reçoit des données, ou capture
réseau montrant une requête différente de la nôtre.

### 17.4 Tests de clôture

| Suite | Tests | Échecs |
|---|---|---|
| `router-core` | **183** | 0 |
| `poc-cli` | **56** | 0 |
| `app` | **35** | 0 |
| **Total** | **274** | **0** |

Scénarios hors ligne : **10/10** conformes, **aucune** tentative d'écriture.

Les deux tests supplémentaires de `app` matérialisent la frontière : l'app
n'importe **jamais** `dev.wifiadmin.router.firewall` ni
`dev.wifiadmin.router.wifi`, `RouterAdapter` n'expose **aucune** opération
mutante, et aucun écran ne propose « Bloquer » / « Débloquer ».

### 17.5 Nettoyage

- `ZltRouterClient.redact()`, la constante `HEX_TOKEN_PATTERN` et le champ
  `challengeToken` (qui n'existait que pour cette fonction morte) ont été
  **supprimés**.
- Les KDoc de `FirewallMacFilterClient`, `FirewallMacFilterParser` et
  `FirewallMacModels` ne parlent plus d'une « fonctionnalité à venir » : ils
  enregistrent le verdict `UNAVAILABLE`.
- `docs/FIREWALL_ANALYSIS.md` §13ter est la référence de la clôture.
