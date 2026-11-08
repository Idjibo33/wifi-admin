# Mission 13 — Revue corrective indépendante (revue de sécurité guidance + code deux axes)

> Date : 08/11/2026 · Branche : `feature/mission-13-speedtest-reel` (base `feature/mission-12-speedtest`).
> **Mode guidance uniquement** (skill `security-audit`) : aucune exécution de workflow complet,
> aucun artefact d'audit (pas de `findings.json`, pas de dossier `~/security-audit-skill`),
> aucune exécution de code cible, aucun appel réseau, aucun PoC, aucun secret.
> Revue statique des sources uniquement — lecture de source, aucune modification de production.
> Skills appliquées : `security-audit` (guidance : sévérité seulement sur findings confirmés source,
> `needs_validation` sans sévérité), `code-review` (axes Standards / Spec séparés, non fusionnés).
> Périmètre lu : `app/src/main/java/dev/wifiadmin/app/data/speedtest/*.kt` (10 fichiers, dont
> `SpeedTestEndpoint.kt`, `SpeedTestEngineFactory.kt`, `LibreSpeedClient.kt`, `LibreSpeedEngine.kt`),
> `app/src/main/java/dev/wifiadmin/app/ui/speedtest/SpeedTestPresentation.kt`,
> `SpeedTestScreen.kt`, `SpeedTestViewModel.kt`, `app/src/main/AndroidManifest.xml`,
> `app/src/main/java/dev/wifiadmin/app/WifiAdminApplication.kt`,
> `docs/MISSION_13_SECURITY_REVIEW.md`, `docs/MISSION_13_ARCHITECTURE.md`,
> `docs/MISSION_13_LIBRESPEED_RESEARCH.md`, `docs/MISSION_13_WIFI_ROUTING.md`,
> `docs/MISSION_13_IMPLEMENTATION_REPORT.md`, `app/src/test/.../Mission12BoundaryTest.kt`,
> `SpeedTestEndpointValidatorTest.kt`, `SpeedTestRepositoryBackstopTest.kt`,
> `SpeedTestEngineFactoryTest.kt`. Aucun fichier de production modifié.
>
> Rappel de méthode (guidance) : à défaut de limite de confiance franchie et d'effet concret observable
> depuis la source, il n'y a pas de finding confirmé — seulement une observation de défense en profondeur
> (sans sévérité) ou un `needs_validation` (fait bloquant externe, sans sévérité).

---

## Résumé

Depuis la première revue (`MISSION_13_SECURITY_REVIEW.md`), le moteur réel a été implémenté :
`SpeedTestEndpoint` + validateur pur, `SpeedTestEngineFactory` (choix `Disabled`/`LibreSpeed`),
`LibreSpeedClient` (seul ouvrant des sockets Internet), `LibreSpeedEngine` (latence médiane → download →
upload). `WifiAdminApplication` lit `BuildConfig.SPEEDTEST_BASE_URL` (vide par défaut) et choisit
l'adaptateur en conséquence. La suite JVM est verte (391 tests selon le rapport d'implémentation)
et la CI reste loopback-only (MockWebServer `127.0.0.1`).

**Contrôles vérifiés conformes sur source :** TLS par défaut (aucun `TrustManager`/`HostnameVerifier`/
`CertificatePinner` permissif), validation d'endpoint stricte (schéma `https`, rejet de toute query,
de `userInfo`/`@`, re-parse identité), `followRedirects=false` + `followSslRedirects=false` avec
`3xx → ServerUnavailable`, télémétrie impossible (seuls `empty.php`/`garbage.php` appelés,
`getIP.php`/`telemetry.php` jamais appelés, aucun log, aucun secret persisté), compteurs en flux
ne faisant jamais confiance à `Content-Length`, annulation `Call.cancel()` + `ensureActive()` par
buffer, fermeture `use {}` systématique, gating logique `LocalWifi` sans repli cellulaire, isolation
ZLT (zéro `ZltRouterClient`/`RouterTransport`/`cmd` dans `data/speedtest/`).

**Écarts confirmés restants (séverité, guidance) :** aucun `critical`/`high`/`medium`.
Restent des **faibles / informationnels** : durcissement HTTPS encore purement applicatif
(`usesCleartextTraffic` globale + exception loopback non paramétrable en production),
garde-fous frontière incomplets côté tests (`getIP`/`telemetry` non surveillés), portée du
backstop repository comprise comme filet de rapport et non garantie fil (voir §1.2),
jauge au-delà de 1000 Mbps (hérité Mission 12, inchangé).

**Bloquant pour le trafic réel (sans sévérité, `needs_validation`) :** liaison Wi-Fi exclusive
des sockets non implémentée (document `MISSION_13_WIFI_ROUTING.md` = proposition seule),
validation LGPL par juriste, rate-limit du backend opérateur, recette appareil (TLS de bout en
bout, bascule cellulaire réelle, VPN/IPv6).

Axes code (non fusionnés, `code-review`) : **Standards** — 3 jugements (fichier monolithique,
double mécanisme d'annulation du `Fake`, progression upload en un seul événement),
aucune violation dure documentée hors heritage ; **Spec** — 8 exigences, 6 conformes,
2 partiellement conformes (`ckSize=1` hors fourchette documentée 4–1024 mais accepté par le PHP
officiel, rou­age physique Wi-Fi en attente).

---

## 1. Revue de sécurité (guidance)

### 1.1 Tableau des contrôles demandés

| Contrôle | État observé (source + ligne) | Verdict |
|---|---|---|
| **TLS et vérification d'hôte** | `SpeedTestEngineFactory.kt:24-31` construit `OkHttpClient.Builder()` avec timeouts + `followRedirects(false)` uniquement — aucun `sslSocketFactory`, `hostnameVerifier`, `trustManager`, `CertificatePinner` custom dans tout `app/src/main/java/dev/wifiadmin/app/data/speedtest/` (grep : 0 hit hors commentaires). TLS + vérification d'hôte = défaut OkHttp (certificat valide exigé, hôte validé). `AndroidManifest.xml:27` `usesCleartextTraffic="true"` globale, sans `networkSecurityConfig` — nécessaire pour le routeur LAN en HTTP clair (`ANDROID_ARCHITECTURE.md:270`), donc rétrograde HTTPS en contrôle applicatif uniquement (voir finding CORR-01). | **Conforme créationnel, résiduel platform-level (Low)** |
| **Validation des endpoints** | `SpeedTestEndpoint.kt:29-56` : `trim`, ≤512, rejet `' '`, contrôle (`<= ' '`), `'@'` brut, `SECRET_HINTS` (`password=`, `token=`, `apikey=`, `api_key=`, `authorization`, `bearer`), `userInfo != null → null`, `fragment != null → null`, **toute query non vide → null** (`!uri.query.isNullOrEmpty()`), schéma `https`/`http` seul, `http` seulement si `LOOPBACK_HOSTS = {127.0.0.1, ::1, [::1], localhost}` (`:43-44`), chemin ≤256 sans `..`, port `1..65535`, re-parse identité (`host.equals(reparsed.host)`). `baseUrl` normalisée sans slash final doublé, `hostLabel` = host seul. Testé par `SpeedTestEndpointValidatorTest.kt` (8 tests). Il reste trois durcissements non faits (CORR-02) : exception loopback sans paramètre `allowHttpLoopback`, port quelconque `1..65535`, chemin encodé `%2e%2e` non détecté. | **Conforme, durcissements mineurs (Low/Info)** |
| **Redirections cross-hôte** | `SpeedTestEngineFactory.kt:29-30` `followRedirects(false)` + `followSslRedirects(false)`. `LibreSpeedClient.kt` : `pingMillis` `:72`, `download` `:102`, `upload` `:159` — `if (response.code != 200) throw ServerUnavailable`. Un `301/302/307` n'est donc jamais suivi ; il échoue net. Aucun `Location` lu. | **Conforme** |
| **Télémétrie automatique interdite** | `LibreSpeedClient.kt:19-21` documente : jamais `getIP.php`, jamais `telemetry.php`. Seuls chemins construits : `empty.php?r=` (ping GET `:65`, upload POST `:153`), `garbage.php?ckSize=1&r=` (download `:95`). Aucun autre endpoint dans `data/speedtest/` (grep `getIP|telemetry` : 0 hit hors commentaires). Aucun `Log.`/`HttpLoggingInterceptor`/`println` dans `app/` (grep : 0 hit). `SpeedTestHistoryStore.kt:28-45` ne persiste que débits/octets/durée/provider/network/heure (jamais IP publique/SSID/MAC/secret). `allowBackup=false` (`AndroidManifest.xml:21`). Mention IP visible du serveur pendant la mesure = explicite à l'écran (`SpeedTestScreen.kt:412`, `SpeedTestPresentation.kt:141`). Garde-fou test manquant (CORR-03). | **Conforme, garde-fou à ajouter (Low)** |
| **Compteurs de données + backstop repository** | Voir §1.2 — portée exacte. Garantie fil = `LibreSpeedClient.kt:99-118` (download) et `:136-147` (upload). Filet rapport = `SpeedTestEngine.kt:268-283` (wrapper `onProgress` du repository). | **Conforme comme système à deux couches, portée du filet à ne pas surinterpréter (Info)** |
| **Annulation** | `LibreSpeedClient.kt:33-48` : `ConcurrentHashMap` d'appels en vol, `cancel()` idempotent thread-safe (`cancelled=true` + `forEach { it.cancel() }`), `newCall` annulé par avance si déjà annulé, `requireActive()` = `ensureActive()` + drapeau. Download appelle `requireActive()` à chaque buffer 64 KiB (`:112`) ; upload `writeTo` teste `cancelled` à chaque chunk et lève `IOException("cancelled")` (`:144`). `LibreSpeedEngine.kt:41-48` : `IOException` + `client.isCancelled()` → `CancellationException` (pas `ServerUnavailable`). Repository `SpeedTestEngine.kt:328-338` : `cancel()` sous `lock`, `engine.cancel()` + `runJob.cancel()` + publication `Cancelled`, `finally` génération-gardé `:307-315`, slot libéré seulement par `invokeOnCompletion` `:232-238`. `SpeedTestViewModel.kt:158-161` : `onCleared → repository.cancel()`. | **Conforme** |
| **Fermeture des ressources HTTP (`use{}`)** | `LibreSpeedClient.kt:71`, `:101`, `:158` — les trois `call.execute().use { response -> … }`. `release(call)` en `finally` (`:79-81`, `:120-122`, `:163-165`). Download lit via `response.body.source()` + `ByteArray(65536)`, jamais `body.string()`, jamais `buffer()` cru (commentaire `:105-107` corrige l'erreur `Buffer` vs `BufferedSource`, preuve : `expected 1000000 but was 0` avant correction). Upload corps streamed `contentLength = maxBytes`, `sink.flush()` terminal. | **Conforme** |
| **Absence de fallback mobile** | Repository : `SpeedTestEngine.kt:204-207` (`start` refuse hors `LocalWifi`), `:260-267` (revalidation + `identity.key` avant `engine.run`), `:341-358` (`watchNetworkLoss` → `cancel(NETWORK_LOST/CHANGED)`, sans retry). `WifiLinkInfoProvider.kt:90-91` : `!TRANSPORT_WIFI → null`, `TRANSPORT_CELLULAR présent → null` (même cumulé avec Wi-Fi). Aucun `retry`, aucun polling, aucun service. **Liaison Wi-Fi exclusive à venir** : `MISSION_13_WIFI_ROUTING.md` propose (non implémentée) `WifiNetworkRouteProvider` (`socketFactory` + `Dns` par run, sans `bindProcessToNetwork`). La fabrique actuelle (`SpeedTestEngineFactory.kt:24-31`) n'impose ni `socketFactory` ni `Dns` — le routage suit le défaut Android, seul l'annulation réactive protège. | **Conforme logique, liaison physique en attente (NV, sans sévérité)** |
| **Isolation du protocole ZLT** | Grep `Zlt|ZLT|RouterTransport|RouterRepository|/cgi-bin|cmd` sur `data/speedtest/` : 0 hit. `Mission12BoundaryTest.kt:20-27` interdit `ZltRouterClient`/`RouterTransport`/`/cgi-bin/http.cgi`/`cmd 100` dans `data/` + `ui/speedtest/` ; `:29-39` interdit `https://` dans `data/speedtest/` (aucun hôte en dur, `BuildConfig` injecté par build, vide par défaut `app/build.gradle.kts:20`). `router-core/` non touché par Mission 13. | **Conforme** |

### 1.2 Portée EXACTE du backstop repository — ce qu'il garantit et ce qu'il ne garantit pas

Le brief l'exige explicitement : un contrôle sur les événements de progression ne garantit pas à
lui seul qu'un moteur défectueux ne transfère jamais plus de données. Le code actuel comporte
**deux couches distinctes**, de nature différente, qu'il ne faut pas confondre.

**Couche 1 — compteur en flux du client (`LibreSpeedClient`), garantie fil.**

- Download (`:89-124`) : `var counted = 0L`, `while (counted < maxBytes)`, `wanted = min(buffer, maxBytes-counted)`,
  `read = source.read(buffer, 0, wanted)`, `counted += read`, `onProgress(counted)`. Le flux est fermé
  (`use {}`) dès que `counted == maxBytes` (`policy.maxDownloadBytes = 20 MiB`). `Content-Length` n'est
  jamais lu — seuls les octets réellement lus du socket sont comptés (conforme ARCH §6.1).
- Upload (`:131-168`) : `contentLength() = maxBytes`, boucle `while (remaining > 0)` écrivant exactement
  `maxBytes` (`policy.maxUploadBytes = 10 MiB`). Aucun octet au-delà du plafond ne peut être sérialisé
  dans le corps, `sink.flush()` terminal. `onProgress(maxBytes)` n'est émis qu'après le `200` —
  la progression upload est donc **en un seul événement terminal**, pas en flux (voir Standards STD-03).
- Ping : 5× `GET empty.php` de taille négligeable, non comptés dans les plafonds données (ARCH §7 :
  les plafonds ne couvrent que DL/UL ; le trafic ping reste < 1 Ko, non déductible du forfait).
- Parallélisme actuel : **1 flux download, 1 flux upload séquentiels** (un seul `garbage.php`, un seul
  `POST empty.php`). La course `N×buffer` de l'ARCH §6.4 (N≤4) ne peut donc pas produire de dépassement.
  Si N>1 est introduit plus tard, il faudra un compteur atomique partagé + borne avant ajout (NV-03).

**Couche 2 — backstop du repository (`SpeedTestEngine.kt:268-283`), filet sur le rapport.**

```kotlin
val result = withTimeout(policy.maxDurationMillis) {
    engine.run { progress ->
        val overDownload = progress.downloadBytes > policy.maxDownloadBytes
        val overUpload   = progress.uploadBytes   > policy.maxUploadBytes
        val overTotal    = progress.downloadBytes + progress.uploadBytes >
                            policy.maxDownloadBytes + policy.maxUploadBytes
        if (overDownload || overUpload || overTotal) {
            runCatching { engine.cancel() }
            throw SpeedTestFailureException(SpeedTestFailure.Incomplete)
        }
        publish(myGeneration, progress)
    }
}
```

**Portée exacte :**

1. Il ne voit que les valeurs `SpeedTestState.Running.downloadBytes/uploadBytes` **émises** via `onProgress`.
   Si un moteur transfère plus sur le fil mais émet un compteur mensonger ou ne l'émet pas du tout,
   le backstop est aveugle — il ne compte pas les octets du socket lui-même.
2. Il ne voit que ce qui traverse le wrapper : avec le client actuel, download émet tous les 64 KiB
   (réactivité bonne), upload n'émet qu'en fin (le backstop ne peut pas interrompre un upload en cours,
   il ne le détecte qu'après le `200` — mais le corps est de toute façon borné par `contentLength`).
3. Le seuil est `>`Strict (pas `>=`) : `== plafond` passe, `> plafond` tue en `Incomplete`. La précision
   est donc au buffer près (64 KiB de dépassement détectable au premier événement supérieur).
4. Il est **complémentaire, pas remplaçant** : sa valeur est de neutraliser une régression de compteur
   du moteur côté rapport (moteur qui émet `99 000 000` dans le test `SpeedTestRepositoryBackstopTest.kt:38`
   → `Incomplete` publié, `isRunning=false`). La garantie « jamais plus de données que le plafond sur
   le fil » vient de la couche 1, pas de la couche 2.
5. `withTimeout(30 s)` est l'autre dimension du backstop (temps), orthogonale aux octets. Les deux
   ensemble couvrent « plafond octets » et « plafond durée », mais aucun ne couvre « moteur qui n'émet
   pas et transfère en boucle sans appeler `onProgress` » si le client n'avait pas déjà borné le flux.
   C'est pourquoi l'exigence ARCH §7.3 doit se lire : le compteur en flux du client est **l'enforcement**,
   le wrapper du repository est le **filet** contre l'erreur de rapport.

Aucun séquelle ne dépend du backstop seul pour borner le fil dans le moteur actuel : la couche 1
est saine, la couche 2 est testée (`SpeedTestRepositoryBackstopTest` : moteur au compteur fou →
`Failed(Incomplete)`, moteur dans le plafond → `Done`). La régression à craindre n'est pas un
moteur mensonger hypothétique, mais une modification de `LibreSpeedClient` qui cesserait de compter
en flux (ex. lire `Content-Length` au lieu des octets lus) : le test T4/T8 doit protéger la couche 1
par serveur servant 100 Mo → `downloadBytes == maxDownloadBytes` à l'octet près.

### 1.3 Findings confirmés (sévérité)

> Méthode (security-audit priorité/certitude) : chaque finding nomme le contrôle explicite, le code
> source, la limite franchie et l'effet concret. L'exposition actuelle est nulle tant que
> `BuildConfig.SPEEDTEST_BASE_URL=""` (défaut `app/build.gradle.kts:20`) → `DisabledSpeedTestEngine`
> (`SpeedTestEngineFactory.kt:22-23`) → zéro octet quittant le téléphone. Les sévérités ci-dessous
> valent pour le jour où un endpoint opérateur valide est branché.

| ID | Sévérité | Finding (fichier:ligne, règle, effet) |
|---|---|---|
| **CORR-01** | **Low** | **HTTPS encore purement applicatif (hérité SEC-02, résiduel).** Règle : ARCH §13.3 « HTTPS ou désactivé ». Code : `AndroidManifest.xml:27` `usesCleartextTraffic="true"` globale sans `networkSecurityConfig` ; la physique TLS ne repose que sur le validateur (`SpeedTestEndpoint.kt:40-44`) + défaut OkHttp. Effet : une seule régression du validateur (ou un `3xx` suivi à tort) exposerait le trafic de mesure en clair. Compensations déjà en place : rejet de tout `http` hors loopback, rejet de toute query, `followRedirects=false` + codes `!=200 → ServerUnavailable`. Une `networkSecurityConfig` par domaine ne discriminera pas l'hôte routeur (saisi par l'utilisateur, HTTP clair légitime) de l'hôte de mesure (hôte validé) — résiduel documenté, non réparable au niveau plateforme sans refonte routeur. Piste de durcissement a minima : assertion de schéma par requête dans `LibreSpeedClient` (ex. `check(endpoint.baseUrl.startsWith("https://") \|\| loopback)`) pour que le client ne puisse jamais émettre du clair vers un hôte non-loopback même si le validateur régresse. |
| **CORR-02** | **Low** | **Exception loopback non paramétrable + ports et encodage non restreints (durcissement du validateur).** Règle : ARCH §5.4.2 « `http` accepté si loopback » **réservé aux tests MockWebServer**, RESEARCH §7.1 « certificat valide, pas de fallback HTTP ». Code : `SpeedTestEndpoint.kt:43-44` accepte `http://127.0.0.1:port` **sans paramètre** — en production `BuildConfig.SPEEDTEST_BASE_URL="http://127.0.0.1:8765"` brancherait un moteur clair (trafic loopback seul, donc pas d'exfiltration, mais non-conforme à « HTTPS obligatoire » et vérifiable par fuzz). Même fichier : port `1..65535` (`:48`) autorise `https://h:53` (évasion DNS, impact quasi nul car hôte opérateur), chemin `path.contains("..")` (`:46`) ne détecte pas `%2e%2e` encodé. Correctif recommandé : `fun parse(raw: String, allowHttpLoopback: Boolean = false)` avec `false` en production (y compris `BuildConfig`), `true` seulement dans le câblage des tests ; restreindre les ports explicites (ex. `443` + tout port avec test dédié) et rejeter `%2e`/`%2f` dans le chemin ou normaliser avant contrôle. Effet borné à un hôte opérateur, pas à un acteur distant. |
| **CORR-03** | **Low** | **Garde-fous frontière toujours incomplets (hérité SEC-03).** Règle : ARCH §7.2/Rec §5 interdisent `getIP.php`/`telemetry.php`. Code : `Mission12BoundaryTest.kt:55-62` ne refuse que `sessionId`/`password`/`"ssid"` ; rien sur `getIP`, `telemetry`, `isp`, `X-Forwarded-For`, `mac`, `token`. Un futur `LibreSpeedEngine` qui appellerait `getIP.php`/`telemetry.php` passerait le test au vert. Correctif : allowlist de chemins relatifs (`garbage.php`, `empty.php` uniquement) + littéraux interdits (`getIP`, `telemetry`, `isp`, `X-Forwarded-For`) en fixtures de test, sans introduire d'URL d'hôte (compatibilité D5 : chemins relatifs sans hôte). Aujourd'hui aucun appel n'existe — c'est un risque de régression, pas une violation active. |
| **CORR-04** | **Informational** | **Portée du backstop sous-documentée dans le code (pas une vulnérabilité active).** Règle : ARCH §7.3/§13.4. Code : `SpeedTestEngine.kt:270-273` commente « even a defective engine cannot exceed the prepaid caps. The byte counters traverse this wrapper » — promesse plus forte que la portée réelle (voir §1.2). Avec le client actuel, la promesse est vraie par la couche client, pas par le wrapper. Corriger le commentaire (« rapport de progression vérifié ; la garantie fil vient du compteur en flux du client ») pour que la couverture T8 ne masque pas une régression du compteur client. |
| **CORR-05** | **Informational** | **Jauge héritée non corrigée (SEC-04 conservée).** `SpeedTestCalculator.kt:38-43` : `firstOrNull { it >= value } ?: steps.last()` renvoie 1000 pour `value > 1000`, alors que le commentaire promet « next rounded step above ». Sous le plafond 20 Mo/30 s, >1000 Mbps exige <1 s de fenêtre, déjà marquée `capped` — exposition pratique quasi nulle, mais code et test (`SpeedTestCalculatorTest.kt:52-54`) se contredisent. Étendre la liste (2 500/5 000) ou documenter le clamp, en un seul endroit (voir Standards STD-03). |

**Statuts SEC-01→05 de la revue précédente :** SEC-01 (backstop absent) → **corrigé** (`SpeedTestEngine.kt:268-283` + test) ;
SEC-02 (cleartext globale) → **résiduel Low** (CORR-01, compensations désormais en place) ; SEC-03 →
**ouvert** (CORR-03) ; SEC-04 → **ouvert Informational** (CORR-05) ; SEC-05 (timeouts non branchés) →
**corrigé** (`SpeedTestEngineFactory.kt:25-28` branche `call/connect/read/write` depuis la policy).

---

## 2. Revue code — axe Standards

> Notes de méthode (skill `code-review`) : les écarts aux standards **documentés** du dépôt sont des
> violations dures ; les smells de Fowler sont des jugements étiquetés (« possible … »), jamais des
> violations. Aucun standard `CODING_STANDARDS.md`/`CONTRIBUTING.md` n'existe dans le dépôt — les
> standards documentés retenus sont `docs/ANDROID_ARCHITECTURE.md` §5 (zéro log dans `app`),
> `Mission12BoundaryTest` (séparation routeur/Internet, pas de secret, pas de permission de
> localisation) et l'ARCH §2/§9 (module profond, invariants de concurrence). Aucun smell n'est
> signalé si l'outillage le contraint déjà.

**Standards documentés respectés (forces).** Zéro log (grep `Log.`/`HttpLoggingInterceptor`/`println` :
0 hit — conforme ANDROID_ARCHITECTURE §5), zéro secret persisté (`SpeedTestHistoryStore.kt:28-45`,
`SpeedTestResult.kt:9` « jamais d'IP publique/SSID/MAC »), séparation routeur/Internet
(`Mission12BoundaryTest.kt:20-39` vert sur source), pas de permission de localisation
(`AndroidManifest.xml:10-17` : `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK` seulement,
`Mission12BoundaryTest.kt:49-53` vert), `router-core` intact.

| ID | Fichier:ligne | Règle / smell | Sévérité (axe) |
|---|---|---|---|
| **STD-01** | `SpeedTestEngine.kt:32-358` (interface `:32`, `Disabled` `:49`, `Fake` `:70`, `Repository` `:151` dans un même fichier de 358 lignes) | **Divergent Change** (jugement) : un fichier modifié pour quatre raisons distinctes (contrat, adaptateur désactivé, adaptateur fictif, orchestration). Associé à **Duplicated Code** (jugement) : `FakeMonitor` redéfini dans chaque fichier de test (`SpeedTestEngineTest`, `SpeedTestRepositoryConcurrencyTest`, `SpeedTestRepositoryBackstopTest.kt:28-32`). ARCH §2.1 assume ce regroupement (module profond) — tradeoff documenté, pas une faute. Extraire `SpeedTestRepository` et le `FakeMonitor` partagé seulement si une PR de refactor l'exige. | Détail |
| **STD-02** | `SpeedTestEngine.kt:84-138` (`@Volatile private var cancelled` `:84`, `ensureActive(): Boolean = !cancelled` `:138`, double appel `currentCoroutineContext().ensureActive()` `:103-104,114-115`) | **Middle Man / Speculative Generality** (jugement) : deux mécanismes d'annulation coexistent (drapeau privé + contexte coroutine) alors que le contrat KDoc `:36` dit « suspension = annulation coopérative » et que le repository annule déjà le `Job` (`SpeedTestEngine.kt:333`). Le drapeau est redondant et masque la cause (root cause) ; le moteur réel (`LibreSpeedClient`) annule par `Call.cancel()` + `ensureActive`, pas par drapeau. Supprimer le drapeau du `Fake` au profit du contexte seul. | Faible |
| **STD-03** | `LibreSpeedClient.kt:131-167` (`upload` : un seul `onProgress(maxBytes)` terminal `:166`) vs `download` (`:111-118` : `onProgress(counted)` tous les 64 KiB) | **Inconsistent Abstraction / Duplicated Code** (jugement) : la granularité de progression est incohérente entre les phases — download en flux, upload en un seul coup. Conséquences : le backstop repository ne peut pas réagir en cours d'upload (il ne voit que l'événement final), et la jauge UI saute de download à upload sans progression intermédiaire. Le corps reste borné par `contentLength`, donc pas une violation de plafond ; aligner `upload` sur le même échelonnement par chunk (ex. `sink.write` par 64 KiB + `onProgress` incrémental) restaurerait l'uniformité du contrat `SpeedTestState.Running`. | Faible |
| **STD-04** | `LibreSpeedClient.kt:95` `garbage.php?ckSize=1` | **Mysterious Name / Magic Number** (jugement) : `1` n'est ni nommé ni justifié, alors que RESEARCH §7.2 recommande 2–5 (granularité <1 Mio) et doc PHP 4–1024. Extraire `private const val CK_SIZE = 1` avec commentaire de calibration. Voir aussi Spec SPEC-05 (hors fourchette doc mais accepté par le PHP officiel). | Détail |

---

## 3. Revue code — axe Spec

> Spec = Mission 12 (`MISSION_12_SPEEDTEST_RESEARCH.md`, `MISSION_12_IMPLEMENTATION_REPORT.md` §4)
> + Mission 13 (`MISSION_13_LIBRESPEED_RESEARCH.md` §7, `MISSION_13_ARCHITECTURE.md` §5–§13) +
> `MISSION_13_WIFI_ROUTING.md` (liaison Wi-Fi). Chaque ligne non satisfaite est une exigence
> manquante ; un écart de formulation est cité avec sa source.

| ID | Exigence | État dans le code | Verdict |
|---|---|---|---|
| **SPEC-01** | Plafonds « 20 Mo / 10 Mo / 30 s » (`SpeedTestPolicy.kt:15-17`) + `capped=true` ↔ plancher « ≥ X » (`SpeedTestCalculator.isCapped` `:33-36`, UI `SpeedTestPresentation.kt:66-70` `formatMbpsCapped`, `SpeedTestScreen.kt:431-437` `gaugeRateText`) | `SpeedTestPolicy.kt:15-17` exacts, `init` `:24-32` cohérent. `LibreSpeedEngine.kt:100-101` `capped = isCapped(...)`, UI préfixe « ≥ » + phrase « valeur minimale » (`SpeedTestScreen.kt:333`). Compteur en flux à l'octet près (`LibreSpeedClient.kt:99-118,136-147`). | **Conforme** |
| **SPEC-02** | Protocole minimal RESEARCH Rec §7 : `GET garbage.php?ckSize&r` (DL), `POST empty.php` (UL), `GET empty.php ×5` (ping), **jamais** `getIP.php`/`telemetry.php`, formule `Mbps = octets×8/s/1e6` sans 1.06, latence **médiane de 5 sondes** (jamais min) | Seuls `empty.php`/`garbage.php` construits (`LibreSpeedClient.kt:65,95,153`). `LibreSpeedEngine.kt:62-66` médiane via `SpeedTestCalculator.medianLatencyMillis` (5 sondes). `SpeedTestCalculator.kt:14-18` ×1.0, pas de 1.06. Divergence LibreSpeed-min vs WiFiAdmin-médiane arbitrée (RESEARCH §3.2, conservée). **`ckSize=1`** (`LibreSpeedClient.kt:95`) vs RESEARCH §7.2 « 2–5 » et doc PHP « 4–1024 » : le PHP officiel accepte `1` (`getChunkCount()` : `≤0 → 4`, `>1024 → 1024`, sinon valeur), mais la valeur est hors fourchette documentée — à justifier ou passer à 2. | **Conforme sauf `ckSize` hors fourchette doc (voir SPEC-05)** |
| **SPEC-03** | Backstop indépendant + timeouts branchés + redirections jamais suivies (ARCH §6.2/§6.5/§7.3) | Backstop wrapper présent (`SpeedTestEngine.kt:268-283`, test `SpeedTestRepositoryBackstopTest.kt:46-60`). Timeouts branchés (`SpeedTestEngineFactory.kt:25-28` depuis `SpeedTestPolicy.kt:19-22`). `followRedirects=false` (`SpeedTestEngineFactory.kt:29-30`) + `!=200 → ServerUnavailable`. Compteur en flux ne fait jamais confiance à `Content-Length`. | **Conforme** |
| **SPEC-04** | « Un test ne démarre que sur `LocalNetworkStatus.LocalWifi`, annulé sur perte/changement d'`identity.key`, aucun fallback mobile, aucun retry » (ARCH §1.2) | Portes `:204-207` + `:260-267` + `watchNetworkLoss` `:341-358` + refus cellulaire `WifiLinkInfoProvider.kt:91`. Couvert par `SpeedTestEngineTest.kt:101-109` « no mobile fallback ever ». **Liaison physique** (`socketFactory`+`Dns` par run, WIFI_ROUTING §2) non implémentée — voir SPEC-06. | **Conforme logique, liaison physique en attente** |
| **SPEC-05** | `ckSize` « 2–5 » (RESEARCH §7.2) et doc PHP « 4–1024 » | `ckSize=1` (`LibreSpeedClient.kt:95`). Accepté par le PHP officiel (`garbage.php` : `getChunkCount()`), granularité affûtée (<1 Mio de dépassement potentiel), mais en dehors des deux fourchettes citées. Soit documenter le choix dans le code, soit passer à 2 pour se conformer à RESEARCH. | **Partiellement non conforme (fourchette)** |
| **SPEC-06** | Liaison Wi-Fi exclusive des sockets sans `bindProcessToNetwork` (WIFI_ROUTING §2, ARCH §6.6 point ouvert fermé par la proposition) | `SpeedTestEngineFactory.kt:24-31` : aucun `socketFactory`/`dns` imposé, aucune `WifiNetworkRouteProvider`. `LibreSpeedEngine` n'apporte aucun routeur. La proposition R1→R4 n'est implémentée nulle part (aucun `WifiNetworkRouteProvider.kt` dans `data/speedtest/`). La propriété « aucun octet par le cellulaire si le Wi-Fi disparaît » reste **réactive** (annulation), pas **préventive** (bind). | **Manquant — bloquant pour le trafic réel (décision R1→R4)** |
| **SPEC-07** | Désactivé par défaut sans endpoint, bandeau « vers `<hôte>` », consentement explicite avant le premier test (ARCH §10) | `SpeedTestEngineFactory.kt:22-23` → `Disabled` si `parse()==null` ; `WifiAdminApplication.kt:127-128` lit `BuildConfig.SPEEDTEST_BASE_URL` (vide par défaut `app/build.gradle.kts:20`). `SpeedTestViewModel.kt:132-139` : `start()` exige `hasConsented`, sinon dialogue. `SpeedTestPresentation.kt:125-142` : `providerBannerText` + `consentServerLine` avec `vers <hôte> (HTTPS)`, `sanitizeHostLabel` `:90-105` refuse IP littéra­les/credentials. | **Conforme** |
| **SPEC-08** | `router-core` intact, aucun `cmd`/`Zlt*` dans le moteur (ARCH §2/§13) + opaque `usage` (`INTERNET`/`ACCESS_NETWORK_STATE`/`ACCESS_LOCAL_NETWORK`, zéro permission de localisation) | Grep 0 hit ZLT dans `data/speedtest/` + `Mission12BoundaryTest.kt:20-39` vert. `AndroidManifest.xml:10-17` sans `ACCESS_FINE_LOCATION`/`ACCESS_WIFI_STATE`. | **Conforme** |

---

## 4. Points needs_validation (sans sévérité)

> En accord avec le skill `security-audit` : un `needs_validation` nomme un fait bloquant externe
> à la source, jamais une sévérité. Aucun de ces points n'est une vulnérabilité confirmée.

| ID | Fait manquant | Plan de validation sûr (sans réseau, sans PoC vers Internet) |
|---|---|---|
| **NV-01** | Actualité CVE/volatilité d'OkHttp `4.12.0` (`gradle/libs.versions.toml:5`) — la source ne contient ni lockfile ni rapport de vulnérabilité. | Contrôle offline au bump contre lockfile/coverage locale ; aucun appel distant. `implementation(libs.okhttp)` et `testImplementation(libs.mockwebserver)` épinglés à la même `4.12.0` (`app/build.gradle.kts:71,89`) — vérifier qu'aucune autre version ne fuit dans l'APK release (vérifier le graphe de dépendances offline). |
| **NV-02** | Exactitude du plafond à `N` flux parallèles si `N>1` est introduit (ARCH §6.4 N≤4) : course lecture→incrément permettant un dépassement de `N×buffer`. Aujourd'hui `N=1`, donc non déclenchable. | T4 avec serveur servant 100 Mo en `N` flux : `downloadBytes == maxDownloadBytes` à l'octet près, avec compteur atomique (`compareAndSet` / borne avant ajout, `AtomicLong`, pas de `long` partagé mutable). Test JVM MockWebServer loopback uniquement. Ne pas introduire `N>1` sans ce test. |
| **NV-03** | Migration cellulaire d'un socket déjà ouvert (WIFI_ROUTING §4 TOCTOU) : entre la revalidation `LocalWifi` (`SpeedTestEngine.kt:260`) et le `connect`, le `Network` peut disparaître ; la propriété garantie est « aucun octet par un autre réseau », pas « le Wi-Fi ne disparaît jamais ». Sans le bind physique (SPEC-06), seul l'annulation réactive est prouvée. | Recette appareil (endpoint opérateur, jamais un serveur public) : couper le Wi-Fi mid-run, vérifier `Cancelled` avant ouverture d'un nouveau flux et zéro octet cellulaire compté. Toute reproduction reste loopback/MockWebServer en JVM ; la preuve OEM (`Network.socketFactory` réel) exige un appareil, jamais un test automatisé vers Internet. |
| **NV-04** | Exécution locale future : toute reproduction relèverait des contrôles « Universal execution safety » du skill (loopback isolé, env vide, limites CPU/mémoire/temps, cible en lecture seule). | T4/T8 restent documentaires dans cette revue. Si exécution ultérieure : loopback MockWebServer `127.0.0.1` seul, fixture dummy, sans dépendance externe, sandbox décrite en tête de plan si applicable. |
| **NV-05** | Licence LGPL-3.0 : théorie « réimplémentation du protocole ≠ copie » (RESEARCH §1) — le code embarque l'implémentation du protocole, pas le JS/PHP LGPL. | Faire valider par un juriste avant livraison ; interdiction de joindre `speedtest*.js`/`backend/*.php` à l'APK. Aucun appel réseau requis pour ce contrôle. |
| **NV-06** | Backend LibreSpeed sans auth/rate-limit (RESEARCH §5, § Risques) : `garbage.php`/`empty.php` n'ont ni auth ni rate-limit dans le PHP officiel. Une URL publique devient une cible d'amplification même si l'app ne référence jamais l'URL. | Reverse-proxy obligatoire dès l'ouverture (rate-limit par IP, quota bande passante, WAF, suppression de `results/`, `telemetry_level=none`, certificat HTTPS valide, limites POST 128 Mo côté nginx/Apache/IIS). Validation côté opérateur, jamais côté app. |
| **NV-07** | Persistance/purge de `speedtest_base_url` si la couche B (Réglages, ARCH D1) est retenue — aujourd'hui seule la source A (`BuildConfig`) existe, aucune clé DataStore `speedtest_base_url`. | Stocker hôte+chemin uniquement (pas de query, pas de fragment), inclure la clé à la purge existante de `SpeedTestHistoryStore` (même extension `allowBackup=false`) ; tester le cycle logout → clé absente. |
| **NV-08** | Bandeau « vers `<hôte>` » + mention d'exposition d'IP : risque d'affichage d'un `hostLabel` partiellement issu d'une URL rejetée ou de credentials. Code : `WifiAdminApplication.kt:127-128` lit `BuildConfig` brut, `SpeedTestEngineFactory.create` valide **avant** tout affichage ; `LibreSpeedEngine.kt:32` `providerLabel = "LibreSpeed — vers ${endpoint.hostLabel}"` (host issu du validateur re-parsé, jamais de la chaîne brute). `SpeedTestPresentation.kt:90-105` `sanitizeHostLabel` refuse IP littéra­les, `@`, ports, underscores. | T9 déjà couvert par `SpeedTestPresentationTest` ; compléter par un test d'injection `user@`/`token=` → message générique sans journalisation (jamais de log de la chaîne fautive). |

---

## 5. Récapitulatif de sortie

**Conforme dès maintenant (confirmé source) :**

- Désactivé par défaut sans endpoint (`SpeedTestEngineFactory.kt:22-23`, `WifiAdminApplication.kt:127-128`,
  `app/build.gradle.kts:20`) → zéro octet hors du téléphone.
- TLS + vérification d'hôte par défaut (aucun bypass custom), validation stricte de l'endpoint
  (`SpeedTestEndpoint.kt:29-56` : schéma, `userInfo`/`@`, toute query, fragment, re-parse identité),
  `followRedirects=false` (`SpeedTestEngineFactory.kt:29-30`), 3xx → `ServerUnavailable`.
- Protocole minimal : seuls `empty.php`/`garbage.php` (`LibreSpeedClient.kt:65,95,153`),
  jamais `getIP.php`/`telemetry.php`, jamais d'identifiant stable, zéro log, zéro secret persisté.
- Plafonds durs **20 Mo / 10 Mo / 30 s** comptés en flux sans `Content-Length`
  (`LibreSpeedClient.kt:99-118,136-147`, `SpeedTestPolicy.kt:15-17`) + `withTimeout(30 s)`
  (`SpeedTestEngine.kt:268`) + backstop rapport (`:268-283`, testé).
- Annulation totale (`Call.cancel()` + `ensureActive()` par buffer 64 KiB, `cancel()` sous verrou,
  `finally` génération-gardé, `onCleared`), fermeture `use {}` systématique (`LibreSpeedClient.kt:71,101,158`).
- Wi-Fi strict sans repli cellulaire (`SpeedTestEngine.kt:204-207,260-267,341-358`,
  `WifiLinkInfoProvider.kt:91`), isolation ZLT (grep 0 hit + `Mission12BoundaryTest` vert),
  zéro log / zéro secret / zéro WebView.

**À corriger / durcir avant le premier trafic réel :**

1. CORR-01 (Low) : assertion de schéma par requête dans `LibreSpeedClient` pour compenser
   `usesCleartextTraffic="true"` (défense en profondeur HTTPS).
2. CORR-02 (Low) : `allowHttpLoopback=false` par défaut en production, restriction des ports,
   rejet `%2e`/`%2f` dans le chemin.
3. CORR-03 (Low) : garde-fous frontière (`getIP`/`telemetry`/`isp`/`X-Forwarded-For` interdits,
   allowlist `garbage.php`/`empty.php`).
4. CORR-05 / SPEC-05 : jauge >1000 Mbps + `ckSize` 1 vs 2–5 (aligner code, test et commentaire).
5. SPEC-06 (NV) : implémenter `WifiNetworkRouteProvider` (R1→R4 de WIFI_ROUTING) ou acter
   explicitement le risque TOCTOU réactif — bloquant pour tout trafic réel vers un endpoint opérateur.
6. Commentaire du backstop (`SpeedTestEngine.kt:270-273`, CORR-04) : aligner la promesse sur la portée réelle.

### Findings triés par sévérité (sécurité, guidance)

1. **Low** — CORR-01 : `usesCleartextTraffic="true"` globale (`AndroidManifest.xml:27`),
   HTTPS restant applicatif uniquement (compensations en place).
2. **Low** — CORR-02 : exception loopback non paramétrable + ports quelconques + encodage `..`
   non détecté (`SpeedTestEndpoint.kt:43-48`).
3. **Low** — CORR-03 : `Mission12BoundaryTest` ne garde ni les chemins allowlist ni les
   littéraux interdits (`getIP`, `telemetry`) (`Mission12BoundaryTest.kt:55-62`).
4. **Informational** — CORR-04 : commentaire du backstop sur-promettant sa portée
   (`SpeedTestEngine.kt:270-273`) ; la garantie fil vient du client (voir §1.2).
5. **Informational** — CORR-05 : jauge `gaugeScaleMaxMbps` sous la valeur au-delà de 1000 Mbps
   (`SpeedTestCalculator.kt:38-43`), hérité Mission 12.

Axes code (non fusionnés, `code-review`) : Standards — 4 jugements dont aucun dur
(documentés), le plus notable (STD-02 double mécanisme d'annulation du `Fake`,
STD-03 progression upload en un seul événement) ; Spec — 8 exigences : 6 conformes
(SPEC-01/02/03/04/07/08), 2 partiellement conformes (SPEC-05 `ckSize`, SPEC-06 liaison
physique en attente).

**Points needs_validation (sans sévérité)** : NV-01 actualité OkHttp 4.12.0, NV-02 exactitude
du plafond en `N` flux (aujourd'hui `N=1`, non déclenchable), NV-03 migration cellulaire
d'un socket ouvert (TOCTOU), NV-04 sandbox d'exécution pour T4/T8, NV-05 validation LGPL
par un juriste, NV-06 reverse-proxy/rate-limit du backend opérateur, NV-07 persistance/purge
de `speedtest_base_url` (couche B non implémentée), NV-08 construction du `hostLabel` (déjà
re-parsée, test d'injection à compléter).

*Contenu de ce document : aucun secret, aucune adresse IP publique, aucun SSID/MAC, aucune
donnée d'utilisateur. Revue indépendante de la propension du projet — aucun fichier de
production n'a été modifié, aucun code cible exécuté.*
