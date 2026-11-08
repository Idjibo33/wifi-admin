# Mission 14 — Rapport d’implémentation

> Date : 2026-11-08 · Branche : `feature/mission-14-local-librespeed` (depuis `develop` 0680de6, PR #6 fusionnée le 2026-11-08T17:55:56Z)
> Objectif : serveur LibreSpeed **local contrôlé** + validation du moteur + chemin Pixel→Ubuntu
> **sans** VPS, **sans** serveur public, **sans** test de débit réel non autorisé.

## 1. Travaux réalisés

### 1.1 Préparation du dépôt

- Fusion Mission 13 confirmée : `gh pr view 6` → `state: MERGED`, `baseRefName: develop`,
  `headRefName: feature/mission-13-speedtest-reel`, `mergedAt: 2026-11-08T17:55:56Z`.
  `origin/develop` = `0680de6 Mission 13 (desactive par defaut) (#6)`.
  `develop` local synchronisé sans écrasement (`git checkout develop && git merge --ff-only origin/develop`,
  `bd154c7..0680de6`, 2 commits). Branche `feature/mission-14-local-librespeed` créée
  depuis `origin/develop`. `main` non touchée. Seul `assets/` (untracked, non revu) reste hors
  commit — il n’est jamais ajouté.

### 1.2 Vérification Ubuntu (sans modification système)

- Docker `29.9.3` (daemon OK), Compose `v5.7.0`, socket `root:docker` accessible
  (utilisateur dans le groupe `docker` — **aucun changement de groupe/permissions**).
- Ports : `80/8001/9000` pris, `8280/8443/8888` libres (choix `8280` pour LibreSpeed,
  `8443` pour le profil TLS). réseau : `wlp58s0 UP 192.168.1.174/24`, Pixel ADB présent
  (`2B091FDH3004DG device Pixel_7_Pro`).
- Pare-feu : `ufw status` requiert root — **aucune règle modifiée**. Les commandes du
  pare-feu sont documentées (`infra/librespeed-local/README.md`) et requièrent
  l’autorisation explicite — jamais exécutées ici.

### 1.3 LibreSpeed local (installation)

- `infra/librespeed-local/compose.yaml` : image officielle `ghcr.io/librespeed/speedtest:6.3.1`
  (mode `backend`, `TELEMETRY=false`, `DISABLE_IPINFO=true`, `DISTANCE=""`, `WEBPORT=8080`),
  `ports: 127.0.0.1:8280:8080`, `restart: no`, `security_opt: no-new-privileges:true`,
  réseau `librespeed-local`, aucun volume. Vérifiée par `docker compose config`.
- Profil optionnel `tls` (`caddy:2-alpine`) : `127.0.0.1:8443:8443`, `Caddyfile`
  (`reverse_proxy speedtest:8080`, TLS avec le certificat SAN de `generate-local-ca.sh`),
  **désactivé par défaut** (profile `tls`).
- `generate-local-ca.sh` : CA locale + certificat SAN (`127.0.0.1`, `[IP-LAN]`, `localhost`),
  clés privées locales (vérifié `git check-ignore`) — **zéro clé privée committée**.
- Vérification endpoints (petits transferts, cf. `docs/MISSION_14_LOCAL_SERVER.md` §5) :
  `empty.php` GET → `200/0`, `garbage.php?ckSize=1` → `200/1048576`,
  POST 64 Ko et 1 Mo → `200`, aucune redirection, télémétrie `404`, arrêt/redémarrage reproductible.
- TLS loopback : `curl --cacert ca.crt https://127.0.0.1:8443/empty.php → 200`,
  sans CA → échec attendu, `openssl s_client Verify return code: 0 (ok)`,
  puis `down` du profil et retour au backend seul (seul `8280` écouté).

### 1.4 Intégration Android (debug-only)

- `app/build.gradle.kts` : `defaultConfig` reste `""` ; bloc `debug` injecte
  `SPEEDTEST_BASE_URL` depuis `-Pwifiadmin.speedtestBaseUrl` > env `SPEEDTEST_URL_DEBUG` >
  défaut `http://127.0.0.1:8280` (boucle via `adb reverse`). `main` n’a pas été modifiée
  pour le release (`isMinifyEnabled=true` inchangé).
- `app/src/debug/res/xml/network_security_config.xml` + `app/src/debug/AndroidManifest.xml`
  (merge) + `app/src/debug/res/raw/local_ca.crt` (CA **publique** uniquement).
- Résultats : `assembleDebug` OK, `BuildConfig.java` debug =
  `http://127.0.0.1:8280` ; release reste `DisabledSpeedTestEngine` (zéro octet).
  `git diff origin/develop...HEAD router-core/` = vide (non-régression routeur).

### 1.5 Tests (sans trafic Internet réel)

- JVM + build + FakeRouter + instrumentés Pixel : voir
  `docs/MISSION_14_ANDROID_VALIDATION.md` §6 (tous verts le 2026-11-08).
  Critères couverts en JVM (métrologie sans trafic réel) : calculs download/upload,
  latence/jitter, erreurs, annulation, double run, perte Wi-Fi, plafonds
  `20/10/30`, aucun fallback mobile, isolation.
- Aucun benchmark de débit réel (Pixel ou Ubuntu) sans autorisation explicite.
  La CI reste indépendante d’un serveur Internet (aucun `connectedDebugAndroidTest`,
  aucun benchmark en GitHub Actions).

### 1.6 Choix documentés (docs)

- `docs/MISSION_14_LOCAL_SERVER.md`, `docs/MISSION_14_ANDROID_VALIDATION.md`,
  `docs/MISSION_14_REMOTE_OPTIONS.md`, `docs/MISSION_14_IMPLEMENTATION_REPORT.md`
  (ce fichier). Les options distantes A/B/C ne sont **ni déployées ni facturées** ;
  les adresses LAN sont des exemples de labo à remplacer (`[IP-LAN]`).

## 2. Skills et sous-agents réellement utilisés

### Skills OpenCode chargés (via l’outil `skill`)

| Skill | Usage concret Mission 14 |
|---|---|
| `research` | Fondement de l’étude des options distantes (sans contact de serveurs) et de la doc Docker officielle |
| `systematic-debugging` | Erreur `compose.yaml: services.caddy.environment must be a mapping` (cause racine : clé `environment` vide, corrigée en une seule modification, pas de retry en aveugle) |
| `tdd` | Cadre rouge→vert décliné pour les tests (MockWebServer, attente littérale jitter `5.0`, plan V-01→V-10 du sous-agent Tests) |
| `code-review` | Revue indépendante sur deux axes (Standards / Spec) — rapport Sécurité ci-dessous, checklist PR §6 of sub-agent in review |
| `verification-before-completion` | Chaque affirmation « tests verts » / « release désactivée » appuyée par une commande fraîche (`gradlew`, `BuildConfig.java`, `git diff router-core/`) |

> Base des skills : `/home/ismael/.agents/skills/` (listés enfermés ci-dessus).
> Plugin REA vérifié (outils `rea.*` présents, `list_mcp_resources` vide) — mais **non utilisé** :
> aucune binaire à analyser, REA non requis pour une mission Docker/Android.
> Plugins MCP inspectés via `tools.opencode.list_mcp_resources` (`resources: []`).

### Sous-agents spécialisés lancés en parallèle (lecture seule, aucun fichier modifié)

| Sous-agent | Session | Produit | Statut |
|---|---|---|---|
| **Infrastructure Docker & LibreSpeed** | `ses_e43a12b31ffeKV1gxRP6G7bQp1` | Rapport infra complet (compose backend, pins, ports, commandes de vérification) | terminé — relu et appliqué (mode `backend`, `DISABLE_IPINFO=true`, ports `8280`, `HEALTHCHECK` écarté) |
| **Réseau Android / Sécurité TLS** | `ses_e43a12b09ffeBWUxMNnNzbAZBi` | Rapport routage/TLS (127.0.0.1 vs LAN, CA locale debug-only, architecture Caddy/nginx, pare-feu temporaire) | terminé — appliqué (Voie A `adb reverse`, Voie B Caddy + SAN, `debug-overrides`) |
| **Tests & métrologie** | `ses_e43a12b05ffed6JSw6oyyyAd2z` | Rapport tests (inventaire 405 tests, plafonds, erreurs, annulation, isolation, plan V-01→V-10 sans Internet, flakiness) | terminé — exécuté V-01→V-09 en JVM/Clone (tous verts, FakeRouter 10/10 + 3/3) |
| **Sécurité & revue indépendante** | `ses_e43a12b00ffe0viBMoDVpk067u` | Revue (TLS, secrets, permissions, télémétrie, accès ZLT, options distantes A/B/C/D, checklist PR) | terminé — appliqué (`.gitignore` durci, CA publique trackée, aucun élargissement release) |

## 3. Difficultés rencontrées et résolutions

| Difficulté | Cause racine | Résolution (un seul fix, vérifiée) |
|---|---|---|
| `services.caddy.environment must be a mapping` au `compose up` | Clé `environment:` vide après suppression de la variable `CADDY_TRUSTED_PROXIES` | Suppression du bloc `environment` pour le service `caddy`, puis `docker compose config` = OK |
| `HEALTHCHECK` Docker en `backend` reste `starting` | Le healthcheck de l’image tape `GET /` qui n’est pas un endpoint `backend` | Critère écarté : validation par `empty.php`/`garbage.php` (conformité doc officielle) |
| `192.168.1.174` codé pourrait être perçu comme adresses personnelle | IP LAN illustrative dans les exemples | Documentation rédigée en `[IP-LAN]` générique ; la clé privée reste jamais dans git ; le Certificat/public CA est le seul artefact versionné |

## 4. Limitations (assumées)

- Aucun **débit réel** mesuré (ni Ubuntu, ni Pixel) : le moteur n’a été validé que
  par le protocole (`empty.php`, `garbage.php` < 2 Mo) et par la suite JVM
  (MockWebServer). Un run réel (jusqu’à 30 Mo) exige une autorisation explicite.
- Le chemin LAN TLS vers `[IP-LAN]:8443` n’a été testé **qu’en boucle locale** :
  aucune ouverture pare-feu réalisée ; la Voie B LAN reste à ouvrir après accord.
- Les options distantes (A/B/C) ne sont pas déployées : l’option B ou C
  nécessitera une autorisation contractuelle, un certificat valide, un
  reverse-proxy avec rate-limit, et un rafraîchissement des coûts au moment de
  l’achat. Aucune revendication de précision (WiFiman / FAST / Ookla) n’est faite.
- `usesCleartextTraffic="true"` (routeur LAN HTTP) subsiste en release pour le
  protocole routeur : le contrôle HTTPS reste applicatif (validateur) + `followRedirects=false`.
  Assisté par des tests, il reste un résiduel assumé (CORR-01).

## 5. Critères d’acceptation Mission 14

- [x] LibreSpeed **démarre localement** sur Ubuntu (`MODE=backend`, `127.0.0.1:8280`, `6.3.1`).
- [x] Endpoints **répondent correctement** (`empty.php` GET/POST `200`, `garbage.php?ckSize=1 → 200/1 Mo`, aucune redirection, télémétrie `404`).
- [x] **Démarrage et arrêt reproductibles** (`up -d`, `restart`, `down -v` — refait après restart).
- [x] **Configuration debug contrôlée** (`SPEEDTEST_BASE_URL` injectée debug-only, `adb reverse` par défaut).
- [x] **Release sûre** par défaut (`SPEEDTEST_BASE_URL=""` → `DisabledSpeedTestEngine`).
- [x] **Chemin Pixel → Ubuntu préparé** (Voie A testée `nc-open` via `adb reverse` + TLS loopback testé) — LAN à ouvrir après autorisation.
- [x] **Tests automatisés verts** (JVM 405 tests, FakeRouter 10/10 + 3/3, instrumentés Pixel verts).
- [x] **Possibilités serveur distant documentées** (A/B/C avec coûts, latence Niger, confidentialité, maintenance).
- [x] **Contraintes de sécurité respectées** (TLS jamais désactivé, clés hors git, aucun bench réel, aucune dépense).

## 6. PR

PR `feature/mission-14-local-librespeed` → `develop` ouverte le 2026-11-08.
CI : tests JVM + `assembleDebug` + scénarios FakeRouter (aucun benchmark).
Revue indépendante Sécurité (tableau §2) + check-list PR complétée :
- Aucune modification `router-core/` (`git diff origin/develop...HEAD router-core/` vide).
- `DefaultConfig` et `release` restent `""` ; l’override est debug-only.
- `AndroidManifest.xml` : 3 permissions inchangées, `allowBackup=false`.
- Télémétrie/getIP jamais appelés, aucun secret, aucune IP publique publiée.

> **Aucune fusion sans autorisation humaine.** La PR reste ouverte pour revue,
> puis fusion éventuelle après feu vert explicite.
