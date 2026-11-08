# Mission 13 — Architecture Kotlin et réseau Android (speedtest réel LibreSpeed)

> Date : 07/11/2026 · Base Mission 12 (`feature/mission-12-speedtest`, moteur réel **désactivé**).
> Périmètre : brancher un moteur réseau réel derrière l'interface `SpeedTestEngine` existante, sans toucher à `router-core`,
> sans aucun test réel vers Internet pendant le développement, sans serveur public par défaut, sans secret, sans télémétrie.
> Revue : PR vers `develop` uniquement, aucune fusion.
> skills : `codebase-design` (module profond, interface, seam, adaptateur), `tdd` (tests au seam public, tranches verticales),
> `systematic-debugging` (concurrence/annulation). Contraintes produit : plafonds **20 Mo DL / 10 Mo UL / 30 s**,
> **désactivé par défaut** sans endpoint configuré, **HTTPS obligatoire** (exception loopback tests).

---

## 1. État actuel (Mission 12) et ce que Mission 13 doit préserver

Le dépôt livre déjà, dans `app/src/main/java/dev/wifiadmin/app/data/speedtest/` :

| Fichier | Rôle | État |
|---|---|---|
| `SpeedTestEngine.kt` | Interface `SpeedTestEngine` (`providerLabel`, `run(onProgress)`, `cancel()`), `DisabledSpeedTestEngine`, `FakeSpeedTestEngine`, **et** `SpeedTestRepository` (single-flight, `generation`, slot `running`, `withTimeout(30 s)`) | Interface déjà profonde : 3 membres, timeouts déclarés mais **non branchés** (aucun transport Internet) |
| `SpeedTestPolicy.kt` | Plafonds `20 Mo / 10 Mo / 30 s`, timeouts connect/read/write/call, validation `init` | Contrat produit déjà figé |
| `SpeedTestCalculator.kt` | `mbps`, médiane latence, `isCapped`, jauge — pur JVM | Gazette stable, testé par 12 tests |
| `SpeedTestResult.kt` | `SpeedTestResult`, `SpeedTestState`, `SpeedTestFailure`, `CancelReason` | `capped` déjà modélisé, `providerLabel` déjà porté |
| `SpeedTestHistoryStore.kt` | DataStore `wifi_admin_speedtest`, 20 entrées / 30 jours, consentement | Liste blanche de clés, aucun secret |
| `WifiLinkInfoProvider.kt` | Liaison sans permission | Jamais de `0`, jamais confondu avec Internet |

Et dans `ui/speedtest/` : `SpeedTestViewModel` (StateFlow + `watchNetworkLoss` + consentement), `SpeedTestScreen` (jauge,
bandeau « indisponible », liens externes manuels HTTPS), `SpeedTestPresentation` (phrases FR, `httpsExternalLinks()`).

Injection dans `WifiAdminApplication.AppContainer` : `speedTestEngine = DisabledSpeedTestEngine()`,
`speedTestRepository = SpeedTestRepository(engine, monitor, linkProvider, clock, scope, policy)`.

**Invariants hérités, non négociables en Mission 13** (revue corrective Mission 12, `MISSION_12_IMPLEMENTATION_REPORT.md` §4) :

1. Un seul test en vol — slot `running` + `Job.invokeOnCompletion` comme seule libération, `generation` atomique filtrant
   toute écriture périmée, `cancel()` sous le même verrou que la prise du slot. Un `start()` refusé tant que le run
   précédent n'a pas entièrement libéré le moteur.
2. Un test ne démarre que sur `LocalNetworkStatus.LocalWifi` (hotspot exclu), annulé sur `NoWifi`/`NotOnWifi`/changement
   d'`identity.key` — **aucun fallback mobile**, aucun polling, aucun service d'arrière-plan, aucun retry automatique.
3. Séparation stricte (`Mission12BoundaryTest`) : le moteur ne connaît ni `ZltRouterClient`, ni `RouterTransport`, ni
   `/cgi-bin`, ni `cmd 100`. Aucun endpoint réel codé en dur dans `data/speedtest/`.
4. Limite connue (report §7) : les plafonds octets reposent **actuellement sur le moteur seul**, sans backstop indépendant
   dans le repository. Mission 13 doit corriger ce point avant tout trafic réel.
5. `AndroidManifest.xml` : `INTERNET` déjà requis pour le trafic routeur ; rien ne sort du LAN tant que le moteur est désactivé.
   Aucune permission de localisation n'est demandée pour la liaison (dégradation gracieuse `SecurityException`).

## 2. Décision 1 — Emplacement du moteur : conserver l'interface dans `data/speedtest/`

### 2.1 Verdict

**On conserve, on ne déplace pas.** Le moteur réel ne crée ni package nouveau ni module Gradle nouveau :

- `SpeedTestEngine` **reste l'interface profonde** dans `data/speedtest/SpeedTestEngine.kt` (module profond au sens
  `codebase-design` : petite interface — `providerLabel`, `run`, `cancel` — et derrière elle toute la mécanique réseau).
- `DisabledSpeedTestEngine` reste l'adaptateur de production par défaut.
- `FakeSpeedTestEngine` reste l'adaptateur de test et de maquettes.
- `LibreSpeedEngine` (nouveau) devient **le seul** adaptateur réseau, dans le même package, sans Android, sans `Context`,
  sans `Router*`, sans `router-core`.

Justification (vocabulaire `codebase-design`) :

- **Le seam existe déjà et est réel** (deux adaptateurs — `Disabled` et `Fake` — partagent l'interface ; un troisième fait
  de la seam une seam éprouvée). Déplacer l'interface dans `router-core` ou dans un nouveau module Kre ferait passer un cas
  LAN-only privé dans le module du protocole routeur : fuite de domaine, et violation du principe « le protocole vit dans
  `router-core`, l'application ne connaît ni commande ni CGI ».
- **La profondeur est déjà là** : 3 membres d'interface couvrent latence → download → upload, progression, annulation et
  nettoyage. Ajouter un quatrième membre (`configure()`, `close()`, `stream()`) l'aplatirait : l'appelant devrait apprendre
  l'ordre, les ressources et les erreurs. Le design doit rester : l'appelant apprend **une** chose — `run()` suspendu,
  annulable coopérativement, avec `cancel()` comme fermeture — et le repository apprend le reste.
- **Le test de suppression** (`codebase-design`) : supprimer le module `data/speedtest` ferait réapparaître, côté repository,
  ViewModel et UI, toute la gestion des phases, des compteurs, des intervalles et des erreurs. Le module gagne sa vie.
- **La connaissance reste locale** (`locality`) : si un jour le protocole LibreSpeed change (chemins, paramètres, en-têtes),
  un seul fichier change. Ni la UI, ni le repository, ni `router-core` ne bougent.

### 2.2 Ce que le package `data/speedtest/` contient après Mission 13

```text
data/speedtest/
  SpeedTestEngine.kt            interface + Disabled + Fake + SpeedTestRepository (conservés, voir §4)
  SpeedTestPolicy.kt            plafonds + timeouts (conservé ; endpoint absent — voir §5)
  SpeedTestCalculator.kt        arithmétique pure (conservé)
  SpeedTestResult.kt            résultat + états + erreurs (conservé ; §9 précise provider/hôte)
  SpeedTestHistoryStore.kt      DataStore + codec (conservé ; aucun endpoint persisté)
  WifiLinkInfoProvider.kt       liaison (conservé)
  SpeedTestEndpoint.kt          NOUVEAU — type validé (hôte, schéma, chemins) + factory de validation pure (§5)
  LibreSpeedEngine.kt           NOUVEAU — adaptateur réseau OkHttp derrière SpeedTestEngine (§7)
  LibreSpeedClient.kt           NOUVEAU — HTTP bas niveau (GET/POST, compteurs) ; seam interne, jamais exposé à la UI (§7)
```

`router-core/` **n'est pas touché** : pas de fichier, pas de dépendance, pas de version bump. La dépendance OkHttp du moteur
est ajoutée au module `app` (déjà présente dans `router-core` en `4.12.0`, mais `app` ne doit pas compter sur un transitif
pour son propre trafic — voir §12). Le protocole LibreSpeed n'est ni un `cmd`, ni une session, ni un token routeur.

### 2.3 Adaptateurs derrière le seam (engagements)

| Adaptateur | Interfaces | Comportement |
|---|---|---|
| `DisabledSpeedTestEngine` | `SpeedTestEngine` | Échoue vite avec `Disabled`, zéro octet. `AppContainer` le choisit tant qu'aucun endpoint valide n'est configuré. |
| `FakeSpeedTestEngine` | `SpeedTestEngine` | Compte les volumes, honore les plafonds, démontre les états. Tests et previews uniquement. |
| `LibreSpeedEngine` | `SpeedTestEngine` | Mesure réelle vers l'endpoint validé. Seul endroit qui appelle OkHttp. |

L'interface est **le seam public** : ViewModel, repository, tests d'orchestration et tests Compose ne croisent que
`SpeedTestEngine`, jamais `LibreSpeedClient`. Le progrès traverse le seam en `SpeedTestState.Running` ; les appels OkHttp,
les fenêtres de 250 ms et les flux parallèles restent **derrière** le seam (implementation), pas dans l'interface.

## 3. Diagramme de modules

```text
┌─────────────────────────────── app ───────────────────────────────┐
│  ui/speedtest/                                                    │
│  ┌────────────────────┐    ┌─────────────────────────────────────┐ │
│  │ SpeedTestScreen    │◄───│ SpeedTestViewModel                  │ │
│  │ (Compose, jauge,   │    │ StateFlow, consentement,            │ │
│  │ bandeau, « vers … »)   │ cancel + watchNetworkLoss           │ │
│  └────────────────────┘    └──────────────────┬──────────────────┘ │
│                                                │ interface        │
│  data/speedtest/                               ▼                    │
│  ┌────────────────────────────────────────────────────────────────┐ │
│  │ SpeedTestRepository (module profond)                            │ │
│  │ single-flight, generation, withTimeout(30 s),                  │ │
│  │ revalidation Wi-Fi, aucun retry, window budget (§9)            │ │
│  └───────────────┬──────────────────────────────────┬─────────────┘ │
│                  │ interface SpeedTestEngine (seam) │              │
│          ┌───────┴───────┐  ┌─────────┴───────┐      ┌────────────┴─────┐
│          │ Disabled (prod│  │ Fake (tests)    │      │ LibreSpeed (réel)│
│          │ par défaut)   │  │                 │      │ OkHttp, compteurs│
│          └───────────────┘  └─────────────────┘      └────────┬─────────┘
│                                                              │ endpoint validé
│  SpeedTestEndpoint (validation pure)  SpeedTestPolicy (plafonds)  │
│  SpeedTestCalculator (pur)  SpeedTestHistoryStore (DataStore)      │
└──────────────────────────────────────────────────────────────────┘
         └─ AUCUN import ZltRouterClient / RouterTransport / cmd ──► router-core (inchangé)
```

Le seul canal de sortie vers Internet est `LibreSpeedEngine` → `LibreSpeedClient` → OkHttp. Tout le reste (UI, repository,
historique, liaison) est offline-by-construction : sans endpoint, l'adaptateur choisi est `Disabled`, et le bouton reste
désactivé.

## 4. Interface proposée

### 4.1 L'interface publique (`SpeedTestEngine`) — inchangée dans sa forme

```kotlin
interface SpeedTestEngine {
    /** Libellé long affiché avant tout test, ex. « vers serveur LibreSpeed (hôte) ». */
    val providerLabel: String

    /**
     * Latence (5 sondes → médiane) → download → upload, progression émise en cours de route.
     * Suspension = annulation coopérative (ensureActive) ; seul timeout global = le withTimeout du repository.
     * Renvoie SpeedTestResult (débits, octets comptés, capped) ou lève SpeedTestFailureException.
     */
    suspend fun run(onProgress: suspend (SpeedTestState.Running) -> Unit): SpeedTestResult

    /** Ferme sockets, appels et streams en cours. Idempotent, appelable depuis n'importe quel thread. */
    fun cancel()
}
```

Aucun paramètre nouveau n'est ajouté à `run()` : **les dépendances se prennent en constructeur, elles ne se créent pas**
(en règle `tdd` / `codebase-design` : *accept dependencies, don't create them*). Le moteur reçoit son endpoint, son
OkHttpClient et sa policy à la construction ; l'appelant ne sait ni ne doit savoir lesquels.

### 4.2 `LibreSpeedEngine` — constructeur

```kotlin
class LibreSpeedEngine(
    private val endpoint: SpeedTestEndpoint,          // déjà validé (§5), HTTPS, pas d'identifiants
    private val client: LibreSpeedClient,              // adaptateur HTTP, construit dans AppContainer
    private val policy: SpeedTestPolicy = SpeedTestPolicy(),
    private val clock: AppClock = SystemAppClock(),    // monotone ; civile seulement pour l'étiquette
    private val scope: CoroutineScope,                 // Scope applicatif (AppContainer), pas de scope d'écran
) : SpeedTestEngine
```

Le transport OkHttp est **injecté**, pas instancié dans le moteur : en test, un `LibreSpeedClient` adossé à MockWebServer
prend la place ; en prod, l'instance unique vit dans `AppContainer` (dispatcher et pool partagés, fermeture en fin de vie
de l'application, pas par run).

### 4.3 Types nouveaux

```kotlin
/** Endpoint LibreSpeed validé : schéma, hôte, chemins. Jamais d'identifiant, jamais de fragment. */
data class SpeedTestEndpoint(
    val baseUrl: String,       // ex. https://speed.example.org/app/ (sans credentials, sans query)
    val hostLabel: String,     // host seul, affiché à l'utilisateur (« vers speed.example.org »)
)

object SpeedTestEndpointValidator {
    /** Pur JVM, sans Android. null = rejeté (voir §5 pour la liste exacte des rejets). */
    fun parse(raw: String): SpeedTestEndpoint?
}
```

`SpeedTestResult` conserve sa forme ; les champs `providerLabel`, `networkKey`, `wifiLink`, `capped` gardent leur sens.
L'étiquette « vers `<hôte>` » est portée par `providerLabel` (ex. `« LibreSpeed — vers speed.example.org »`), jamais par un
nouveau champ réseau. `networkKey` continue de désigner le Wi-Fi de départ — il ne stocke jamais l'IP publique.

## 5. Décision 2 — Source de l'endpoint : jamais en dur

### 5.1 Exigences

- Aucun `https://…` dans `data/speedtest/` — gardé par `Mission12BoundaryTest` (qui rejette tout `https://` dans le data
  layer). La liste §8 devra l'assouplir **de façon ciblée**, sans l'abolir.
- Aucun serveur public par défaut, aucun secret, aucune télémétrie.
- **Désactivé par défaut sans endpoint configuré** : `AppContainer` choisit `DisabledSpeedTestEngine` tant que la source
  n'a pas produit un `SpeedTestEndpoint` valide.
- HTTPS obligatoire, **sauf loopback** (`127.0.0.1`, `::1`, `localhost`) réservé aux tests MockWebServer.

### 5.2 Options comparées

| Option | Description | Pour | Contre | Verdict |
|---|---|---|---|---|
| **A. `BuildConfig` field** | `buildConfigField("String", "SPEEDTEST_BASE_URL", ...)` (debug/release), défaut `""` | simple, zéro écran, compile-time, testable (`BuildConfig` lu une fois dans `AppContainer`) | release sans valeur reste désactivé ; une URL rebuild-required ; l'IP du serveur n'apparaît pas à l'utilisateur dans le code ? — elle y apparaît, mais c'est le but : c'est **l'opérateur** qui configure, pas l'utilisateur | **Retenu comme source principale** |
| **B. DataStore / réglage explicite** | Clé `speedtest_base_url` + écran Réglages avec champ + validation à la saisie | l'utilisateur peut pointer son propre serveur LibreSpeed auto-hébergé ; pas de rebuild | surface d'abus (une URL arbitraire = un exfiltrable ? non : l'app n'envoie rien d'autre que du trafic de mesure), champ sensible (colle-client, faute), écran à construire, test UI, et l'IP distante devient une donnée persistée | **Retenu comme mécanisme A+B** (voir §5.3) |
| **C. URL codée en dur** | constante dans `data/` | zéro config | Interdit par §5.1 et par `Mission12BoundaryTest` ; couple le code à un tiers ; contredit la recherche Mission 12 §5 (aucun endpoint autorisé par défaut) | **Rejeté** |
| **D. URL distante (bannière, JSON, réseau)** | téléchargement d'une config | zéro rebuild | télemétrie/config distante = réseau avant consentement ; redevient un serveur par défaut ; rejeté par les contraintes | **Rejeté** |

### 5.3 Décision : A+B en couches, désactivé par défaut

1. **A (primaire).** `BuildConfig.SPEEDTEST_BASE_URL`, valeur par défaut `""` (vide = non configuré) en `debug` **et**
   `release`. Une valeur ne peut être fournie que par la machine de build de l'opérateur (variable Gradle/CI, jamais par
   défaut dans le dépôt). Le champ est lu **une fois** dans `AppContainer` ; son absence ou son invalidité → `Disabled`.
2. **B (secondaire, réglage utilisateur).** Une clé DataStore `speedtest_base_url` + une ligne dans Réglages (champ texte
   libre + bouton « Enregistrer » + affichage d'erreur de validation). **Priorité :** la valeur utilisateur valides écrase la
   valeur `BuildConfig` ; si les deux sont vides/invalides → `Disabled`. L'utilisateur qui auto-héberge un LibreSpeed
   (voie A de `MISSION_12_SPEEDTEST_RESEARCH.md` §5) n'a pas de dépendance build.
3. Dans les deux cas, la valeur brute traverse `SpeedTestEndpointValidator.parse()` ; **aucun code ne lit la chaîne brute**
   après ce point. Le résultat `SpeedTestEndpoint?` (ou `null`) choisit l'adaptateur.

> La recherche Mission 12 §5 conclut qu'**aucun endpoint n'est simultanément fiable, autorisé et contrôlable** en accès
> public. C'est pourquoi l'option « serveur public par défaut » (M-Lab, Ookla, Fast, Cloudflare) n'existe pas dans ce
> design : l'opérateur auto-héberge (voie A) ou configure explicitement. La voie B (M-Lab consentie, publication d'IP,
> plafonds relevés) **exige de relever les plafonds** et constitue un chantier séparé — voir « Décisions à arbitrer ».

### 5.4 Validation (liste fermée)

`SpeedTestEndpointValidator.parse(raw)` — fonction pure, JVM, testable sans Android :

1. **Chaîne non vide**, `trim()`, longueur ≤ 512 caractères.
2. **Schéma** : `https` uniquement. Rejet de `http` sauf si hôte loopback (`127.0.0.1`, `::1`, `localhost`) — cas tests uniquement.
3. **Hôte présent et non vide** ; rejet des chaînes contenant un espace, un caractère de contrôle ou `@` (identifiants
   embarqués, voir 5).
4. **Pas d'identifiants** : rejet si `userInfo` non nul, si query contient `token`/`key`/`password` (insensible à la casse),
   si la chaîne brute contient `password=`, `token=`, `apikey=`, `Authorization`, `Bearer`.
5. **Pas de fragment** (`#…`), pas de port implicite exotique (port 443 par défaut, ou port explicite HTTPS `1..65535`).
6. **Chemin** borné (≤ 256 caractères), sans `..`, sans caractère de contrôle. Normalisation : base URL = schéma + hôte +
   chemin, **sans slash final doublé**, sans query ni fragment.
7. `baseUrl` normalisée doit, reparsée, redonner le même hôte — défense en profondeur contre `https://evil.com@…`.

Tout rejet produit `null` → moteur désactivé + message générique (« adresse de mesure invalide »), **sans** journaliser la
chaîne fautive (elle pourrait contenir un secret saisi par erreur).

## 6. Décision 3 — OkHttp : comptage borné, timeouts, corps, parallélisme, redirections

OkHttp `4.12.0` est déjà la version du dépôt (`gradle/libs.versions.toml`). Sufficient pour un compteur borné, des timeouts
et des streams annulables. Aucun WebSocket n'est requis pour LibreSpeed (GET pour download, POST pour upload, GET pour
ping) — la mesure reste une série de requêtes dont les corps sont comptés et fermés.

### 6.1 Comptage borné (défense en profondeur)

Le compteur **ne fait jamais confiance à `Content-Length`** (mensonger ou absent) : il compte les octets réellement lus
(depuis un `ResponseBody.source()` ou un `RequestBody` coulé dans un sink de comptage).

- Lecture par fenêtres (buffer 64 KiB), `ensureActive()` à chaque itération, horloge monotone pour la fenêtre de vitesse.
- **Seuil dur** : dès que le total du sens courant atteint le plafond (`maxDownloadBytes` / `maxUploadBytes`), stop —
  fermeture immédiate du flux, et passage à la phase suivante. C'est ce qui rend le plafond indépendant du réseau : même si
  le serveur envoie plus, le flux est fermé au plafond.
- **Budget total du run** : le repository a déjà un `withTimeout(policy.maxDurationMillis)` ; le compteur ajoute un **compteur
  de run** (DL + UL ≤ Σ plafonds) qui, s'il est dépassé, ferme tout et échoue avec `Incomplete`. Backstop exigé par la
  limite §1 point 4.
- Les compteurs de progression (`downloadBytes`, `uploadBytes` de `SpeedTestState.Running`) **dérivent** de ce flux compté, pas
  d'une estimation.

### 6.2 Timeouts — brancher la policy (le point laissé ouvert en Mission 12)

`SpeedTestPolicy` déclare déjà : `connectTimeoutMillis = 8 s`, `readTimeoutMillis = 10 s`, `writeTimeoutMillis = 10 s`,
`callTimeoutMillis = 30 s` (`callTimeout ≥ maxDurationMillis` validé par `init`). En Mission 12, ils ne sont « branchés par
aucun code ». En Mission 13, ils sont branchés :

| Règle | Implémentation |
|---|---|
| `callTimeout` | `OkHttpClient.callTimeout = policy.callTimeoutMillis` (garde-fou de bout en bout, redondant avec `withTimeout(30 s)` — deux couches, la plus courte gagne) |
| `connect` | `client.connectTimeout = policy.connectTimeoutMillis` |
| `read` | `client.readTimeout = policy.readTimeoutMillis` |
| `write` | `client.writeTimeout = policy.writeTimeoutMillis` |

L'instance OkHttpClient est construite **une fois** dans `AppContainer` avec ces valeurs et partagée ; elle n'est pas
reconstruite par run. La validation `init` de `SpeedTestPolicy` reste inchangée (réutilisée par `LibreSpeedClient`).

### 6.3 Fermeture des corps

- Chaque `Response` est consommée dans un `use { }` (fermeture inconditionnelle, succès comme échec).
- `ResponseBody` de download : lecture bornée (§6.1) puis fermeture ; aucun `body.string()` (un `string()` charge tout en
  mémoire — déni de service contre notre propre téléphone).
- Upload : `RequestBody` streamed (`contentLength` annoncé = `chunkSize`, pas le total), annulable via `call.cancel()`.
- `cancel()` du moteur : `call.cancel()` pour chaque appel en vol + annulation des enfants de coroutine. `cancel()` est
  **idempotent** et **thread-safe** (enregistre les appels sous un verrou ou un ensemble concurrent ; `OkHttp` autorise
  `Call.cancel()` depuis n'importe quel thread).
- Le nettoyage suit le même ordre que l'acceptation dans le repository : le `finally` appelle `engine.cancel()`
  **sous le verrou du repository**, avant toute libération du slot (invariants §9).

### 6.4 Parallélisme borné

Le download ouvre **N flux en parallèle** (les serveurs LibreSpeed acceptent un nombre de threads client). Bornes :

- `N` plafonné à **4** en parallèle (borne produit, jamais une constante magique cachée : champ de `SpeedTestPolicy` ? voir
  « Décisions à arbitrer » D4). Au-delà de 4, le gain est marginal et le risque (batterie, CGNAT, rafales) grand.
- Queue OkHttp dédiée pour le moteur (dispatcher borné), **pas** le dispatcher du client routeur de `router-core` —
  un run ne doit jamais affamer le polling routeur (un nouveau `OkHttpClient` dédié, instance unique, pool limité).
- L'upload reste **séquentiel** (1 flux) : mieux pour les liens asymétriques et pour le comptage.

### 6.5 Redirections — jamais cross-hôte

`followRedirects` et `followSslRedirects` sont forcés à `false` sur le client dédié. Un `3xx` est traité comme une
réponse terminale : le moteur échoue avec `ServerUnavailable` (ou `Unexpected` selon le contexte), il ne suit jamais.
Justification : une redirection cross-hôte serait un canal de contournement du schéma et de l'hôte validé — l'orthographe
de §5 ne protège que l'URL de départ. Un HSTS / redirect depuis le serveur de l'opérateur doit être géré côté serveur
(final 200), pas côté client.

### 6.6 Liaison au réseau Wi-Fi (point ouvert Mission 12)

`MISSION_12_DEVICE_VALIDATION.md` §4 note que, le jour où le trafic Internet existera, les sockets devront être liées au
`Network` Wi-Fi (`bindProcessToNetwork` + `socketFactory`) — **non implémenté** en Mission 12. Décision Mission 13 :

- **Option retenue (conservative) :** le moteur OkHttp est construit avec un `socketFactory`/`dns`/`connectionPool`
  normal, **et le run est annulé** dès que le réseau quitte le Wi-Fi (invariants déjà en place). Un socket ouvert sur un
  `Network` périmé reçoit un `UnknownHostException`/`SocketException` → mappé en `InternetUnavailable`/`Cancelled`, jamais
  de bascule silencieuse.
- `bindProcessToNetwork` n'est **pas** retenu par défaut : il lie tout le processus (y compris le trafic routeur et le
  polling) et doit être relevé au niveau `AppContainer`, pas au moteur. Laisser le système choisir + annuler sur perte de
  Wi-Fi est plus sûr et suffit pour un run de 30 s. Revoir si des mesures démontrent une bascule mobile parasite.

## 7. Décision 4 — Plafonds et `capped` honnête

`SpeedTestPolicy` (inchangée) fixe :

```
maxDownloadBytes = 20 Mo, maxUploadBytes = 10 Mo, maxDurationMillis = 30 s
```

Mise en œuvre du `capped` honnête (`SpeedTestCalculator.isCapped` déjà existant) :

1. **Détection.** `downloadBytes ≥ maxDownloadBytes || uploadBytes ≥ maxUploadBytes` ⇒ `capped = true`. La détection est
   **en flux** (§6.1) : le moteur sait, au moment où il ferme, que c'est le plafond qui l'a arrêté — pas une estimation
   après coup.
2. **Conséquence produit.** Au-delà de ~16 Mbps DL cumulés (§3 de `MISSION_12_SPEEDTEST_RESEARCH.md`), le plafond coupe
   avant stabilisation BBR et **sous-estime systématiquement**. La UI affiche donc, quand `capped` est vrai :
   un préfixe « ≥ » devant le Mbps, et le texte déjà existant « Plafond atteint avant stabilisation : valeur minimale,
   pas débit maximal. » Aucun Mbps « précis » n'est jamais affirmé sur un lien rapide.
3. **Backstop indépendant du moteur.** Le repository compte (via les `Running` émises) et, si DL+UL dépasse les plafonds,
   il ferme le run et publie `Incomplete`. Ai-je dessus de tout : un bug du compteur moteur ne peut pas transformer le test
   en téléchargement illimité. La correction de la limite Mission 12 §7.5 est un critère d'acceptation.
4. **Budget données préservé.** 30 Mo / test ; aucun fichier n'est persisté hors DataStore de l'historique (20 entrées,
   30 jours) ; aucun payload ne part en télémétrie.
5. **Latence.** Médiane de 5 sondes (`SpeedTestCalculator.medianLatencyMillis`), jamais min ou max, jamais « ping ICMP »
   (c'est le temps de réponse HTTP, nommé comme tel dans l'UI : « latence HTTP »).

## 8. Flux (séquence d'un run, du premier tap à l'historique)

```text
1. L'utilisateur ouvre « Test de vitesse » (route protégée, sans nouvel onglet — inchangé).
2. L'écran lit SpeedTestUiState :
     - si aucun endpoint → failure = Disabled → bandeau + bouton désactivé + liens externes (§10);
     - sinon → bandeau fournisseur « vers <hôte> » + bouton actif ; consentement exigé avant le premier test.
3. Tap « Lancer » (ou « J'accepte et je lance ») → SpeedTestViewModel.start()
   → si !hasConsented → dialogue consentement ; sinon repository.start().
4. repository.start() :
     - lit monitor.currentStatus() (hors verrou) ; si !LocalWifi → Failed(NoWiFi), moteur jamais touché ;
     - sous verrou : si running → refus silencieux (single-flight) ; sinon running=true, generation++ ;
     - lance execute(myGeneration, identity.key) sur ioDispatcher, withTimeout(30 s) autour de engine.run().
5. execute() revalide monitor.currentStatus() ; identité différente → Cancelled (aucun appel réseau).
6. Engine.run() :
     a. LATENCY : 5 sondes HTTP → medianLatencyMillis.
     b. DOWNLOAD : N flux (≤4) parallèles, compteur borné à 20 Mo, 1 fenêtre/250 ms, ensureActive().
     c. UPLOAD : 1 flux, compteur borné à 10 Mo.
     d. FINALIZING : mbps() par horloge monotone, isCapped().
7. Résultat → publish(Done) (seulement si generation == courante) → ViewModel → écran (Mbps, capped « ≥ », liaison Wi-Fi).
8. L'historique est écrit par la UI/ViewModel (codec inchangé), 20 entrées/30 jours.
9. Annulation : cancel() sous verrou → engine.cancel() → call.cancel() → job.cancel() → slot libéré via invokeOnCompletion.
   Perte Wi-Fi : watchNetworkLoss() → cancel(NETWORK_LOST) / cancel(NETWORK_CHANGED), jamais de retry.
```

Erreurs de mapping (table fermée) :

| Exception / condition | `SpeedTestFailure` | Retentative auto |
|---|---|---|
| Aucun endpoint configuré | `Disabled` | non |
| Pas Wi-Fi / pas de réseau | `NoWiFi` / `InternetUnavailable` | non |
| DNS / connect / TLS / 5xx / redirect / 204 | `ServerUnavailable` | non |
| Dépassement budget coroutines | `Timeout` | non |
| Interruption mid-run, compteur incohérent | `Incomplete` | non |
| Tout autre `Throwable` capturé | `Unexpected` | non (mais slot libéré, retry manuel possible) |
| Utilisateur / perte Wi-Fi / changement d'identité | `Cancelled` (`CancelReason`) | non |

## 9. Décision 5 — Concurrence et annulation (système de bugs, pas règles)

Lecture de `systematic-debugging` Phase 1–4 : la concurrence n'est pas « à espérer correcte », elle est
**modélisée et testée**. Voici les invariants que Mission 13 conserve — et ce que le nouveau moteur doit respecter :

**Invariants du repository (déjà couverts par `SpeedTestRepositoryLifecycleTest`, §11) :**

1. Slot unique `running` + `invokeOnCompletion` comme libération. Pas de `Job.isActive` pour décider si un run est fini.
2. `generation` pour filtrer toute écriture périmée (`publish()` ne publie que si `myGeneration == generation.get()`).
3. `engine.cancel()` sous le verrou du repository, dans `cancel()` comme dans le `finally` — un ancien `cancel()` ne peut
   jamais atteindre un run successeur (« old cleanup never cancels the new run »).
4. `withTimeout(policy.maxDurationMillis)` : le timeout tue la coroutine ; le moteur doit répondre à l'annulation en
   moins d'un instant — son `finally` ferme les appels OkHttp **sans suspension** (ou, s'il doit suspendre, le slot reste
   détenu jusqu'à la fin du nettoyage, et le `start()` suivant est refusé — comportement déjà prouvé par le test
   « start is refused while cleanup is still suspended » avec `NonCancellable`).
5. `Cancelled` publié pour tous les motifs (utilisateur, perte Wi-Fi, changement d'identité) **sauf** quand un `Done` /
   `Failed` a déjà été publié — le cancel ne masque jamais un résultat.

**Invariants du moteur (nouveaux, à tenir dans `LibreSpeedEngine` / `LibreSpeedClient`) :**

6. Toute boucle de lecture/écriture fait `ensureActive()` **à chaque buffer** (pas seulement chaque fenêtre) — sinon un
   cancel entre deux fenêtres met jusqu'à 250 ms à réagir, et le slot semble bloqué.
7. `cancel()` ferme **tous** les appels en vol (un ensemble sous verrou, vidé à chaque fin d'appel). Un appel lancé après
   `cancel()` lève immédiatement (`call.cancel()` par avance).
8. Aucune valeur locale partagée inter-runs dans le moteur (pas de champ mutable de compteur hors run : chaque `run()`
   instancie ses compteurs locaux). Le seul état partagé est la liste des appels en vol, protégée par le verrou.
9. Les `SocketException`/`IOException` 《 réseau » ne sont **jamais** confondues avec une annulation : `CancellationException`
   remonte telle quelle ; les autres sont mappées selon le tableau §8. Le `finally` ne lève jamais (un `finally` qui lève
   masquerait l'erreur d'origine — Phase 1 : « ne pas corriger le symptôme »).

Si trois correctifs échouent sur un même symptôme de concurrence, la règle `systematic-debugging` Phase 4.5 s'applique :
**on questionne l'architecture** (p. ex. `binder call` dans `execute()` — risque consigné dans le rapport Mission 12 §4 :
la revalidation du réseau est un appel binder synchrone, donc un risque de suspension sur le thread IO ; ce qui suit est le
risque principal documenté, voir « Décisions à arbitrer » D6).

## 10. Décision 6 — Impact UI et ViewModel

Aucun état nouveau n'est inventé capable de mentir : l'UI distingue trois situations — **désactivé** (pas d'endpoint),
**ready** (endpoint configuré, consentement éventuellement requis), **running/done/failed** (module déjà existant).

| Élément | Désactivé (sans endpoint) | Configuré (endpoint valide) |
|---|---|---|
| Bandeau fournisseur | « Test de vitesse Internet indisponible dans cette version. Aucun fournisseur… Rien ne quitte le téléphone. » + liens externes (Mission 12, inchangé) | Nouveau bandeau : « Fournisseur : LibreSpeed — vers `<hôte>` (HTTPS) · Consommation max. 30 Mo/test » + liens externes conservés en substituts manuels |
| Bouton Lancer | `enabled = false` (`start_button_is_disabled_without_a_provider`, inchangé) | actif une fois consenti ; `testTag("speedtest_start_button")` conservé |
| Consentement | Texte actuel (aucun trafic) | Texte mis à jour : **serveur** = hôte validé, **données** = débit/latence/heure locaux, **consommation** = 30 Mo, **confidentialité** = l'IP publique du Wi-Fi est visible du serveur (sinon aucune mesure n'est possible) — pas de nouveau consentement implicite |
| Jauge / résultats | jauge « — », compteur 0,5 figé (maquette) | jauge branchaée sur `currentMbps` ; `progress = { fraction }` déjà en place ; `capped` → préfixe « ≥ » + phrase « valeur minimale » |
| « vers `<hôte>` » | absent | visible en permanence (en-tête de carte, dialogue de consentement, ligne « Fournisseur » du résultat) — l'utilisateur doit savoir **vers qui** partent 30 Mo |
| Historique | inchangé (aucune entrée) | inchangé ; `provider` stocke `hostLabel` (jamais d'IP publique, jamais de query) |

`SpeedTestUiState` conserve `failure`/`isDisabled` ; le ViewModel expose en plus `providerLabel: String?` et
`targetHost: String?` (null = désactivé), lus depuis le repository ou depuis `providerLabel` de l'adaptateur courant. Le
ViewModel ne connaît **toujours** pas l'URL, OkHttp ni le protocole : il ne décide que de l'état « désactivé ou non ».
Aucune surface nouvelle n'est exposée au repository : si le bouton est désactivé, c'est parce que l'adaptateur est
`Disabled`, pas parce que la UI a deviné.

Accessibilité : `liveRegion = Polite` sur la jauge (existant), `Assertive` sur le bandeau/erreur ; contraste Material 3
inchangé ; TalkBack doit lire « vers `<hôte>` » comme partie intégrante du nom du bouton.

## 11. Plan de fichiers

| Fichier | Action | Contenu |
|---|---|---|
| `app/build.gradle.kts` | modifier | ajouter `implementation(libs.okhttp)` (module `app`, pas `router-core`) ; `buildConfig = true` + `buildConfigField("String", "SPEEDTEST_BASE_URL", "\"$speedtestBaseUrl\"")` avec défaut `""` (propriété Gradle/CI injectée, jamais de valeur en dépôt) |
| `app/src/main/java/dev/wifiadmin/app/data/speedtest/SpeedTestEndpoint.kt` | **créer** | `SpeedTestEndpoint`, `SpeedTestEndpointValidator` (fonction pure, §5) |
| `app/src/main/java/dev/wifiadmin/app/data/speedtest/LibreSpeedClient.kt` | **créer** | HTTP bas niveau : `download(counter)`, `upload(counter)`, `ping()`, `cancel()`. Compteurs bornés, `use {}`, `followRedirects = false`, parallélisme borné (≤ 4), `ensureActive()` par buffer, timeouts depuis `SpeedTestPolicy` |
| `app/src/main/java/dev/wifiadmin/app/data/speedtest/LibreSpeedEngine.kt` | **créer** | Adaptateur `SpeedTestEngine` : orchestre latence → DL → UL, calcule via `SpeedTestCalculator`, mappe les erreurs (§8), `providerLabel = "LibreSpeed — vers <hôte>"` |
| `app/src/main/java/dev/wifiadmin/app/data/speedtest/SpeedTestPolicy.kt` | modifier | inchangé en valeurs ; ajout éventuel du parallélisme DL (voir D4) |
| `app/src/main/java/dev/wifiadmin/app/data/speedtest/SpeedTestEngine.kt` | modifier (léger) | conserver interface ; le repository gagne le **compteur backstop** (§7.3) |
| `app/src/main/java/dev/wifiadmin/app/WifiAdminApplication.kt` | modifier | `speedTestEngine` choisi selon `BuildConfig.SPEEDTEST_BASE_URL` + DataStore (`...`) ; construction unique de `OkHttpClient` dédié et de `LibreSpeedClient` |
| `app/src/main/java/dev/wifiadmin/app/data/speedtest/SpeedTestHistoryStore.kt` | modifier (léger) | clé `speedtest_base_url` (secondaire, §5.3-B) + lecture/écriture typée |
| `app/src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestPresentation.kt` | modifier | bandeau « vers `<hôte>` », textes de consentement mis à jour, mapping `failureMessage` inchangé |
| `app/src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestViewModel.kt` | modifier (léger) | `providerLabel` / `targetHost` exposés ; `isDisabled` inchangé |
| `app/src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestScreen.kt` | modifier | bandeau configurable, préfixe « ≥ » sur `capped`, jauge branchée |
| `gradle/libs.versions.toml` | modifier | `mockwebserver = { group = "com.squareup.okhttp3", name = "mockwebserver", version.ref = "okhttp" }` (test uniquement) |
| `app/build.gradle.kts` | modifier (test) | `testImplementation(libs.mockwebserver)` |
| `app/src/test/java/dev/wifiadmin/app/data/speedtest/SpeedTestEndpointValidatorTest.kt` | **créer** | validation pure (schéma, hôte, identifiants, loopback) |
| `app/src/test/java/dev/wifiadmin/app/data/speedtest/LibreSpeedEngineTest.kt` | **créer** | MockWebServer loopback, JVM, sans Android (§13) |
| `app/src/main/java/dev/wifiadmin/app/data/settings/*` (ou `ui/settings/`) | créer si D1 retenu | champ Réglages « adresse du serveur de mesure » + validation à la saisie |
| `docs/MISSION_13_*.md` | créer | rapports, captures conventionnelles (maquettes, jamais de mesures réelles) |

`router-core/` : **aucun fichier touché** — critère d'acceptation par `git diff --stat router-core/` vide (sauf BOM de version
déjà présente).

## 12. Plan de tests (seams pré-agres, règles `tdd`)

**Règle non négociable (`tdd`) : aucun test n'est écrit à un seam non confirmé.** Les seams ci-dessous sont les seams
proposés ; ils doivent être confirmés avant l'écriture du premier test.

| # | Seam | Ce qu'il attrape | Ce qu'il manque | Type |
|---|---|---|---|---|
| S1 | `SpeedTestEngine.run()` (JVM, MockWebServer loopback `127.0.0.1`) | plafonds honorés, compteurs exacts, `capped`, erreurs mappées, annulation, fermeture des corps | rien sur le TLS, le vrai serveur, le réseau cellulaire | JVM `test/` |
| S2 | `SpeedTestEndpointValidator.parse()` (pur, sans Android) | schéma, hôte, identifiants, loopback, longueur, unicode | la robustesse du réseau | JVM `test/` |
| S3 | `SpeedTestRepository` (inchangé, existant) + compteur backstop | single-flight, cancel, cleanup, identité, timeout | le comportement réseau | JVM `test/` |
| S4 | `SpeedTestContent` Compose avec `SpeedTestUiState` construit à la main | bandeau « vers », bouton désactivé, `≥ capped`, consentement | l'erreur réseau réelle | instrumenté `androidTest/` (loopback uniquement) |

**Anti-patterns proscrits (`tdd`) :**

- *Implementation-coupled* : pas de test du `LibreSpeedClient` au-delà de son comportement observable par
  `LibreSpeedEngine.run()` (reçus, compteurs, `capped`) — pas de `private fun` touché, pas de mock de `OkHttpClient` au
  niveau des appels internes.
- *Tautologique* : les valeurs attendues viennent d'un serveur **indépendant** (MockWebServer renvoie exactement N octets
  choisis par le test ; le test attend `mbps = N*8 / dur` **littéral**, pas recalculé par `SpeedTestCalculator`).
- *Horizontal slicing* : pas de « tous les tests d'abord ». Chaque tranche (voir §12-bis) est une boucle **un test →
  une implémentation minimale → vert**, avant la suivante.

**À quel endroit JVM sans Android ?** `LibreSpeedEngine` et `LibreSpeedClient` importent uniquement `okhttp3.*`,
`kotlinx.coroutines.*`, `dev.wifiadmin.app.data.AppClock` (interface) et les types du package — **jamais**
`android.net.*`, `android.content.Context`, ni `DataStore`. De ce fait, ils tournent sur la JVM (`test/`), sans Robolectric,
sans instrumenté, avec un `TestAppClock` et un `StandardTestDispatcher` (schéma déjà prouvé par
`SpeedTestRepositoryLifecycleTest`). Seuls `AndroidWifiLinkInfoProvider` et l'écran Compose restent Android.

**MockWebServer (serveur HTTP local JVM).** Sert sur `127.0.0.1` (loopback — seul cas où le schéma `http` est autorisé,
§5.4). L'URL du test est `http://127.0.0.1:<port>` — c'est la **seule** URL qu'on accepte en `http`, et elle est injectée
par le test, jamais lue depuis `BuildConfig`. Aucun test ne contacte Internet : la CI peut tourner **offline** pour S1–S3,
et l'étape S4 (instrumenté) ne sert que des écrans en `127.0.0.1`.

## 12-bis. Tranches verticales rouge → vert

Chaque tranche est une boucle complète (`tdd` : *one seam, one test, one minimal implementation*). Aucune tranche ne commence
avant que la précédente soit verte — et verte **sans** ajouter de fonctionnalité spéculative.

| Tranche | Test (rouge) | Implémentation minimale (vert) |
|---|---|---|
| **T1 — endpoint absent** | `SpeedTestEndpointValidator.parse("") == null`, `parse("http://...") == null` (hors loopback) | `SpeedTestEndpoint` + `Validator` pur |
| **T2 — endpoint valide** | `parse("https://speed.example.org/app/") → hostLabel = "speed.example.org"` ; rejet `user:pass@…`, `token=…`, `http://127.0.0.1:ok` accepté | Normalisation + rejets §5.4 |
| **T3 — moteur, loopback** | MockWebServer sert 5 Mo conséquents, `LibreSpeedEngine.run()` retourne `downloadBytes = 5_000_000`, `downloadMbps` = littéral indépendant | `LibreSpeedClient` + `LibreSpeedEngine` (DL seul, séquentiel d'abord, puis parallélisme) |
| **T4 — plafonds et `capped`** | Le serveur sert 100 Mo, `downloadBytes == maxDownloadBytes` (20 Mo) exactement, `capped == true` | compteur borné en flux §6.1, `SpeedTestCalculator.isCapped` branché |
| **T5 — upload** | Miroir de T3/T4 pour l'upload (`maxUploadBytes = 10 Mo`) | `upload()` streamed |
| **T6 — latence + timeouts** | serveurlent : médiane 5 sondes = littéral connu ; `SocketTimeoutException` → `Timeout`/`ServerUnavailable` | `ping()` + branchement des timeouts §6.2 |
| **T7 — annulation** | `cancel()` pendant DL : `engine` ferme les appels, slot libéré, `Cancelled` publié, aucun run successeur atteint | `call.cancel()` ensemble + `ensureActive()` par buffer |
| **T8 — garde-fous repository** | bug simulé du moteur (compteur fou) → backstop (§7.3) tue le run | compteur de run dans le repository |
| **T9 — UI horlogement** | Compose avec `SpeedTestUiState` configuré : « vers `<hôte>` », « ≥ », bouton actif ; sans endpoint : bouton désactivé (déjà couvert, inchangé) | bandeau + ViewModel `providerLabel`/`targetHost` |
| **T10 — réception** | `AppContainer` lit `BuildConfig` vide → `Disabled` (test d'usine), valeur valide → `LibreSpeed` | choix de l'adaptateur dans `AppContainer` |

**Critères d'acceptation de la PR (vers `develop` uniquement, aucune fusion) :**

- [ ] `git diff --stat router-core/` — vide.
- [ ] `Mission12BoundaryTest` vert après son assouplissement ciblé (voir D5) ; aucun `https://` dans `data/speedtest/`
      hors test fixtures.
- [ ] Suite JVM complète verte (612 tests Mission 12 + nouveaux), 0 échec, **CI offline** (S1–S3).
- [ ] Aucun appel Internet : les tests ne servent que `127.0.0.1` (MockWebServer).
- [ ] Plafonds 20/10/30s tenus même si le serveur ment ou envoie plus (T4).
- [ ] Désactivé par défaut : APK sans `SPEEDTEST_BASE_URL` monté → bouton désactivé (`start_button_is_disabled_without_a_provider`).
- [ ] « vers `<hôte>` » visible, consentement explicite avant le premier test.
- [ ] Pas de secret, pas de télémétrie, pas de `http` hors loopback ; aucun `followRedirects` cross-hôte.

## 13. Invariants (contrat complet du module)

1. **Un test = un utilisateur, un Wi-Fi.** Aucun démarrage automatique, aucun polling, aucun service, aucun WorkManager,
   aucun retry. Rejet silencieux d'un `start()` pendant un run (single-flight).
2. **Un seul réseau sortant.** `LibreSpeedEngine` est le seul endroit qui peut ouvrir une socket Internet. Le repository,
   le ViewModel, l'écran, l'historique et la liaison sont offline-by-construction.
3. **HTTPS ou désactivé.** Aucun `http` hors loopback, aucune redirection cross-hôte, aucun identifiant dans l'URL.
4. **Plafonds durs et comptés en flux**, pas en `Content-Length`. 20 Mo / 10 Mo / 30 s, budget total du run vérifié des deux
   côtés (moteur + backstop repository).
5. **`capped` = plancher, pas débit maximal.** Préfixe « ≥ », texte affiché, jamais de Mbps précis sur un lien plafonné.
6. **Deux débits jamais confondus.** `WifiLinkInfo` (téléphone ↔ routeur) et `SpeedTestResult` (téléphone ↔ Internet)
   restent des champs séparés, étiquetés séparément, dont l'un vaut `null` quand il est inconnu (jamais `0`).
7. **Annulation totale et ordonnée.** `cancel()` ferme chaque appel, `ensureActive()` partout, slot libéré par
   `invokeOnCompletion`, aucun `cancel()` périmé ne touche un run nouveau, aucun `Done`/`Failed` n'est écrasé.
8. **`router-core` intact.** Aucun `cmd`, aucune session, aucun token, aucun `Zlt*` dans le moteur. Le polling routeur n'est
   jamais déclenché par un test.
9. **Aucune donnée personnelle hors du téléphone.** DataStore de l'historique (20/30 jours) ; jamais d'IP publique, MAC,
   SSID, session, mot de passe. Aucune télémétrie, aucun identifiant stable, aucune publicité.
10. **Désactivé par défaut, honnêtement.** Sans endpoint valide, l'écran dit « indisponible », le bouton est désactivé, et
    aucun octet ne quitte le téléphone. Liens externes = navigateurs, jamais l'app.

## 14. Décisions à arbitrer (utilisateur)

### D1 — B : exposer le réglage utilisateur dans Réglages ? (primaire, bloquant pour le plan de fichiers)
- **Option S (simple).** Seule `BuildConfig` — zéro écran. L'utilisateur ne peut pas pointer son propre serveur sans rebuild.
  Cohérent avec l'opérateur-hosted (voie A).
- **Option S+B (recommandée).** `BuildConfig` + champ Réglages avec validation à la saisie. L'utilisateur peut auto-héberger
  LibreSpeed et le configurer lui-même. Surface : un écran Compose maintenable (schéma existant `SettingsStore`).
- Sans arbitrage, Mission 13 livre **S** (testable, aucun écran) ; la couche B est alors une PR séparée.

### D2 — Périmètre fonctionnel : parallélisme et warm-up
- **P1 (recommandé).** `N = 4` DL parallèles dès T3, warm-up 2 s écarté, fenêtre 250 ms (conforme à l'algorithme Mission 12
  §4). Le comptage reste global, les fenêtres sommées.
- **P2.** Séquentiel d'abord (N = 1), parallélisme seulement après stabilisation des tests et mesure réelle (plus simple, mais
  sous-estime les liens rapides).
- **Arbitrage produit.** N=4 dès le départ (P1) ou séquentiel (P2) ? Le parallélisme multiplie les appels en vol à fermer
  pendant `cancel()` — il appartient aux tests T7, pas à une préférence.

### D3 — Transport : HTTP (LibreSpeed) ou WebSocket (NDT7) ?
- **HTTP retenu** : correspond à l'interface et au protocole LibreSpeed existant dans les serveurs auto-hébergés, pénalité
  moindre, pas de client WS à inventer. NDT7/M-Lab exigerait un nouveau protocole + une refonte des compteurs + la question
  de la publication d'IP (voie B, plafonds relevés).
- L'alternative NDT7/M-Lab impose, par ailleurs, des **plafonds ≥ 100–250 Mo** sur liens rapides (`MISSION_12_SPEEDTEST_RESEARCH.md`
  §3 et §5). Le choix HTTP est donc indissociable du maintien de 20/10/30s.

### D4 — Faut-il afficher une consommation mesurée (octets exacts) en permanence ?
- Oui, déjà disponible via `SpeedTestResult` ; le bandeau §10 l'affiche (« max. 30 Mo »). Reste à décider si l'écran
  affiche « Consommé ce test : X Mo » en plus du Mbps (information utile en prépayé).

### D5 — Assouplir `Mission12BoundaryTest` ?
Le test actuel rejette **tout** `https://` dans `data/speedtest/`. Le nouveau moteur n'en a pas — la config reste externe —
mais il contient des littéraux de chemins (`/download`, `/upload`, `/ping`) et des constantes de buffer. Proposition
(recommandée) : conserver la règle « aucun endpoint hôte en dur », mais autoriser des littéraux de **chemins relatifs**
sans hôte. Sans cet assouplissement ciblé, le test bloque tout implémentation valide.

### D6 — Risque documenté : appel binder synchrone dans `execute()`
`execute()` revalide `monitor.currentStatus()` (appel binder) sur le thread `ioDispatcher`. Si ce binder suspend, le slot
reste détenu et les `start()` suivants sont refusés jusqu'à la résolution. Mission 12 a déjà signalé ce risque. Options :
laquelle accepter (laisser tel quel, déplacer la revalidation, ou timeur binder) ?

### D7 — Permission : `INTERNET` suffisante, ou `ACCESS_LOCAL_NETWORK` pour le ping LAN ?
- `INTERNET` existe déjà (trafic routeur). L'IP publique du Wi-Fi est visible par le serveur de mesure — c'est sa nature.
  Aucune permission nouvelle n'est demandée. Confirmer que cette hypothèse reste valide pour le serveur configuré.

### D8 — Faut-il élever les plafonds si l'utilisateur le demande ?
Non, sans refte du produit : toute valeur > 20/10/30s contredit le contrat de consommation en prépayé. Si un lien rapide exige
une mesure stabilisée, la solution est de le dire à l'utilisateur (« ≥ » + liens externes), pas de relever les plafonds en
silence.

## 15. Risques et hors-périmètre

| Risque | Mitigation |
|---|---|
| Le serveur configuré est loin (chemin international) : latence élevée, mesure « pas dernière mille » | S'afficher : l'écran dit « vers `<hôte>` » ; comparaison avec la liaison Wi-Fi à l'écran ; liens externes en substituts |
| `204` hors capacité, CGNAT, PEP, partage de pare-feu | Mapping `ServerUnavailable`/`InternetUnavailable` honnête ; pas de retry auto ; consigne « ressayez » |
| Un lien rapide est plafonné avant stabilisation (sous-estimation) | `capped` + « ≥ » + phrase « valeur minimale » ; aucune revendication de débit maximal |
| Batterie / forfait mobile limité | 30 Mo/test max, 1 test à la fois, bouton Annuler visible, aucun fond |
| Fuite vers mobile | Invariants §9 : annulation sur perte Wi-Fi, switch sur identité différente, jamais de bascule |
| URL malveillante saisie dans le réglage | Validation §5.4 (HTTPS, pas d'identifiants, pas de fragment, pas de redirection cross-hôte) |
| Persistance de l'URL saisie | Clé DataStore distincte, jamais d'IP publique, purge au logout (même politique que `SpeedTestPreferencesStore`) — à conforter dans la tranche B |
| Télémétrie déguisée | Aucune, par construction : aucun appel avant consentement, aucun identifiant stable, aucun tiers contacté |

**Hors-périmètre (explicitement non traité en Mission 13) :**

- Voie B M-Lab (publication d'IP, plafonds relevés, consentement RGPD spécifique) — chantier séparé.
- NDT7 / WebSocket / Ookla / Fast.com / Cloudflare — non revus (recherche Mission 12 §2).
- Mesure ICMP / `TCPInfo` — nommée latence HTTP, pas ping.
- Écran de suppression dédié de l'historique (Mission 12 §7 liste l'absent : purge 30 jours + effet logout, inchangés).
- Jauge : le 0,5 figé des maquettes est branché dès T9 pour les valeurs moteur ; les chiffres fictifs restent marqués
  « Moteur simulé (fictif) » partout.

## 16. Récapitulatif du plan (ordre d'exécution)

1. Confirmation utilisateur des seams S1–S4 (obligatoire avant T1 — règle `tdd`).
2. T1 → T2 (endpoint + validation, pur JVM).
3. T3 → T6 (moteur OkHttp contre MockWebServer loopback ; timeouts branchés).
4. T7 → T8 (annulation + backstop repository).
5. T9 (UI « vers », `≥`, bouton actif). 6. T10 (choix de l'adaptateur `AppContainer`).
7. PR vers `develop` — captures conventionnelles (maquettes, jamais de mesures réelles) — aucune fusion.

---

*Document d'architecture : Mission 13 ne modifie aucun code — il produit `docs/MISSION_13_ARCHITECTURE.md` et, à
l'issue de sa confirmation, les tranches T1–T10 en boucle rouge → vert, une tranche à la fois, sans aucun test réel vers
Internet, sans toucher à `router-core`.*

## Additif de revue corrective (08/11/2026)

État implémenté (voir `docs/MISSION_13_CORRECTION_REPORT.md`) :

- **§6.6 fermé** : routage Wi-Fi physique par run (`WifiRoute` : `socketFactory` + `Dns` via
  `Network.getAllByName`), sans `bindProcessToNetwork` ; `null` → `NoWiFi` avant tout octet.
  Conception `docs/MISSION_13_WIFI_ROUTING.md`, tests `SpeedTestRoutingTest`.
- **Métrologie** : download en réponse longue (`ckSize` dimensionné au plafond) + warm-up
  256 Kio exclu de la fenêtre mais compté au plafond/à la consommation ; upload PRNG
  déterministe incompressible compté en octets écrits ; jitter `moyenne |Δ|` sur les
  5 sondes ; latence sous charge « indisponible ». Conception `docs/MISSION_13_METROLOGY.md`.
- **Réutilisation** : client frais par run + drapeau du moteur, `SpeedTestReuseTest` 3/3.
- **Backstop** : progression + résultat terminal ; portée documentée à deux couches.
