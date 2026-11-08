# Mission 13 — Recherche LibreSpeed et protocoles de mesure

> Date : 08/11/2026 · Mission 13 « Recherche LibreSpeed et protocoles de mesure » pour WiFi Admin.
> Méthode : lecture de sources primaires uniquement (dépôt officiel `librespeed/speedtest`, fichiers `backend/*.php`, `speedtest_worker.js`, `doc.md`, `LICENSE`, `README.md`, `results/telemetry*`, `server-list.json`, dépôts `speedtest-cli` et `speedtest-android` cités par le dépôt principal). Aucun endpoint de mesure n'a été appelé pendant la recherche — aucun `garbage.php`, `empty.php`, `getIP.php` ni `telemetry.php` contacté.
> Statuts : **Observé** = constaté dans le code / la doc citée. **Inféré** = déduction à partir d'une absence ou d'une combinaison. **Inconnu** = non trouvé dans les sources examinées.
> Contexte local : `docs/MISSION_12_SPEEDTEST_RESEARCH.md`, `docs/MISSION_12_IMPLEMENTATION_REPORT.md`, `app/src/main/java/dev/wifiadmin/app/data/speedtest/*.kt` (`SpeedTestPolicy.kt` : plafonds 20 Mo / 10 Mo / 30 s, `SpeedTestCalculator.kt`, `SpeedTestEngine.kt` : `DisabledSpeedTestEngine`).

## 1. Licence LibreSpeed

| Élément | Fait | Source (URL + fichier) |
|---|---|---|
| Client navigateur (JS) | GNU LGPL v3 « or any later version », copyright 2016-2024 Federico Dossena ; « you can redistribute it and/or modify it under LGPLv3 » ; aucune garantie | https://github.com/librespeed/speedtest — `LICENSE` ; `README.md` § License |
| Backend PHP (`backend/`, `results/`) | Même licence LGPL-3.0 (dépôt unique) ; `doc.md` : « This software is under GNU LGPL v3 or newer », redistribution libre, modifications de la bibliothèque sous LGPL | https://github.com/librespeed/speedtest — `LICENSE` ; `doc.md` § License |
| Client CLI (Go) | LGPL v3.0 | https://github.com/librespeed/speedtest-cli — `LICENSE` ; `README.md` § License |
| Client Android (template) | « LGPL-3.0, same as the rest of the LibreSpeed project » | https://github.com/librespeed/speedtest-android — `README.md` § License ; `LICENSE` |
| Télémétrie | Fonction intégrée au dépôt principal, décrit comme optionnelle ; sous même LGPL | https://github.com/librespeed/speedtest — `README.md` § Features (« Telemetry (optional) ») ; `results/telemetry.php` |

**Observé.** La licence couvre l'ensemble du dépôt principal, y compris `backend/*.php` et `results/*`. Le fichier `LICENSE` dispose le texte complet LGPL-3.0 FSF.
**Inféré.** Réimplémenter le *protocole* HTTP (GET/POST vers des chemins `/garbage.php`, `/empty.php`) dans un client Kotlin/OkHttp ne copie aucun code LGPL — ce n'est pas de la redistribution de la bibliothèque. En revanche, embarquer `speedtest.js`/`speedtest_worker.js` ou y copier du code JS/PHP serait une œuvre combinée LGPL : conserver l'avis de copyright et le texte LGPL, publier les modifications de la bibliothèque sous LGPL, et ne pas rendre l'application entière LGPL si le client natif ne lie pas le code. Point à faire valider par un juriste avant livraison (voir § Risques).

## 2. Protocole backend exact (PHP)

Inventaire **Observé** : le répertoire `backend/` dans la branche `master` ne contient que `garbage.php`, `empty.php`, `getIP.php`, `getIP_util.php`, `getIP_ipInfo_apikey.php`, `country_asn.mmdb` et `geoip2.phar` (API GitHub `repos/librespeed/speedtest/contents/backend`). Il n'y a ni `ping.php` nor `getIP.php?d=...` — `ping` est un rôle de `empty.php`, pas un fichier dédié.

### 2.1 Endpoints, paramètres, formats

| Endpoint (rôle) | Méthode & paramètres **Observés** | Format / headers | Source |
|---|---|---|---|
| `garbage.php` — download | `GET ?ckSize=<int> &r=<random> [&cors=true]`. `getChunkCount()` : absent / non-numérique / ≤0 → 4 ; >1024 → 1024 ; sinon valeur | `HTTP/1.1 200 OK`, `Content-Type: application/octet-stream`, `Content-Disposition: attachment; filename=random.dat`, `Cache-Control: no-store, no-cache, must-revalidate, max-age=0, s-maxage=0`, `Pragma: no-cache`, compression désactivée ; corps : `ckSize × 1 048 576 octets` de `openssl_random_pseudo_bytes(1048576)` (données incompressibles), émis par `echo`/`flush()` | https://github.com/librespeed/speedtest — `backend/garbage.php` ; `doc.md` § Implementation details / `backend` files : `ckSize` « 4-1024 » |
| `empty.php` — ping & upload | Ping : `GET ?r=<random> [&cors=true]` ; Upload : `POST ?r=<random> [&cors=true]` avec corps blob (voir §3) ; MPOT : POST vide de préchargement CORS | `HTTP/1.1 200 OK`, corps **vide (0 octet)**, `Connection: keep-alive`, `Cache-Control: no-store…`, `Pragma: no-cache` | https://github.com/librespeed/speedtest — `backend/empty.php` ; `speedtest_worker.js` : `url_ping`, `url_ul` |
| `getIP.php` — IP / ISP / distance | `GET ?r=<random> [&isp=true] [&distance=km|mi] [&cors=true]` | Sans `isp` : JSON `{"processedString":"<ip>","rawIspInfo":""}` ; avec `isp` : `processedString = "<ip> - <ISP>, <pays> (<distance>)"` et `rawIspInfo` = JSON brut ipinfo.io (ou base hors-ligne). Headers `Content-Type: application/json; charset=utf-8`, anti-cache | https://github.com/librespeed/speedtest — `backend/getIP.php` ; `doc.md` § `getIP.php` |
| `getIP_util.php` — aide détection IP | Pas un endpoint (inclus par `getIP.php`) ; priorité : `CF-Connecting-IPv6` → `CLIENT_IP` / `X-Real-IP` / `X-Forwarded-For` → `REMOTE_ADDR` | Validation `FILTER_VALIDATE_IP`, prise du premier élément d'une liste XFF, suppression du préfixe `::ffff:` | https://github.com/librespeed/speedtest — `backend/getIP_util.php` |
| `results/telemetry.php` — télémétrie | `POST multipart/form-data` : `ispinfo` (JSON `{processedString,rawIspInfo}`), `extra`, `dl`, `ul`, `ping`, `jitter`, `log` | Réponse texte `id <id>` (ex. `id 123`). En cas d'échec `exit(1)` sans corps utile | https://github.com/librespeed/speedtest — `results/telemetry.php` ; `speedtest_worker.js` : `sendTelemetry()` |

**Points d'attention protocolaires Observés :**
- Paramètre `d` : **non observé** dans le backend PHP ni le worker. Le paramètre de distance est `distance=km|mi`. Tout paramètre `d` rencontré dans d'autres backends (Rust/Go) est donc **Inféré** comme variante d'implémentation tiers, non normatif pour le PHP officiel.
- Paramètre `r` : toujours ajouté par le worker (`"r=" + Math.random()`) comme anti-cache — à reproduire côté Kotlin (UUID ou `Random.nextLong()`), sinon un proxy/cache peut servir une réponse en cache.
- Paramètres `ckSize` : doc dit 4–1024 ; code PHP borne à 1024 (par défaut 4) et le worker demande par défaut `garbagePhp_chunkSize = 100` (soit 100 Mio par requête). `doc.md` recommande ≥10, maximum 1024.
- `Content-Encoding: identity` posé par le worker sur l'upload pour empêcher la compression (données incompressibles).
- `Accept-Encoding: identity` posé par le CLI sur download/upload (voir `defs/server.go`).

### 2.2 Erreurs

| Cas | Comportement **Observé** |
|---|---|
| Serveur sain | `garbage.php` / `empty.php` répondent **toujours 200**. Pas de JSON d'erreur. |
| Corps ping non vide | Le CLI considère le backend HS : `IsUp()` exige `StatusCode == 200` **et** `len(body) == 0` (`defs/server.go`) ; sinon serveur exclu. |
| Oversize POST | Le serveur web renvoie **HTTP 413** si la limite de taille est trop petite — la doc exige `client_max_body_size 128m` (nginx), `LimitRequestBody 134217728` (Apache), `maxAllowedContentLength 134217728` (IIS) | `doc.md` § Installation « Web server upload limit », § Troubleshooting « Upload test is inaccurate » |
| PHP sans OpenSSL | `garbage.php` échoue (absence d'`openssl_random_pseudo_bytes()`) — symptôme documenté « download gives very low result » | `doc.md` § Troubleshooting |
| Connexion / timeout / interruption | Le worker passe en `Fail` (`dlStatus`/`ulStatus`/`pingStatus = "Fail"`) si `xhr_ignoreErrors=0` ; sinon redémarre ou ignore selon `xhr_ignoreErrors` (défaut 1) | `speedtest_worker.js` : `xhr_ignoreErrors`, `dlTest`/`ulTest`/`pingTest` |
| Télémétrie HS | `sendTelemetry()` logue `TELEMETRY ERROR <status>` et retourne `null` sans faire échouer le test | `speedtest_worker.js` : `sendTelemetry()` |
| ISP introuvable | `getIP.php` retombe sur IP seule (sans ISP), puis base hors-ligne, puis IP brute | `backend/getIP.php` |

### 2.3 CORS

**Observé.** Les en-têtes CORS ne sont envoyés que si `?cors=true` est présent : `Access-Control-Allow-Origin: *`, `Access-Control-Allow-Methods: GET, POST` (et `Access-Control-Allow-Headers: Content-Encoding, Content-Type` sur `empty.php`). Le worker n'ajoute `cors=true` qu'en mode MPOT (`mpot: true`). Pour un client natif Kodi/OkHttp — i.e. Kotlin/OkHttp — CORS est hors navigateur : l'en-tête n'est pas requis mais sans danger à envoyer. Le mélange HTTP/HTTPS est interdit : « You can't mix HTTP with HTTPS… For HTTPS, all your servers must have valid certificates » (`doc.md` § Multiple servers).

## 3. Algorithme du client officiel

Deux implémentations officielles coexistent : le **worker JS** (référence navigateur) et le **CLI Go** (référence native). Les mécanismes de base convergent ; les durées et facteurs divergent (points signalés).

### 3.1 Ordre et réglages par défaut (JS)

**Observé** (`speedtest_worker.js`, `settings.json`) : `test_order` par défaut `"IP_D_U"` (`I` = getIP, `D` = download, `U` = upload, `P` = ping+jitter, `_` = 1 s). `settings.json` du dépôt montre un exemple `"ID_U_P"` avec `time_dl_max=12`, `time_ul_max=12`, `time_auto=false`.
Réglages par défaut du worker : `time_dl_max=15 s`, `time_ul_max=15 s`, `time_auto=true`, `count_ping=10`, `xhr_dlMultistream=6`, `xhr_ulMultistream=3`, `xhr_multistreamDelay=300 ms`, `xhr_ul_blob_megabytes=20`, `garbagePhp_chunkSize=100`, `time_dlGraceTime=1.5 s`, `time_ulGraceTime=3 s`, `overheadCompensationFactor=1.06`, `telemetry_level=0` (désactivé), `getIp_ispInfo=true`, `getIp_ispInfo_distance="km"`.

### 3.2 Ping / jitter (JS)

**Observé** (`speedtest_worker.js` : `pingTest()`, `doc.md` § Ping + Jitter) :
- Ce n'est **pas un ping ICMP** mais des `GET` séquentiels sur `empty.php` (avec `r` aléatoire) sur une connexion persistante (`Connection: keep-alive` exigée — sinon « ping shows double », `doc.md` § Troubleshooting).
- Mesure = différence `Date.now()` entre envoi et `onload`, ou `performance.getEntries() → responseStart - requestStart` si `ping_allowPerformanceApi=true` (désactivé sur Firefox).
- `count_ping=10` (recommandé ≥3, <30). Après le 1er pong, `ping = min` des échantillons ; `jitter` = moyenne pondérée des `|inst - prev|` (`*0.3/*0.7` en hausse, `*0.8/*0.2` en baisse, premier jitter jeté).
- Erreurs selon `xhr_ignoreErrors` : 0 → `Fail`, 1 → réessaie, 2 → ignore.

**Divergence avec Mission 12 (à trancher) :** LibreSpeed prend le **minimum** ; WiFi Admin (`SpeedTestCalculator.medianLatencyMillis`) prend la **médiane de 5 sondes**. La médiane est plus robuste et déjà spécifiée dans Mission 12 — ne pas « corriger » vers le min LibreSpeed.

### 3.3 Download (JS)

**Observé** (`speedtest_worker.js` : `dlTest()`) :
- `xhr_dlMultistream` flux `GET garbage.php?r=…&ckSize=100`, décalés de 300 ms ; chaque flux se relance dès `onload` (boucle continue) ; comptage par `onprogress` : `loadDiff = event.loaded - prevLoaded`, total `totLoaded`.
- Tick toutes les **200 ms**. Pendant `time_dlGraceTime=1.5 s` (warm-up TCP window) : pas de mesure ; à la fin, si des données sont reçues, `startT` et `totLoaded` sont réinitialisés (le warm-up est exclu du calcul).
- Ensuite : `speed = totLoaded / (t/1000)` ; si `time_auto=true`, `bonusT += min((5*speed)/100000, 400) ms` tous les 200 ms (raccourcit le test sur les liaisons rapides). Arrêt quand `(t+bonusT)/1000 > time_dl_max (15 s)` ou `failed`.
- Débit affiché : `dlStatus = (speed * 8 * 1.06) / 1 000 000` (Mbps) — soit `octets×8/s/1e6 ×1.06`, facteur empirique de surcoût HTTP/réseau (`doc.md` : valeurs alternatives 1.0513, 1.0369, `1514/1460`, `1` si on ignore les surcoûts). RAM jusqu'à 500 Mo sur liens très rapides (`doc.md` § Introduction).

### 3.4 Upload (JS)

**Observé** (`speedtest_worker.js` : `ulTest()`) :
- `xhr_ulMultistream=3` flux `POST empty.php?r=…`, corps = blob aléatoire de `xhr_ul_blob_megabytes=20 Mo` (incompressible, `Uint32Array` + `Math.random() * 2^32`), décalés de 300 ms ; en-tête `Content-Encoding: identity`.
- Même boucle : `upload.onprogress` → `totLoaded` ; `onload` → relance. Grace `time_ulGraceTime=3 s` (remplissage des buffers), tick 200 ms, même `time_auto` bonus, même formule `Mbps = speed*8*1.06/1e6`, `time_ul_max=15 s`.
- Cas particuliers : détection `xhr.upload` (IE11 workaround : petits blobs 256 Ko comptés sur `onload`, implémenté sur Edge/PlayStation 4 par User-Agent) ; sur Chromium mobile, blob forcé à 4 Mo (limite ~v65) ; en MPOT, un `POST` vide préalable charge les en-têtes CORS.
- Arrêt anticipé identique au download ; `abort()` coupe tous les XHR, vide les statuts, `testState=5`.

### 3.5 Équivalent natif (CLI Go)

**Observé** (`speedtest-cli` — `defs/server.go`, `defs/bytes_counter.go`, `speedtest/helper.go`, `README.md`) :
- `Download()` : `concurrent=3` flux par défaut, `chunks=100` (= `ckSize`), `duration=15 s`, espacement 200 ms, `Accept-Encoding: identity`, comptage atomique `BytesCounter` via `io.TeeReader` ; canal `downloadDone` relance un flux terminé ; `context.WithCancel` coupe au timeout. `AvgMbps = total_octets / elapsed_s / 125000` (ou `131072` en mébibits) — soit `octets×8/s/1e6`, **sans** facteur 1.06, ce qui diffère du worker JS d'environ 6 %.
- `Upload()` : payload pré-alloué aléatoire (ChaCha8 `rand/v2`) de `upload-size=1024 KiB` par requête, `Content-Length` fixe (évite `Transfer-Encoding: chunked`, incompatible avec certains serveurs Rust), même structure concurrente 3 flux / 15 s.
- `PingAndJitter()` (TCP) : `count=10` `GET pingURL`, **jette le 1er échantillon** (handshake), moyenne arithmétique (`getAvg`) et jitter pondéré `0.7/0.3` ou `0.8/0.2`. `ICMPPingAndJitter()` via `pro-bing` (ICMP) avec repli TCP ; `--no-icmp` force le TCP. Le `IsUp()` vérifie `GET pingURL → 200 + corps vide`.
- Timeout HTTP global 15 s (`--timeout`), dialer 30 s ; télémétrie `basic` par défaut en CLI (opt-out par `--telemetry-level disabled`) — inversion du défaut navigateur (`none`).

**Inféré pour Kotlin** : le protocole HTTP est identique (endpoints + `ckSize` + `r`) ; seules la parallélisme, le warm-up et le facteur de surcoût varient. Choisir un socle commun et documenter l'écart de ~6 % si on suit le worker (1.06) vs la formule WiFi Admin (×1.0).

## 4. Serveurs publics

| Fait | Source |
|---|---|
| Il n'y a pas de liste figée « officielle et garantie » dans le dépôt : `server-list.json` est un exemple embarqué (38 entrées — Amsterdam, Bangalore, Johannesburg, etc.) avec champs `name`, `server`, `dlURL`, `ulURL`, `pingURL`, `getIpURL`, `id`, `sponsorName`, `sponsorURL` | https://github.com/librespeed/speedtest — `server-list.json` |
| Le CLI et l'Android chargent une liste **distante** : CLI `serverListUrl = https://librespeed.org/backend-servers/servers.php` (avec repli `/.well-known/librespeed`) ; Android : « bundled snapshot of the public server list is used as fallback when the remote list cannot be loaded » + serveurs personnalisés auto-hébergés | https://github.com/librespeed/speedtest-cli — `speedtest/speedtest.go` ; https://github.com/librespeed/speedtest-android — `README.md` § Features |
| Sources de la liste : opérateurs tiers sponsorisés (Clouvider, HOSTAFRICA, DigitalOcean, CESNET, etc.) — chaque entrée porte un `sponsorName`/`sponsorURL` | `server-list.json` (même source) |
| Aucune politique d'usage, quota, taux ou SLA documentée n'a été trouvée dans les sources primaires examinées (`LICENSE`, `README.md`, `doc.md`, `PRIVACY.md`, `SECURITY.md`) | **Inconnu** — à vérifier sur `librespeed.org` (page non consultée ici) avant toute utilisation tiers |

**Pourquoi ne jamais coder un endpoint public en dur (Inféré à partir des faits Observés) :**
1. **Aucune autorisation** : ces backends sont des machines de tiers. La licence LGPL couvre le *code*, pas la bande passante de l'opérateur. Les utiliser dans une app sans accord = consommation de la bande passante d'autrui.
2. **Instabilité** : liste community-maintained, sponsors qui entrent/sortent, schémas `//` ambigus (HTTP par défaut côté CLI sans `--secure`, HTTPS requis côté navigateur moderne) — un serveur mort casse le test.
3. **Confidentialité** : tout appel révèle l'IP publique du client à l'opérateur, et `getIP.php` + télémétrie peuvent la stocker (voir §5).
4. **Biais géographique** : aucune entrée au Niger / Afrique de l'Ouest dans `server-list.json` — le chemin serait international (Europe/Amérique du Nord), avec latence et congestion. La page publique `librespeed.org` est un service web, pas une API d'app.
5. **Sécurité** : aucun contrôle de la configuration du serveur tiers (telemetry_level, `redact_ip_addresses`, plan B TLS).

## 5. Auto-hébergement

### 5.1 Prérequis Observés

| Prérequis | Fait | Source |
|---|---|---|
| Serveur web | « reasonably fast web server with Apache 2 (nginx, IIS also supported) » ; les backends de test doivent accepter de gros POST (≳20 Mo) | https://github.com/librespeed/speedtest — `README.md` § Server requirements ; `doc.md` § Installation |
| PHP | « PHP 5.4 or newer (other backends: Go, Rust, Node branche) », 8.0 requis pour ISP/distance ; 64-bit « strongly recommended » ; OpenSSL + module PHP OpenSSL | `doc.md` § Single server ; `README.md` § Server requirements |
| Base (optionnel) | MariaDB/MySQL, PostgreSQL, MSSQL (Windows) ou SQLite + module PDO ; script SQL dans `results/` | `doc.md` § Installation ; `results/telemetry_settings.php` |
| Partage d'image (optionnel) | FreeType 2 + module gd | `doc.md` § Installation |
| Permissions | Écriture / exécution dans le dossier d'installation | `doc.md`, `README.md` § Installation |
| Docker | Image hebdomadaire (patches PHP, base GeoIP mise à jour) | `README.md` § Docker |

### 5.2 TLS, CORS, sécurité, anti-abus

| Suj | Fait | Statut |
|---|---|---|
| TLS | Pour HTTPS : « all your servers must have valid certificates » ; autorité de certification valide exigée côté navigateur. En natif, même exigence métier (WiFi Admin impose HTTPS). Limites nginx/Apache/IIS ci-dessus à configurer **avant** l'upload `<=20 Mo`. | **Observé** — `doc.md` § Multiple servers, § Web server upload limit |
| CORS | En-têtes seulement sur demande `cors=true` ; inutiles pour OkHttp mais à garder `?cors=true` si une WebView ou un front JS consomme le même backend. | **Observé** — `backend/*.php` § CORS headers |
| Auth sur endpoints | Aucune authentification, aucun token, aucun rate-limit dans `garbage.php` / `empty.php` / `getIP.php` (code lu en entier). Quiconque connaît l'URL peut consommer le serveur. | **Inféré** de l'absence dans le code officiel |
| Anti-abus | Non fourni par le dépôt : l'opérateur doit le mettre en place (reverse-proxy / WAF, rate-limit par IP, blocage `empty.php` vs `garbage.php`, quota de bande passante, fail2ban). Défense en profondeur : séparer front et backends, n'exposer que `backend/` des serveurs de test, supprimer `results/` si télémétrie non voulue. | **Inféré** — à valider en exploitation (voir § Risques) |
| Télémétrie désactivable | `telemetry_level=0` (« none ») par défaut dans le worker ; dossier `results/` supprimable si non utilisée (« If you're not using telemetry… you can delete the results folder »). Si activée : `redact_ip_addresses=true` (masque IPv4/IPv6/hostname), `enable_id_obfuscation=true` (requiert PHP 64-bit), mot de passe `stats_password` par défaut à changer, politique de confidentialité à personnaliser (RGPD). | **Observé** — `doc.md` § Telemetry and results sharing, § Privacy ; `results/telemetry_settings.php` ; `speedtest_worker.js` |
| IP/ISP | `getIP.php` interroge ipinfo.io (base hors-ligne `country_asn.mmdb` « CC-BY-SA 4.0 » par défaut, clé API optionnelle pour la distance). Au minimum en auto-hébergement, l'opérateur garde la base hors-ligne à jour (Docker `latest`). | **Observé** — `doc.md` § ipinfo.io ; `backend/getIP.php` |
| Cost / « no backend » | Sans PHP : fichier incompressible statique (10–100 Mo) + fichier vide — download/upload seuls, sans IP/ISP/télémétrie/MPOT. | **Observé** — `doc.md` § No backend |

## 6. Compatibilité avec les plafonds WiFi Admin (20 Mo / 10 Mo / 30 s)

| Paramètre LibreSpeed | Valeur par défaut (Observé) | Conflit avec WiFi Admin (Inféré) |
|---|---|---|
| `time_dl_max` / `time_ul_max` | 15 s chacun → **30 s** à eux deux | Le budget global WiFi Admin est **30 s pour tout le test** (≈5 s latence + download + upload). Avec les durées officielles, le test réel dépasse le budget avant même la fin upload. D'où `withTimeout(30 s)` de `SpeedTestRepository` déjà en place. |
| `time_auto` (raccourcissement « bonus ») | `true` ; `bonusT += min(5*speed/1e5, 400 ms)` tous les 200 ms | Sur lien rapide, la phase se raccourcit — mais LibreSpeed ne compte **pas** de plafond d'octets : à 100 Mbps, 15 s ≈ 187 Mo ; à 1 Gbps, ≈1.8 Go. Sous plafond 20 Mo, l'annulation arrive **pendant le grace time** (1.5 s à 100 Mbps = 19 Mo) ; la mesure n'est pas stabilisée ⇒ marquage `capped=true` obligatoire (cf. `SpeedTestCalculator.isCapped`, `SpeedTestPolicy`). |
| Warm-up exclus | DL 1.5 s, UL 3 s écartés (reset `startT`/`totLoaded`) | À 20 Mo, écarter 3 s d'upload laisse <7 s de mesure utile. Recommandation Mission 12 (warm-up 2 s écarté, fenêtres 250 ms) compatible si on compte le warm-up dans le plafond d'octets **et** dans le 30 s — l'alternative (ne pas compter) mentirait sur la consommation prépayée. |
| `garbagePhp_chunkSize` / `xhr_dlMultistream` | 100 (100 Mio/requête) × 6 flux | Une seule requête `ckSize=100` dépasse 5× le plafond download. Il faut ramener `ckSize` à 2–5 (2–5 Mio/requête) et `multistream` à 2–4 (voir §7). |
| `xhr_ul_blob_megabytes` | 20 Mo/requête, × 3 flux | Un seul lot d'upload dépasse le plafond upload 10 Mo. Ramener à 2–5 Mio et recréer le blob par itération (voir §7). |
| `count_ping` | 10 (≥3, <30) | 10 sondes dans le budget 30 s sont faisables (séquentiel, ~RTT×10), mais Mission 12 fixe `latencyProbes=5` — garder 5 et médiane (voir §3.2). |
| `overheadCompensationFactor` | 1.06 (empirique, Internet IPv4 typique) | Si on garde la formule WiFi Admin `Mbps = octets×8/s/1e6` (sans 1.06), les résultats diffèreront d'environ 6 % de LibreSpeed JS. Documenter le choix ; ne pas appliquer 1.06 sans calibration sur le chemin réel. |

**Conclusion de compatibilité (Inféré)** : le protocole LibreSpeed est **compatible** avec les plafonds, à condition de le *dé-configurer* (durées plus courtes, petits chunks, `time_auto=false` ou bonus plafonné, comptage cumulatif incl. warm-up) et de marquer `capped` dès que le plafond est atteint. Tant que le plafond coupe la mesure avant stabilisation, la UI doit afficher `≥ X Mbps` (pourcentage / plancher) plutôt qu'un Mbps précis — déjà le contrat `SpeedTestPolicy.kt` et `SpeedTestCalculator.kt`.

## 7. Spécification pour un client Kotlin / OkHttp sans JavaScript

Objectif : parler le **protocole** (endpoints + `ckSize` + `r`) sans embarquer le worker JS ni `settings.json`. Conception compatible avec `SpeedTestPolicy.kt` et `SpeedTestEngine.kt`.

### 7.1 Configuration (Observé pour les noms, Inféré pour les valeurs)

```kotlin
// Endpoint auto-hébergé configuré par l'utilisateur. Aucun défaut codé en dur.
data class LibreSpeedEndpoint(
    val baseUrl: String,       // ex. https://<hôte auto-hébergé>/backend/
    val dlPath: String = "garbage.php",
    val ulPath: String = "empty.php",
    val pingPath: String = "empty.php",
    val getIpPath: String = "getIP.php", // OPTIONNEL — voir §7.4
)
```

- Règles : URL HTTPS obligatoire, certificat valide (pas de `hostnameVerifier` permissif, pas de fallback HTTP). Base vide → moteur désactivé (`DisabledSpeedTestEngine`, zéro appel — déjà livré). Persistance locale (DataStore) — ne jamais stocker d'IP/MAC/SSID/secret.
- Consommation avant clic « Lancer » : afficher « jusqu'à 20 Mo download + 10 Mo upload en 30 s » (`SpeedTestPolicy`), déjà exigé par Mission 12.

### 7.2 Requêtes (Observé pour URL/paramètres, Inféré pour le cadrage)

| Phase | Requête OkHttp | Contrôle |
|---|---|---|
| Ping (latence) | `GET https://…/empty.php?r=<random>` séquentiel ×5 (Mission 12) ; `Cache-Control: no-cache` ; timeout connect 8 s / read 5 s / call 5 s | Médiane des 5 (jamais min — divergence assumée avec LibreSpeed, voir §3.2) ; 1 échec → phase `Failed` ou `Incomplete` selon `SpeedTestFailure` |
| Download | `GET https://…/garbage.php?r=<random>&ckSize=5[&cors=true]` (2–4 flux parallèles via `OkHttp Dispatcher`, `maxRequests` dédié), lecture en streaming par buffers 64–256 Ko, `Accept-Encoding: identity`, annulation du `Call` au plafond ou au timer | Compteur atomique cumulé (tous flux + retries + warm-up) ; arrêt `Call.cancel()` dès `total >= maxDownloadBytes` ou `elapsed >= durée DL` ; warm-up 1.5 s exclu du calcul mais **compté** dans le plafond |
| Upload | `POST https://…/empty.php?r=<random>` (2–3 flux), corps = blob aléatoire de 1–4 Mio régénéré par itération (chapitre : corps OkHttp avec `Content-Length` fixe — modèle CLI Go), `Content-Encoding: identity` | Même comptage atomique ; arrêt au plafond 10 Mo ou à la durée ; jamais de retry qui dépasserait le cumul |
| getIP (optionnel) | `GET https://…/getIP.php?r=<random>` **sans** `isp` ni `distance` si on a besoin de la confirmation serveur ; sinon **ne pas appeler** (économie d'un aller-retour et aucune IP publique) | Ne jamais stocker/réafficher l'IP (contrat Mission 12 : « no public IP stored ») ; ne pas renvoyer vers `telemetry.php` |
| Télémétrie | **Never** : aucun appel à `results/telemetry.php` ; le backend auto-hébergé doit être déployé sans `results/` (cf. §5) | — |

Paramètres libres alignés sur le worker : `ckSize` petit (2–5) pour que la granularité de dépassement reste < 1 Mio par flux ; `r` unique par requête ; `cors=true` facultatif (inoffensif en natif, utile si une WebView partage le backend).

### 7.3 Comptage, calcul, annulation, timeouts

```
Mbps = totalBytes × 8 / elapsedSeconds / 1 000 000     // SpeedTestCalculator.mbps — sans facteur 1.06
latence = médiane de 5 sondes                            // SpeedTestCalculator.medianLatencyMillis — jamais 0, jamais min/max
plafond atteint avant fenêtre stable ⇒ capped=true      // SpeedTestCalculator.isCapped — UI affiche « ≥ X »
```

- **Comptage** : compteur `AtomicLong` partagé ; `FakeSpeedTestEngine` actuel compte de la même façon (volume fictif) — réutiliser sa structure.
- **Annulation** : `SpeedTestEngine.cancel()` doit fermer chaque `Call` (`call.cancel()`), puis le `Job` coroutine coopératif (`ensureActive()`). Le `SpeedTestRepository` existant (`cancel()` sous verrou, `generation` atomique, `withTimeout(30 s)`) couvre déjà l'annulation utilisateur / perte Wi-Fi / changement d'identité — **ne pas réimplémenter** : brancher le nouvel engine dessus.
- **Timeouts** (déjà déclarés dans `SpeedTestPolicy`, à appliquer à l'`OkHttpClient`) : connect 8 s, read 10 s, write 10 s. En plus, `callTimeout = 30 s` global ; chaque phase bornée par son budget (ex. DL 12 s, UL 12 s, ping 6 s) pour que le total 30 s ne soit pas absorbé par une seule phase.
- **Retry** : `xhr_ignoreErrors=0` pour WiFi Admin (échec net plutôt que relance silencieuse) — toute relance compterait contre le plafond ; donc pas de retry auto. single-flight déjà garanti par le repository.
- **Wifi-gating** : garder le garde-fou Mission 12 — démarrage uniquement sur `LocalNetworkStatus.LocalWifi`, annulation sur perte/changement d'`identity.key` — aucun fallback mobile.
- **Interruptions réseau** : `IOException` / `SocketTimeout` / HTTP ≠ 200 + corps ping non vide → `SpeedTestFailure.ServerUnavailable` / `Timeout` / `Incomplete` (types déjà définis dans `SpeedTestResult.kt`).

## Tableau de décision

| Critère | A. LibreSpeed **auto-hébergé configurable, désactivé par défaut** | B. LibreSpeed **serveur public codé en dur** | C. **Moteur toujours désactivé** (statu quo) | D. Autre (M-Lab/Ookla/CDN) |
|---|---|---|---|---|
| Autorisation / ToS | Aucun droit tiers requis — l'opérateur possède le serveur ; LGPL OK sans embarquer le JS | **Interdit** — bande passante de tiers, aucune autorisation dans les sources | Aucun usage tiers | Mission 12 : Ookla/Fast/Cloudflare interdits sans licence ; M-Lab = IP publiée + quotas |
| Confidentialité | Aucune télémétrie (backend sans `results/`) ; `getIP` évitable ; HTTPS contrôlé | IP publique exposée à un opérateur inconnu ; télémétrie possible sur le serveur tiers | Aucune exposition | M-Lab publie l'IP indéfiniment ; autres riches télémétries |
| Plafonds 20/10/30 s | Compatible (§6) avec `ckSize` réduit et compteur cumulatif ; `capped` affiché | Idem A mais sans contrôle du backend (durée/taille imposées par le worker JS par défaut, 15 s/20 Mo) | N/A (pas de transfert) | Non configurables (Mission 12 §4) |
| Fiabilité / Niger | Chemin optimisable (serveur le plus proche, région) ; pas de serveur au Niger dans la liste publique, mais un auto-hébergement régional est possible | Chemins longs EEA/NA uniquement ; aucune entrée Ouest-africaine | Mesure Internet indisponible ; seule la liaison Wi-Fi est affichée | Idem B (serveurs éloignés) |
| Maintenance | Opérateur tient à jour PHP (Docker `latest`), TLS, rate-limit | Liste cassante, sponsors imprévisibles, schémas `//` | Nulle (code déjà livré et testé) | Vendors + contrats |
| Compatibilité Android | OkHttp natif — `INTERNET` déjà déclaré, aucune permission nouvelle | Idem, mais dépendance externe | Aucune dépendance | SDK tiers / WebView |

**Verdict** : **A** est la seule voie à la fois licite, privée et contrôlable sous les plafonds projet. B et D sont rejetées pour les mêmes raisons que Mission 12 ; C reste le garde-fou tant qu'aucun endpoint n'est configuré par l'utilisateur / l'opérateur.

## Recommandation pour WiFi Admin

1. **Endpoint auto-hébergé configurable, désactivé par défaut, HTTPS obligatoire.** L'app ne contient **ni** URL de mesure par défaut, **ni** liste embarquée, **ni** chargement d'une liste distante (`server-list.json` / `servers.php`). Un écran « Test de vitesse — serveur » (ou une entrée Settings) permet de saisir une base URL HTTPS validée (parse + schéma `https` + certificat valide), stockée en local (DataStore). Tant qu'elle est vide, `DisabledSpeedTestEngine` reste l'engine de production.
2. **Télémétrie jamais** : pas d'appel `results/telemetry.php`, pas de partage d'image, pas de sérialisation d'IP/ISP ; le déploiement de référence (guide dans `docs/`) supprime `results/` (ou `telemetry_level=none`).
3. **Protocole minimal** : `GET garbage.php?ckSize=2..5&r=…` (download, 2–4 flux), `POST empty.php?r=…` (upload, 2–3 flux, corps 1–4 Mio régénéré), `GET empty.php?r=…` ×5 (ping, médiane). `getIP.php` **non appelé** (la latence se mesure sans révéler l'IP ; aucune valeur ajoutée pour WiFi Admin). Formule `SpeedTestCalculator` inchangée (×1.0, pas de 1.06) ; écarts vs mesure JS documentés dans l'écran d'aide.
4. **Plafonds intangibles** : compteur atomique cumulé (tous flux, retries, warm-up), `call.cancel()` au premier dépassement, `capped=true` dès qu'il est atteint, budget global 30 s découpé (ex. 6 s ping + 12 s DL + 12 s UL — à valider par test d'usage), annulation via `SpeedTestEngine.cancel()` sous le verrou existant.
5. **Documentation opérateur** (dans `docs/`, section à rédiger avec l'implémentation) : déploiement PHP + limites de taille POST (128m), `client_max_body_size / LimitRequestBody / maxAllowedContentLength`, suppression de `results/`, `redact_ip_addresses` si télémétrie accidentelle, base GeoIP hors-ligne à jour, rate-limit reverse-proxy obligatoire, certificat HTTPS valide, clé API ipinfo **non** requise si `getIP` abandonné.

## Risques et points à valider

| Risque / question | Statut | Action |
|---|---|---|
| **Licence LGPL-3.0** | **Observé** (texte FSF) — appl. logique **Inférée** | Faire valider : réimplémentation protocole = pas de code LGPL ; aucune`speedtest.js`/`worker` embarqué dans le APK. Si un jour le worker est intégré, revisiter (avis LGPL + mention copyright). |
| **Backend sans auth/rate-limit** | **Inféré** (absence dans `backend/*.php`) | Reverse-proxy obligatoire dès l'ouverture (rate-limit par IP, quota bande passante, WAF, journalisation). Sans cela, une URL publique devient une cible DDoS / DDoS-amplification `garbage.php` — même si l'app ne référence jamais l'URL. |
| **Télémétrie/IP publique** | **Observé** (`telemetry.php` stocke IP, ISP, User-Agent, log ; `getIP.php` expose IP/ISP) | Supprimer `results/` du serveur de référence ; ne pas appeler `getIP.php` ni `telemetry.php` ; audit d'en-têtes `X-Forwarded-For` (risque d'usurpation d'IP derrière proxy — `getIP_util.php` fait confiance à ces en-têtes). |
| **Plafonds vs stabilisation** | **Inféré** (calcul §6) | Valider par mesures sandbox sur lien saturé : le marqueur `capped` et le plancher « ≥ X » doivent s'afficher dès le dépassement ; ne jamais présenter un plafond comme une vitesse précise. |
| **Keep-alive / réutilisation de connexion** | **Observé** (`Connection: keep-alive` dans `empty.php`) | Vérifier le pool de connexions OkHttp : latence ping « double » si keep-alive absent ; configurer `ConnectionPool(maxIdle=4, keepAlive=30 s)` (aligné sur le CLI Go `MaxIdleConnsPerHost = concurrent+2`). |
| **Compression / buffering PHP** | **Observé** (`zlib.output_compression Off`, `output_buffering Off`, `flush()` par Mo) | Tester sur PHP lent : `garbage.php` génère du `openssl_random_pseudo_bytes` à la volée ; benchs CPU serveur + préférer `garbage.dat` statique pour les très gros volumes. |
| **Serveur au Niger / entrepôts** | **Inconnu** (aucune entrée africaine de l'Ouest dans `server-list.json` ; régionalisation non chiffrée) | Chiffrer latence et coût d'un point de présence le plus proche ; auto-hébergement régional = coût récurrent (à décider produit). Sans serveur proche, la mesure reflète le chemin international, pas la « dernière mille » (déjà noté Mission 12 §6). |
| **Politique d'usage publique LibreSpeed** | **Inconnu** (non trouvée dans les sources examinées) | Chercher la page d'usage de `librespeed.org` / `backends` avant tout débat « liste publique » — ne pas présumer d'autorisation. |
| **Biais du facteur 1.06 vs formule locale** | **Observé** (JS 1.06, CLI 1.0, WiFi Admin 1.0) | Choix assumé dans `SpeedTestCalculator.kt` (×1.0) ; mention dans l'aide utilisateur ; pas de recalibrage avant mesures terrain. |
| **Test coverage** | — | Brancher le nouvel engine sur `SpeedTestRepository` existant ; tests JVM : compteur cumulatif, `capped`, HTTPS-only, set d'URL vide → zéro appel, annulation sous plafond, timeouts ; instrumentés : ping médiane 5 sondes, upload 10 Mo stop. |

## Sources primaires

Toutes les URL ci-dessous ont été lues le 08/11/2026 (contenu de la branche `master` sauf mention). Aucune ne fait office de partie du livrable — ce sont les fondements factuels.

| URL | Fichier lu |
|---|---|
| https://github.com/librespeed/speedtest | `LICENSE`, `README.md`, `doc.md`, `server-list.json`, `settings.json`, `config.json`, `speedtest.js`, `speedtest_worker.js` |
| https://github.com/librespeed/speedtest | `backend/garbage.php`, `backend/empty.php`, `backend/getIP.php`, `backend/getIP_util.php`, `backend/getIP_ipInfo_apikey.php` ; inventaire via `api.github.com/repos/librespeed/speedtest/contents/backend` |
| https://github.com/librespeed/speedtest | `results/telemetry.php`, `results/telemetry_settings.php` |
| https://github.com/librespeed/speedtest-cli | `README.md`, `speedtest/speedtest.go`, `speedtest/helper.go`, `defs/server.go`, `defs/bytes_counter.go` |
| https://github.com/librespeed/speedtest-android | `README.md`, `PRIVACY.md`, `SECURITY.md` |
| Contexte local | `docs/MISSION_12_SPEEDTEST_RESEARCH.md`, `docs/MISSION_12_IMPLEMENTATION_REPORT.md`, `app/src/main/java/dev/wifiadmin/app/data/speedtest/{SpeedTestPolicy,SpeedTestCalculator,SpeedTestResult,SpeedTestEngine,WifiLinkInfoProvider}.kt` |

*Fin du document — Mission 13. Aucun secret, aucune adresse IP publique, aucun SSID/MAC ne figure dans ce fichier. Ce document est la seule modification de la mission : il ne modifie pas la fonctionnalité mesurée (moteur réel toujours désactivé par défaut).*
