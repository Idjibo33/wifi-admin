# Mission 14 — Serveur LibreSpeed local (installation et vérification)

> Date : 2026-11-08 · Branche : `feature/mission-14-local-librespeed` (depuis `develop` 0680de6)
> Objectif : valider le vrai moteur LibreSpeed (Mission 13, PR #6) contre un backend contrôlé,
> **sans** VPS, **sans** serveur public, **sans** benchmark réel.

## 1. Environnement Ubuntu vérifié (2026-11-08)

| Élément | Constat |
|---|---|
| Docker | `29.9.3` (`docker --version`, daemon OK), Compose `v5.7.0` |
| Socket Docker | `/var/run/docker.sock` (`root:docker`, `srw-rw----`) ; l’utilisateur est dans le groupe `docker` (984) — aucun `chmod`/`usermod` appliqué |
| Réseau local | `wlp58s0` UP, `192.168.1.174/24` (sous-réseau du routeur ZLT `192.168.1.1`) |
| Ports déjà utilisés (`ss -ltn`) | `80`, `8001`, `9000`, `3306`, `5037`, `5432/5433/5434`, `6379` — **`8280`, `8443`, `8888` libres** (vérifié par `ss -ltn 'sport = :8280 or sport = :8443 or sport = :8888'`) |
| Pare-feu | `ufw status` requiert root (`ERROR: You need to be root`) — **aucune règle modifiée**. Commandes de référence à demander : voir `infra/librespeed-local/README.md` |
| ADB Pixel | `/home/ismael/Android/Sdk/platform-tools/adb`, `2B091FDH3004DG device usb:1-4 product:cheetah_beta model:Pixel_7_Pro device:cheetah` |
| Google | `2026-11-08`, `Ubuntu ok` |

## 2. Choix d’infrastructure

- **Image officielle** : `ghcr.io/librespeed/speedtest:6.3.1` (version pinnée, `v6.3.1` du 2026-10-21, manifest amd64 `sha256:c7ac7092ccbf793e9ed613818c501ad6052161c18a16a3fbaa9d5b765002a65d`). Documentation : [doc_docker.md](https://github.com/librespeed/speedtest/blob/master/doc_docker.md).
- **Mode `backend`** (pas `standalone`) : le conteneur sert à la racine uniquement `garbage.php`, `empty.php`, `getIP.php`. Aucune UI, aucun `results/`, aucune DB. C’est exactement le contrat du client Kotlin (`LibreSpeedClient.kt`) : `empty.php` (ping GET + upload POST) et `garbage.php` (download) — **`getIP.php` n’est jamais appelé par l’app**.
- **Aucun port public** : `ports: ["127.0.0.1:8280:8080"]` (boucle locale Ubuntu uniquement). `restart: "no"`, aucun volume, `security_opt: no-new-privileges:true`.
- **Télémétrie désactivée** : `TELEMETRY=false`, `DISABLE_IPINFO=true`, `DISTANCE=""` (aucun appel à `ipinfo.io`, aucune collecte de résultats, aucun identifiant de volume). Vérifié : `GET /results/telemetry.php` et `/results/stats.php` retournent `404` en mode backend.
- **Isolation ZLT** : réseau Docker bridge `librespeed-local`, aucun secret routeur dans l’env, aucun `network_mode: host`, aucun volume vers `router-core`. Le conteneur ne connaît pas `/cgi-bin/http.cgi` ni les credentials du routeur (preuve : `git grep Zlt data/speedtest/` = vide, garde `Mission12BoundaryTest` vert — voir rapport d’implémentation).

## 3. Fichiers livrés

| Fichier | Rôle |
|---|---|
| `infra/librespeed-local/compose.yaml` | `speedtest` (backend, `127.0.0.1:8280:8080`) + profil optionnel `caddy` (TLS dev, `127.0.0.1:8443:8443`, profile `tls` **désactivé par défaut**) sur réseau `librespeed-local` |
| `infra/librespeed-local/Caddyfile` | Reverse-proxy HTTPS dev : terminaison TLS avec certificat SAN, `reverse_proxy speedtest:8080` |
| `infra/librespeed-local/generate-local-ca.sh` | Génère CA locale + certificat serveur SAN (`127.0.0.1`, IP LAN, `localhost`) avec `openssl`. Clés privées locales uniquement |
| `infra/librespeed-local/README.md` | Démarrage, arrêt, nettoyage, vérification `curl`, Voies A (`adb reverse`) et B (TLS LAN, après autorisation) |
| `infra/librespeed-local/tls/.gitkeep` | Dossier des secrets **ignoré par git** (`infra/librespeed-local/tls/*` dans `.gitignore`) |

Vérification `git check-ignore` (2026-11-08) : `ca.crt`, `ca.key`, `cert.pem`, `key.pem` dans `infra/librespeed-local/tls/` sont bien ignorés ; `app/src/debug/res/raw/local_ca.crt` (certificat **public** seul) est traçable et versionné.

## 4. Démarrage reproductible

```bash
docker compose -f infra/librespeed-local/compose.yaml up -d
docker compose -f infra/librespeed-local/compose.yaml ps
docker logs wifiadmin-librespeed-local-speedtest-1
```

Après `up`, `ss -ltn 'sport = :8280'` montre `127.0.0.1:8280` seul (jamais `0.0.0.0`/`*`).

Arrêt et nettoyage :

```bash
docker compose -f infra/librespeed-local/compose.yaml down
docker compose -f infra/librespeed-local/compose.yaml down -v
```

Le conteneur est éphémère (`restart: no`) : il ne survit pas à `down` ni à un reboot.

## 5. Vérification des endpoints (petits transferts techniques uniquement)

Aucun benchmark complet n’a été lancé. Seuls des transferts de contrôle (`empty.php` vide, `garbage.php?ckSize=1` ≈ 1 Mo, POST 64 Ko / 1 Mo) ont servi à prouver la disponibilité, les codes HTTP et l’absence de redirection. Aucun `getIP.php`, aucun appel Internet.

### 5.1 Résultats observés (2026-11-08)

| Test | Commande | Résultat |
|---|---|---|
| `empty.php` GET (ping) | `curl -s -i http://127.0.0.1:8280/empty.php?r=12345` | `HTTP/1.1 200 OK`, `Content-Length: 0`, `Cache-Control: no-store`, **aucun `Location:`/redirection** |
| `garbage.php` faible volume | `curl -s http://127.0.0.1:8280/garbage.php?ckSize=1&r=12345` | `code=200`, `size=1048576`, `Content-Type: application/octet-stream`, `Content-Disposition: attachment; filename=random.dat`, **aucune redirection** |
| `empty.php` POST (upload, petit) | `POST --data-binary @/tmp/upload-64k.bin` (64 Ko) | `code=200 upload=65536 download=0`, **aucune redirection** |
| Limite POST | `POST` 1 Mo (`head -c 1048576 /dev/urandom`) | `code=200 upload=1048576` — bien sous `post_max_size=32M` du conteneur et sous le plafond app `10 MiB` |
| Télémétrie | `GET /results/telemetry.php`, `/results/stats.php` | `404` / `404` — aucun `results/` copié en mode backend |
| Redirections (absentes) | `-D -` sur `empty.php` et `garbage.php` | Aucun en-tête `Location` ; l’app OkHttp a `followRedirects(false)` et `followSslRedirects(false)` : tout éventuel `3xx` deviendrait `ServerUnavailable`, pas un contournement de schéma |
| Arrêt / redémarrage | `docker compose restart` puis `down` + `up -d` | Après redémarrage : `GET empty.php → 200 size=0`, logs Apache `200 332` — **comportement reproductible, aucune DB persistée** |

Commandes complètes : voir `infra/librespeed-local/README.md` (bloc « Vérification des endpoints »). Aucun test de débit réel n’a été exécuté depuis Ubuntu ; les transferts ci-dessus sont bornés (< 2 Mo) et servent uniquement à prouver le protocole.

### 5.2 Limites constatées

- `post_max_size = 32M` côté PHP : l’upload app (un seul POST de `maxUploadBytes = 10 MiB`) est dans la limite (10 < 32). Ne pas augmenter le plafond sans revue.
- `ckSize=20` est le cas réel de la policy par défaut (20 MiB en une seule réponse) : à valider sur le serveur local avant tout test réel (CPU `openssl_random_pseudo_bytes`, RAM).
- Le `HEALTHCHECK` Docker de l’image (`GET /`) peut rester `starting`/`unhealthy` en mode backend (`/` n’est pas un endpoint documenté) : ne pas en faire un critère bloquant, valider par `empty.php`/`garbage.php`.

## 6. Sécurité et limites

- **Aucune exposition LAN** à ce stade : le service n’écoute qu’en boucle locale. L’accès Pixel passe par `adb reverse` ou, après autorisation explicite, par le profil TLS LAN (documenté dans `MISSION_14_ANDROID_VALIDATION.md`).
- **Aucune modification du routeur ZLT** : aucun paramètre du routeur n’est modifié, aucun volume d’identifiants n’est monté.
- **Clés privées hors git** : `infra/librespeed-local/tls/*.key` / `key.pem` restent locaux (`.gitignore`). Seul le certificat CA **public** est versionné dans `app/src/debug/res/raw/local_ca.crt` (debug-only).
- Pour la production (hors Mission 14), un reverse-proxy avec rate-limit par IP serait obligatoire avant toute exposition (le backend n’a ni authentification ni rate-limit natif).

## 7. Voir aussi

- `infra/librespeed-local/README.md` — guides démarrage/arrêt/vérification et Voies A/B.
- `docs/MISSION_14_ANDROID_VALIDATION.md` — connexion Pixel, HTTPS dev, config debug.
- `docs/MISSION_14_IMPLEMENTATION_REPORT.md` — travaux, sous-agents, skills, difficultés.
