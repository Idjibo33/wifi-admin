# Mission 13 — Tests, sécurité et revue indépendante

> Date : 08/11/2026 · **Mode guidance uniquement** (skill `security-audit`) :
> aucun workflow complet lancé, aucune exécution de code cible, aucun appel réseau,
> aucun PoC, aucun secret. Revue statique des sources uniquement.
> Skills appliquées : `security-audit` (guidance), `code-review` (axes
> Standards / Spec séparés, non fusionnés), `systematic-debugging`
> (cause racine avant correction — ce document propose des correctifs à diagnostiquer,
> il ne corrige rien).
> Périmètre lu : `docs/MISSION_13_LIBRESPEED_RESEARCH.md`,
> `docs/MISSION_13_ARCHITECTURE.md` (**présent** — les décisions D1–D8 y sont
> encore « à arbitrer »), `app/src/main/java/dev/wifiadmin/app/data/speedtest/*.kt`
> (6 fichiers), `app/src/test/.../*.kt` (4 fichiers + `Mission12BoundaryTest`),
> `app/src/main/AndroidManifest.xml`, `app/build.gradle.kts`,
> `gradle/libs.versions.toml`, `docs/MISSION_12_*.md`, `docs/ANDROID_ARCHITECTURE.md`.
> Aucun fichier de production modifié par cette revue.

**Constat transverse (cause racine du périmètre de la revue).**
Dans ce checkout, le moteur réel n'existe pas encore : on trouve seulement
`DisabledSpeedTestEngine` + `FakeSpeedTestEngine` + `SpeedTestRepository`
(`data/speedtest/SpeedTestEngine.kt`), la policy et le calcul. Il n'y a ni
`SpeedTestEndpoint.kt`, ni `LibreSpeedEngine.kt`, ni `SpeedTestEndpointValidator`,
ni `LibreSpeedClient`, ni import OkHttp dans le module `app`
(`app/build.gradle.kts` : aucune `implementation(libs.okhttp)`), ni `MockWebServer`
dans le catalogue de versions. Le fournisseur par défaut reste
`DisabledSpeedTestEngine()` (`WifiAdminApplication.kt:127`), donc **zéro octet ne
quitte le téléphone aujourd'hui**. Toutes les exigences LibreSpeed ci-dessous sont
donc relues comme des *exigences à satisfaire avant le premier trafic réel*
(confirmed pour les écarts déjà présents dans le code hérité de Mission 12,
needs_validation pour ce qui dépend encore d'un fait externe). Les numéros de
tranches T1–T10 renvoient à `MISSION_13_ARCHITECTURE.md` §12-bis.

---

## 1. Revue sécurité (guidance)

### 1.1 Contrôles exigés à la mission, état observé

| Contrôle | État observé | Verdict |
|---|---|---|
| **HTTPS obligatoire** | Aucun moteur réseau n'existe ; le manifeste pose `android:usesCleartextTraffic="true"` (globale, `AndroidManifest.xml:27`) sans `networkSecurityConfig`. L'exigence vit dans ARCH §5.4 (validation + loopback tests), non encore implémentée, et reste **application-layer uniquement** si elle ne repose que sur le validator. | **Écart confirmé (SEC-02)** + NV |
| **Validation endpoint (schéma, hôte, pas d'identifiants, pas de fragment)** | Aucun validator présent. ARCH §5.4 liste fermée : `https` only, loopback `http` réservé aux tests, rejet `userInfo`/`@`, query `token/key/password`, fragment, longueur ≤512, chemin ≤256, pas de `..`, re-parse identité (§5.4.7). Voir critiques §1.2. | À implémenter (T1–T2) |
| **Absence de télémétrie / getIP** | Aujourd'hui aucune appel (moteur désactivé). RESEARCH Rec §2/§3 et ARCH §7.2/Rec §5 interdisent `results/telemetry.php` et le non-appel de `getIP.php`. Aucun test frontière ne garde ces interdictions (SEC-03). | Conforme aujourd'hui, garde-fou manquant |
| **Plafonds durs 20 Mo / 10 Mo / 30 s + backstop indépendant** | Caps exacts (`SpeedTestPolicy.kt:15-17`, init `:24-32`), `withTimeout(30 s)` repository (`SpeedTestEngine.kt:268-270`). Backstop octets **absent** (reconnu en Mission 12 report §7, ARCH §7.3) : repository ne contrôle pas les compteurs portés par `Running`. | **Écart confirmé (SEC-01)** |
| **Annulation (`Call.cancel` + `ensureActive`)** | Repository : `cancel()` sous verrou + `engine.cancel()` (`SpeedTestEngine.kt:303-313`), `finally` génération-gardé (`:282-291`) — solide. `Call.cancel` / `ensureActive` par buffer ne peuvent être vérifiés : aucun `Call` n'existe. Le `Fake` utilise un drapeau `cancelled` privé (`:137-138`) en plus de `currentCoroutineContext().ensureActive()` (`:103-104,114`). | Contrat futur (T7) ; voir STD |
| **Repli cellulaire interdit** | Repository refuse tout démarrage hors `LocalNetworkStatus.LocalWifi` (`SpeedTestEngine.kt:204-207,260-267`) ; `watchNetworkLoss` annule sans retry (`:316-333`) ; `WifiLinkInfoProvider.kt:91` retourne `null` si transport cellulaire présent (même cumulé avec Wi-Fi). Tests dédiés (`SpeedTestEngineTest.kt:101-109` « no mobile fallback ever »). | **Conforme** |
| **Logs sans IP / SSID / MAC** | Zéro instruction de log dans tout `app` (grep `Log.`, `HttpLoggingInterceptor`, `println` : 0 hit) — conforme à `ANDROID_ARCHITECTURE.md` §5 (« Aucune instruction de log dans le module `app` »). L'historique DataStore ne stocke que débits/octets/heure + `provider`/`network` (`SpeedTestHistoryStore.kt:28-45`, `networkKey` = clé interne réseau local, jamais l'IP publique). `allowBackup=false` (`AndroidManifest.xml:21`). | **Conforme** (à préserver) |
| **XSS** | Aucune `WebView` (`grep` `ui/speedtest` : 0 hit) ; les liens externes sont du texte Compose + intents navigateur (`SpeedTestPresentation.kt:61-65`, `SpeedTestScreen.kt:426` `httpsExternalLinks()`) ; champs Compose typés, pas de HTML brut. `WifiAdminApplication` reste sur `Disabled` par défaut. | **Conforme (aucune surface XSS)** |
| **Dépendances OkHttp / MockWebServer** | OkHttp `4.12.0` (`gradle/libs.versions.toml:5`), consommé uniquement par `router-core` (`router-core/build.gradle.kts:6`) — le module `app` ne doit pas compter sur le transitif (ARCH §2.2 correcte). MockWebServer **absent** du catalogue : aucun seam S1/S2 exécutable aujourd'hui. | **Écart confirmé (NV-02)** |

### 1.2 Critiques de la liste fermée de validation (ARCH §5.4) — avant T2

La liste fermée est globalement bonne (schéma + hôte + re-parse identité). Quatre
points à durcir dans l'implémentation (toute défaillance ici ne serait décelée que
par un fuzz d'URL, pas par les tranches T1–T2) :

1. **Rejeter toute query, pas seulement `token/key/password`.** §5.4.4 autorise
   implicitement `?ckSize=…` ou `?foo=…` dans une base URL. Or la base normalisée
   (schéma+hôte+chemin) n'a jamais besoin de query : tout `?` doit être rejet.
   Sinon, une base `https://hôte/x?r=…` peut changer le sens des paramètres livrés
   au backend par le client (cf. §2.1 RESEARCH : `r`, `ckSize` ajoutés par le client).
2. **Codifier l'exception loopback hors production.** « `http` accepté si loopback »
   (§5.4.2) dans un validator livré en APK ferait de `http://127.0.0.1:port` une
   base légitime, donc un bypass `https`-only que le fuzz de saisie utilisateur
   révélerait immédiatement. Le validator doit prendre un paramètre
   `allowHttpLoopback: Boolean = false` ; `false` en production (y compris
   `BuildConfig`), `true` uniquement dans le câblage des tests MockWebServer.
3. **Port et IDN.** §5.4.5 « port explicite HTTPS `1..65535` » autorise `https://h:53`
   (évasion DNS) — restreindre aux ports raisonnables (ex. `443` ainsi que tout port
   explicite mais avec test dédié), et couvrir `user@` obfusqué par
   `https://evil.com@hôte` via le re-parse identité déjà prévu (§5.4.7), plus des cas
   Unicode/punycode et `@` brut en chemin.
4. **Doublon `/` final et casse.** ARCH promet « sans slash final doublé » — le code
   de construction des requêtes doit normaliser la concaténation base+chemin
   (`garbage.php`/`empty.php`), sinon `//garbage.php` peut selon le serveur devenir
   un chemin canoniquement différent du chemin validé (bypass de la liste de chemins).

### 1.3 Findings confirmés (sévérité)

> Méthode (security-audit priorité/certitude) : chaque finding nomme le contrôle
> explicite, le code source, la limite franchie et l'effet concret. L'exposition
> actuelle est nulle tant que `DisabledSpeedTestEngine` est le moteur de production
> (`WifiAdminApplication.kt:127`) ; les sévérités ci-dessous valent pour le jour où
> un moteur réseau est branché sans les correctifs indiqués.

| ID | Sévérité | Finding (fichier:ligne, règle, effet) |
|---|---|---|
| **SEC-01** | **Medium** | **Pas de backstop indépendant des plafonds octets.** Règle : ARCH §13.4, Mission 12 report §7. Code : `SpeedTestEngine.kt:268-270` (`withTimeout` seul), `publish()` `:244-248` ne contrôle aucun compteur, `Running` porte bien `downloadBytes/uploadBytes` (`SpeedTestResult.kt:32-33`) mais ils ne sont jamais testés par le repository. Effet : un moteur défectueux (comptage sur `Content-Length` mensonger, `ensureActive` oublié, curseur partagé inter-run) peut transférer sans borne sur un forfait prépayé — le contrôle produit est « affaibli », non « fully defeated ». Correctif : compter dans le wrapper `onProgress` du repository, seuils = plafond + marges d'un buffer (64 KiB, cf. §6.1 ARCH), kill → `Incomplete` + `engine.cancel()`, test T8 (moteur au compteur fou). Cause racine : une seule couche d'enforcement (défense en profondeur absente), pas un bug de compteur. |
| **SEC-02** | **Medium** | **Cleartext global au niveau plateforme.** `AndroidManifest.xml:27` `usesCleartextTraffic="true"`, sans `networkSecurityConfig`. Nécessaire pour le routeur LAN (`ANDROID_ARCHITECTURE.md:270`, HTTP clair) — donc pas un défaut, mais il rétrograde « HTTPS obligatoire » au rang de contrôle purement applicatif (validator) pour tout hôte de mesure : une seule régression du validator (ou une redirection suivie à tort) expose le trafic de mesure en clair. Compensations requises avant trafic réel : assert de schéma par requête dans `LibreSpeedClient` (`https`, sauf loopback explicite de test), `followRedirects=false` (ARCH §6.5), T-tests 3xx. Pas de `domain-config` possible par hôte de mesure saisi par l'utilisateur — risque résiduel assumé et documenté. |
| **SEC-03** | **Low** | **Tests frontières incomplets face au protocole LibreSpeed.** `Mission12BoundaryTest.kt:29-34` interdit seulement `https://` dans `data/` ; `:55-62` refuse `sessionId/password/"ssid"` (rien sur `mac`, `ip`, `token`, `getIP`, `telemetry`). Un futur `LibreSpeedEngine` peut appeler `getIP.php`/`telemetry.php` sans faire rouge de aucun test. Correctif : allowlist de chemins relatifs (`garbage.php`, `empty.php` uniquement) + littéraux interdits (`getIP`, `telemetry`, `isp`, `X-Forwarded-For`) en fixtures de test, sans introduire d'URL d'hôte (compatibilité D5). |
| **SEC-04** | **Low** | **Jauge qui peut mentir par défaut.** `SpeedTestCalculator.kt:38-43` : commentaire « next rounded step above the value », mais `firstOrNull { it >= value } ?: steps.last()` renvoie 1000 pour une valeur >1000 (donc *en dessous*), testée comme telle (`SpeedTestCalculatorTest.kt:52-54`). Règle : Mission 12 report §3 « palier rond au-dessus ». Effet borné : sous le plafond 20 Mo/30 s, une vitesse >1000 Mbps exigerait ~>1000 Mbps de goodput avec `elapsed` <1 s, déjà gardée par `isTooShortToTrust` sous 1000 ms — exposition pratique faible, mais le code contredit sa spec. Étendre la liste (2 500/5 000) ou documenter le clamp. |
| **SEC-05** | **Informational** | **Timeouts déclarés jamais branchés.** `SpeedTestPolicy.kt:19-22` (connect 8 s / read 10 s / write 10 s / call 30 s) ; `SpeedTestEngine.kt` n'importe aucun OkHttp ; Mission 12 report §4 : « ne sont branchés par aucun code ». Pas de vulnérabilité aujourd'hui ; bloquant avant tout trafic (ARCH §6.2, T6). |

### 1.4 Points needs_validation (sans sévérité, cause bloquante + plan de validation sans réseau)

| ID | Fait manquant | Plan sûr |
|---|---|---|
| **NV-01** | OkHttp 4.12.0 (`libs.versions.toml:5`) : actualité CVE/volatilité au fil de l'eau, sans appel réseau. | Contrôle offline au bump contre lockfile/coverage locale ; aucun appel distant. |
| **NV-02** | MockWebServer absent du catalogue/tests : S1–S3 non exécutables. | Ajout `testImplementation` épinglé à la même `4.12.0`, scope `testImplementation` uniquement (vérifier qu'il ne fuite pas dans l'APK release) ; CI offline loopback (`127.0.0.1` uniquement, cf. §4). |
| **NV-03** | Exactitude du plafond à N flux parallèles (ARCH §6.4 N≤4) : course lecture→incrément permettant un dépassement de N×buffer. | T4 avec serveur servant 100 Mo en 4 flux : `downloadBytes == maxDownloadBytes` à l'octet près, avec test de compteur atomique `compareAndSet`/borne avant ajout. |
| **NV-04** | Migration cellulaire d'un socket déjà ouvert (ARCH §6.6, `bindProcessToNetwork` écarté) : aucun transfert cilien ne migrera silencieusement sans preuve du `watchNetworkLoss`. | Test appareil : couper le Wi-Fi mid-run, vérifier `Cancelled` avant ouverture d'un nouveau flux et zéro octet cellulaire compté (QEMU/fixtures offline ; aucun serveur réel). |
| **NV-05** | Exécution locale future (T4/T8) : les exécutions cibles relèvent des contrôles d'exécution sandbox (loopback isolé, env vide, limites CPU/mémoire/temps) — rappel de la règle « Universal execution safety » du skill. | Toute reproduction locale se limite au loopback MockWebServer, fixture dummy, sans dépendance externe ; soft sandbox descrite ci-dessus en tête de plan si applicable. |
| **NV-06** | LGPL-3.0 réimplémentation vs embarquement (RESEARCH §1 : théorie « protocole ≠ copie »). | Faire valider par un juriste avant livraison ; interdiction de joindre `speedtest*.js`/`backend/*.php` à l'APK. |
| **NV-07** | DataStore B : persistance de `speedtest_base_url` et purge au logout (ARCH §15 supposée). | Stocker hôte+chemin uniquement (pas de query), inclure la clé à la purge existante de `SpeedTestHistoryStore` (même extension `allowBackup=false`) ; tester le cycle logout → clé absente. |
| **NV-08** | Bandeau « vers `<hôte>` » + mention d'exposition d'IP (ARCH §10) : risque d'affichage d'un `hostLabel` partiellement issu d'une URL rejetée ou de credentials (`user:pass@hôte`). | T9 : hostLabel reconstruit depuis l'endpoint **validé et re-parsé** (identité §5.4.7), jamais depuis la chaîne brute ; test d'injection `user@`/`token=` → message générique sans journalisation. |
| **NV-09** | `getIP.php` « optionnel » (ARCH §7.2, RESEARCH §7.2) : son appel exposerait l'IP publique et introduirait l'usurpation XFF obs. | Décision gelée : **ne jamais appeler `getIP.php`/`getIP_util.php`** (confirmer la recommandation RESEARCH « non appelé ») ; l'interdiction est gardée par SEC-03. Toute réactivation exigerait une revue de confidentialité propre (y c. RGPD/AUP). |

---

## 2. Revue code — axe Standards

> Notes de méthode (skill `code-review`) : les écarts aux standards **documentés**
> du dépôt sont des violations dures ; les smells de Fowler sont des jugements
> étiquetés (« possible … ») et ne doivent jamais être promues en violations.
> Aucun smell n'est signalé si l'outillage le contraint déjà.

**Standards documentés respectés (forces).** Séparation routeur/Internet
(`Mission12BoundaryTest` vert, ARCH §8 ; `data/speedtest/` n'importe aucun
`ZltRouterClient`/`RouterTransport`/`/cgi-bin`/`cmd 100`), zéro log
(`ANDROID_ARCHITECTURE.md` §5), pas de permission de localisation
(`Mission12BoundaryTest.kt:49-53`, manifeste sans `ACCESS_FINE_LOCATION`/`ACCESS_WIFI_STATE`),
pas de secret persisté (même test `:55-62`).

| ID | Fichier:ligne | Règle / smell | Sévérité (axe) |
|---|---|---|---|
| **STD-01** | `SpeedTestEngine.kt:32-334` (interface `:32`, `Disabled` `:49`, `Fake` `:70`, `Repository` `:151` dans un seul fichier de 334 lignes) | **Divergent Change** (jugement) : un fichier modifié pour quatre raisons distinctes (contrat, adaptateur désactivé, adaptateur fictif, orchestration). Associé à **Duplicated Code** (judgement) : les trois tests redéfinissent le même `FakeMonitor` (`SpeedTestEngineTest.kt:26-30`, `SpeedTestRepositoryConcurrencyTest.kt:25-29`, `SpeedTestRepositoryLifecycleTest.kt` id.). Correction suggérée seulement si Mission 13 ouvre une PR de refactor : extraire `SpeedTestRepository` et le `FakeMonitor` partagé ; en l'état, ARCH §2.1 assume ce regroupement (module profond) — tradeoff documenté, pas une faute. | Détail / Faible |
| **STD-02** | `SpeedTestEngine.kt:96,104,115,137-138` (`private fun ensureActive(): Boolean = !cancelled`, champ `@Volatile private var cancelled`) | **Message Chains / Middle Man + Speculative Generality** (jugement) : deux mécanismes d'annulation coexistent — le drapeau privé et `currentCoroutineContext().ensureActive()` (`:103,114`) — alors que le contrat documenté de l'interface (KDoc `:36`) dit « *suspension = annulation coopérative* ». Le drapeau est redondant avec le `Job` coroutine que le repository annule (`:308`) et masque la cause (root cause per `systematic-debugging`) : un oubli de réinitialisation ou d'écriture par ailleurs ferait « ne pas répondre » sans rien montrer. Le `Fake` peut imiter le test attendu sans corriger la cause ; le vrai test à écrire est T7 (`Call.cancel()` + `ensureActive()` par buffer, ARCH §9.6-9.7), pas un autre drapeau. Correctif proposé ici : supprimer le drapeau au profit du contexte coroutine seul (le repository annule déjà le `Job`). | Faible |
| **STD-03** | `SpeedTestEngine.kt:73` `providerLabelFake: String = "Moteur simulé (fictif)"` | **Mysterious Name** (jugement) : suffixe `Fake` sur un paramètre de constructeur public dont la valeur sert de `providerLabel` effectif (`:80`). Nommer `providerLabelOverride` ou `displayLabel`. | Détail |
| **STD-04** | `SpeedTestResult.kt:19-20` `providerLabel/networkKey: String` ; `SpeedTestHistoryStore.kt:39-40` clés JSON `"provider"/"network"` | **Primitive Obsession** (jugement) : la « clé réseau de départ » (ARCH §4.3 : ne stocke jamais l'IP publique) et l'étiquette fournisseur sont des chaînes opaques qui permettent de confondre host valide et chaîne brute saisie (NV-08). Un petit type `NetworkKey`/`ProviderLabel` issu du re-parse identité lèverait la classe d'erreur ; son absence est acceptable tant qu'un seul site construit `networkKey` (le `Fake`, `SpeedTestEngine.kt:75`). | Détail |
| **STD-05** | `SpeedTestEngine.kt:99,112` `dlTarget = max/4`, `ulTarget = max/4`, `SpeedTestCalculator` jamais appelé pour `isTooShortToTrust` dans le `Fake` | **Speculative Generality inversée / Divergent Change mineur** (jugement) : le `Fake` sous-cible systématiquement au quart des plafonds, donc ne peut jamais exercer le chemin `capped=true` (forcé `:129` `false`) — le pattern « docteur qui ne soigne jamais la maladie ». Ce n'est pas un écart à la spec (le fictif reste « honoring the caps », M12 report §2), mais il impose à T4 le premier vrai exercice du marquage. | Détail |
| **STD-06** | `SpeedTestCalculator.kt:38-43` et son test `:52-54` | **Navette doc/test** (SD, cross-axe avec SEC-04) : le commentaire et le test garantissent chacun de leur côté un comportement différent de l'autre. Voir SEC-04 pour le fonds ; côté Standards, corriger **en un seul endroit** (une règle, un test, un commentaire alignés). | Faible |

---

## 3. Revue code — axe Spec

> Spec = Mission 12 (`MISSION_12_SPEEDTEST_RESEARCH.md`, `MISSION_12_IMPLEMENTATION_REPORT.md`,
> §4 plafonds/annulation/historique) + Mission 13 (`LIBRESPEED_RESEARCH`, `ARCHITECTURE` §13).
> Chaque ligne non satisfaite est une exigence manquante ; un écart de formulation est
> cité avec sa source.

| ID | Exigence | État dans le code | Verdict |
|---|---|---|---|
| **SPEC-01** | Plafonds « 20 Mo / 10 Mo / 30 s » (`SpeedTestPolicy.kt:15-17`, `MISSION_12_SPEEDTEST_RESEARCH.md` §1) | `maxDownloadBytes = 20 * 1024 * 1024`, `maxUploadBytes = 10 * 1024 * 1024`, `maxDurationMillis = 30_000`, init cohérente (`SpeedTestPolicy.kt:24-32`, `callTimeout ≥ maxDuration`). | **Conforme** |
| **SPEC-02** | Latence = **médiane de 5 sondes** (jamais min/max), `Recorder` Research §2/§4. ARCH §13.5. | `SpeedTestCalculator.medianLatencyMillis()` (`SpeedTestCalculator.kt:24-30`) est une médiane robuste (moyenne des deux médianes en cas pair — inatteignable à 5 sondes). La divergence LibreSpeed-min vs WiFi Admin-médiane est **arbitrée** (RESEARCH §3.2 : « ne pas corriger », garder médiane). | **Conforme** (conserver) |
| **SPEC-03** | « Un test ne démarre que sur `LocalNetworkStatus.LocalWifi`, annulé sur perte/changement d'`identity.key`, aucun fallback mobile » (ARCH §1.2, M12 report §4). | Portes `:204-207` (start) et `:260-267` (revalidation avant exécution) + `watchNetworkLoss` `:316-333` + refus de liaison cellulaire `WifiLinkInfoProvider.kt:91`. Couvert par `SpeedTestEngineTest.kt:66-83,85-99,101-109`. | **Conforme** |
| **SPEC-04** | `capped` affiché « ≥ X, valeur minimale » (`SpeedTestResult.kt:18`, `SpeedTestCalculator.isCapped` `:33-36` `>=`, UI texte ARCH §10). | Modélisation présente et honnête. Le `Fake` ne l'exerce jamais (STD-05) — voir SPEC-07. | **Conforme (modèle)** |
| **SPEC-05** | **Backstop indépendant du moteur** (ARCH §7.3, §13.4 ; M12 report §7). | Absent du repository (SEC-01). Aucun test T8. | **Manquant — bloquant avant trafic réel** |
| **SPEC-06** | Timeouts branchés à l'OkHttpClient (ARCH §6.2, `SpeedTestPolicy` déjà déclarés). | Déclarés (`SpeedTestPolicy.kt:19-22`), non branchés (SEC-05) ; aucun `LibreSpeedClient`. | **Manquant (par design, M12 report §4)** |
| **SPEC-07** | Interface/protocole LibreSpeed (ARCH §2, §5, §7 : `SpeedTestEndpoint`, validation, `LibreSpeedEngine/Client`). | Aucun fichier dans le package n'existe (6 fichiers lus) ; `AppContainer` sur `DisabledSpeedTestEngine()`. Les valeurs de test T1–T10 ne sont pas encore atteignables. | **Aucun implémenté en l'état — tranche T1→T10 entièrement ouverte** |
| **SPEC-08** | Jauge « palier rond au-dessus de la valeur » (M12 report §3). | `gaugeScaleMaxMbps` renvoie 1000 < valeur pour valeur >1000 (SEC-04). | **Partiellement non conforme (edge case >1000 Mbps)** |
| **SPEC-09** | Formule « Mbps = octets×8 / secondes / 1e6, horloge monotone, sans 1.06 » (RESEARCH §8 `SpeedTestCalculator.mbps`, RESEARCH §3.3/34 arbitrage). | `SpeedTestCalculator.kt:14-18` (×1.0, division par `durationMillis/1000`, `bytes==0 → 0.0`, `duration ≤ 0 → null`). Conforme. Le facteur 1.06 de LibreSpeed doit **ne pas** être introduit sans calibration (RESEARCH §risques). | **Conforme** |

---

## 4. Plan de tests

### 4.1 Numérotation des seams (réconciliation)

Le brief de cette mission et ARCH §12 numérotent différemment ; pour éviter toute
ambiguïté dans les tranches, ce document retient la numérotation du **brief** et
signale la correspondance vers ARCH :

| # (brief) | Seam | Correspondance ARCH §12 | Couche |
|---|---|---|---|
| **S1** | `SpeedTestEndpointValidator.parse()` — validation URL **pure JVM, sans Android** | ARCH S2 | JVM (`test/`) |
| **S2** | `LibreSpeedEngine.run()` + `LibreSpeedClient` contre **MockWebServer loopback `127.0.0.1`** | ARCH S1 | JVM (`test/`) |
| **S3** | `SpeedTestRepository` existant + **compteur backstop** | ARCH S3 | JVM (`test/`) |
| **S4** | Compose `SpeedTestContent` / écran avec `SpeedTestUiState` construit à la main | ARCH S4 | instrumenté `androidTest/` (loopback uniquement) |

*Un seam non confirmé ne prend aucun test* (règle `tdd`, ARCH §12) : la confirmation
de S1–S4 est l'étape 1 du plan d'exécution ARCH §16. Jusqu'à confirmation, T1 reste rouge par interdiction, pas par échec d'assertion.

### 4.2 Ce que chaque seam attrape / manque

| Seam | Ce qu'il attrape | Style / fixture |
|---|---|---|
| **S1 — validation pure** | Schéma `https` seul, hôte absent/vide, `userInfo`/identifiants (`user:pass@`, `@` brut, `token=`, `apikey=`, `Authorization`/`Bearer`), fragment, query présente (§1.2.1), port exotique, longueur >512, chemin >256 ou `..`, contrôle/ESPACE, unicode/punycode, re-parse identité (`https://evil@hôte`), exception loopback **désactivée par défaut** (§1.2.2). Entrées synoptiques : table fermée de ~30 entrées (`""`, `http://…`, `https://…/garbage.php` OK, `https://user@…`, casse, `https://h:53`, `https://…?r=1`, `https://…#…`, `javascript:`). | JUnit pur, paramètre `allowHttpLoopback=false` par défaut ; valeurs attendues **littérales** (jamais recalculées par le code de production). |
| **S2 — moteur loopback** | Compteurs exacts (0 contenu ⇒ 0 octet), plafonds en flux même si serveur sert 100 Mo (exactitude à l'octet, NV-03), `capped=true` ↔ plancher, download vs upload asymétriques, corps fermés (`use{}`), `followRedirects=false` (302 ⇒ `ServerUnavailable`, aucun follow), timeouts connect/read/call branchés (SEC-05 → valeurs `SpeedTestPolicy`), codes 5xx/DNS/TLS mappés (`ServerUnavailable`/`Timeout`), ping médiane 5 sondes (pas min), keep-alive/pool dédié, payload upload régénéré 1–4 Mio, `ensureActive()` par buffer, sans lecture de `Content-Length`. | MockWebServer sur `127.0.0.1:<port>`, URL injectée par le test (jamais `BuildConfig`) ; temps contrôlés par `TestAppClock` + `StandardTestDispatcher` (schéma déjà prouvé par les tests Mission 12). `Accept-Encoding: identity` vérifié par l'en-tête reçu. |
| **S3 — repository/backstop** | Single-flight (refus d'un `start()` pendant run **et** pendant cleanup suspendu « old never cancels new » — déjà prouvé), annulation sous verrou, `generation` anti-écriture périmée, `withTimeout(30 s)` champ, revalidation Wi-Fi + changement d'identité, **nouveau** : kill du run par backstop quand `downloadBytes + uploadBytes` ou une direction dépasse plafond+marge → `Incomplete` + `engine.cancel()` (T8). | Faux moteurs ad hoc (compteur fou), dispatchers de test, `advanceTimeBy`, zéro réseau. |
| **S4 — Compose** | Bandeau « vers `<hôte>` » visible (toujours, en-tête + consentement + ligne Fournisseur), bouton désactivé sans endpoint (`start_button_is_disabled_without_a_provider` inchangé), préfixe « ≥ » si `capped`, chiffres `—` jamais `0` pour inconnu, accessibilité `liveRegion`, aucun bouton/faux débit sur `capped`. **Manque** : toute erreur réseau réelle (recouverte par S2/S3). | `SpeedTestUiState` construit à la main + FakeEngine ; n'émet aucun appel (loopback si écran instancié). |

### 4.3 Tranches verticales rouge → vert (condensé d'ARCH §12-bis)

| Tranche | Test rouge (échec attendu) | Vert minimal |
|---|---|---|
| T1 | `parse("") == null`, `parse("http://…") == null` | `SpeedTestEndpoint` + `Validator` pur |
| T2 | `parse("https://speed.example.org/app/") → hostLabel "speed.example.org"` ; rejets `user:pass@`, `token=`, `?`/`#` | normalisation §5.4 + re-parse identité |
| T3 | MockWebServer sert 5 Mo, `run()` → `downloadBytes = 5_000_000`, `downloadMbps` littéral indépendant | `LibreSpeedClient` + `LibreSpeedEngine` (DL séquentiel d'abord, N≤4 dès l'amorce) |
| T4 | Serveur sert 100 Mo → `downloadBytes == maxDownloadBytes` exactement, `capped == true` | compteur borné en flux (NV-03) |
| T5 | Miroir T3/T4 en upload (`10 Mo`) | `upload()` streamed 1–4 Mio régénéré |
| T6 | Ping médiane 5 sondes littérale ; `SocketTimeoutException` → `Timeout`/`ServerUnavailable` | `ping()` + branchement timeouts (SEC-05) |
| T7 | `cancel()` pendant DL : `Call.cancel()` chaque appel, slot libéré, `Cancelled`, run successeur intact | ensemble d'appels sous verrou + `ensureActive()`/buffer |
| T8 | Moteur à compteur fou → backstop tue le run | compteur de run dans le repository (SEC-01) |
| T9 | Compose configuré : `≥`, `vers <hôte>`, bouton actif | bandeau + ViewModel `providerLabel`/`targetHost` |
| T10 | `AppContainer` : `BuildConfig` vide → `Disabled`, valeur valide → `LibreSpeed` | choix d'adaptateur |

**Anti-patterns proscrits (`tdd`)** : tester `LibreSpeedClient` au-delà de son
comportement observable par `LibreSpeedEngine.run()` (Implementation-coupled) ;
attendus recalculés par `SpeedTestCalculator` (tautologique) ;
« tous les tests d'abord » (Horizontal slicing).

---

## 5. Conflits entre règles de sécurité du projet

| # | Règle A (projet / spec) | Règle B (en tension) | Résolution |
|---|---|---|---|
| C-1 | Manifest global `usesCleartextTraffic="true"` (`AndroidManifest.xml:27`), requis par l'HTTP clair routeur (`ANDROID_ARCHITECTURE.md:270`). | « HTTPS obligatoire / aucun `http` hors loopback » (ARCH §5.4/§13). | Résolu a minima : le contrôle restant est le validator + assertion de schéma par requête + `followRedirects=false` ; le résiduel (SEC-02) est documenté. Une `networkSecurityConfig` ne discriminera pas l'hôte routeur saisi par l'utilisateur de l'hôte de mesure. |
| C-2 | `Mission12BoundaryTest.kt:29-34` : « aucun `https://` dans `data/` » (tout littéral bloqué). | LibreSpeed a besoin de chemins relatifs (`garbage.php`/`empty.php`) et d'une base URL externe. | ARCH D5 propose l'assouplissement ciblé : autoriser des **chemins relatifs sans hôte** seulement. Le reviewer y est favorable ssi SEC-03 ajoute parallèlement l'allowlist des chemins + les interdits `getIP/telemetry` — jamais un assouplissement sur « tout `https://` toléré ». |
| C-3 | RESEARCH §7.2 « `getIP.php` : optionnel — sans `isp` si confirmation serveur ». | Principe « no public IP stored » (M12 report §7, `SpeedTestResult.kt:9`, déduite de l'IP publique visible du serveur). | Gelée par RESEARCH Rec §3 et ARCH §7.2/Rec 2 : **ne pas appeler `getIP.php` du tout** (NV-09). |
| C-4 | Mission exige le livrable `docs/MISSION_13_SECURITY_REVIEW.md`. | Skill `security-audit` en mode guidance : « ne pas lancer le workflow complet, ne pas créer d'artefacts d'audit ». | Le livrable est un seul document de revue revu par la brief Mission 13, pas les artefacts phase 1–6 du skill ; le mode guidance est respecté (aucun répertoire `~/security-audit-skill`, aucun `findings.json`, aucune exécution ciblée). |
| C-5 | Skill `code-review` : deux sub-agents parallèles sur un diff `git diff <fixed-point>...HEAD` + récépissé anti-reranking. | Mission 13 : sous-agent unique, reviewer indépendant, aucun diff de code (pas de patch), aucun fix appliqué. | Adaptations explicites : deux sections séparées §2/§3 non fusionnées (la règle anti-reranking est préservée), revue de l'**état courant du working tree** contre les specs Mission 12/13 au lieu d'un diff de commits — aucun commit/Patch à figer ; les correctifs proposés sont donc énoncés, jamais appliqués. |
| C-6 | `systematic-debugging` : « test échouant d'abord », Phase 1 reproduction consistante. | Mission 13 : « sans modifier AUCUN fichier de production (Kotlin, gradle, manifest) », « aucun appel réseau, aucun PoC exploitant ». | Plan de tests **documentatoire** (§4) ; aucune reproduction ni test écrit pendant cette revue ; l'ordre T1→T10 garde la logique rouge→vert sans exécution ici. |
| C-7 | TDD : « aucun test à un seam non confirmé » (ARCH §12). | ARCH §16.1 place la confirmation des seams en amont de T1. | §4.1 garde cette porte ; tant qu'elle n'est pas passée, T1 n'est pas autorisé à démarrer. |

Aucun conflit non résolu n'a été relevé côté permission (`INTERNET` + `ACCESS_NETWORK_STATE` +
`ACCESS_LOCAL_NETWORK` déjà déclarées ; aucune permission de localisation) ni côté
dépendance (OkHttp `4.12.0` partagé `router-core`/`app` via catalogue unique).

---

## 6. Récapitulatif de sortie

**Conforme dès maintenant** : désactivé par défaut sans endpoint
(`DisabledSpeedTestEngine` en production, `WifiAdminApplication.kt:127`),
plafonds exacts et validés (`SpeedTestPolicy.kt:15-17,24-32`),
médiane de 5 sondes (`SpeedTestCalculator.kt:24-30`),
Wi-Fi strict sans repli cellulaire (`SpeedTestEngine.kt:204-207,260-267,316-333`,
`WifiLinkInfoProvider.kt:91`), zéro log / zéro secret persisté / zéro XPSs
(surface Compose, liens externes `httpsExternalLinks()` seulement),
formule Mbps ×1.0 sans facteur 1.06.

**À corriger avant tout trafic réel** : backstop indépendant (SEC-01, T8),
durcissement HTTPS au niveau plateforme (SEC-02, assert par requête), frontières
protocole dans les tests (SEC-03), jauge au-dessus de 1000 (SEC-04),
branchement des timeouts (SEC-05, T6), et surtout **aucune implémentation
LibreSpeed dans ce checkout** (SPEC-07 : T1→T10 à réaliser).

### Findings triés par sévérité (sécurité, guidance)

1. **Medium** — SEC-01 : backstop indépendant des plafonds octets absent
   (`SpeedTestEngine.kt`, `SpeedTestResult.kt:32-33`).
2. **Medium** — SEC-02 : `usesCleartextTraffic="true"` globale (`AndroidManifest.xml:27`),
   HTTPS restant applicatif uniquement.
3. **Low** — SEC-03 : `Mission12BoundaryTest` ne garde ni les chemins allowlist
   ni les littéraux interdits (`getIP`, `telemetry`).
4. **Low** — SEC-04 : jauge `gaugeScaleMaxMbps` sous la valeur au-delà de 1000 Mbps
   (`SpeedTestCalculator.kt:38-43`).
5. **Informational** — SEC-05 : timeouts de la policy non branchés (conversation
   structurelle, `SpeedTestPolicy.kt:19-22`).

Axes code (non fusionnés, `code-review`) : Standards — 6 findings dont aucune violation
dure documentée hors STD-06, le plus problématique (STD-02 double mécanisme
d'annulation `SpeedTestEngine.kt:137-138` vs `:103-104`) ;
Spec — 9 exigences : 5 conformes (SPEC-01/02/03/04/09), 1 partiellement
non conforme (SPEC-08 jauge), 3 manquantes bloquantes avant trafic réel
(SPEC-05 backstop, SPEC-06 timeouts, SPEC-07 implémentation absente).

**Points needs_validation (sans sévérité)** : NV-01 actualité OkHttp 4.12.0,
NV-02 MockWebServer absent du catalogue, NV-03 exactitude du plafond en N flux,
NV-04 migration cellulaire d'un socket ouvert (ARCH §6.6),
NV-05 sandbox d'exécution pour T4/T8, NV-06 validation LGPL par un juriste,
NV-07 persistance/purge de `speedtest_base_url` (DataStore B),
NV-08 construction du `hostLabel` depuis le re-parse identique,
NV-09 interdiction gelée de l'appel `getIP.php`.

*Contenu de ce document : aucun secret, aucune adresse IP publique, aucun SSID/MAC,
aucune donnée d'utilisateur. Revue indépendante de la propension du projet — aucun
fichier de production n'a été modifié.*
