# LibreSpeed local — dev isolé (Mission 14)

Serveur de mesure **contrôlé et local uniquement**. Aucun VPS, aucun serveur public,
aucun benchmark réel sans autorisation explicite. Image officielle `ghcr.io/librespeed/speedtest:6.3.1`
(MODE `backend`, télémétrie `false`, `DISABLE_IPINFO=true`, écoute `127.0.0.1:8280`).

## État du poste (vérifié 2026-11-08)

- Docker 29.9.3, daemon OK, socket `/var/run/docker.sock` accessible (groupe `docker`).
- IP LAN Ubuntu : `192.168.1.174/24` sur `wlp58s0` (up).
- Pixel 7 Pro visible via ADB : `2B091FDH3004DG device` (`/home/ismael/Android/Sdk/platform-tools/adb`).
- Ports libres vérifiés : `8280`, `8443`, `8888` (80 déjà pris, 8001/9000 pris).
- Pare-feu : `ufw status` requiert root — **aucune modification appliquée sans autorisation**.
  Commandes de référence (à demander) :
  `sudo ufw status numbered`, ouverture temporaire **limitée à l’IP du Pixel (/32)
  uniquement** (IP relevée le jour J via `$ADB shell ip addr show wlan0` — observée
  `192.168.1.115/24` le 2026-11-08, jamais tout le /24) :
  `sudo ufw allow from 192.168.1.115/32 to any port 8443 proto tcp comment 'WiFi Admin LibreSpeed dev'`
  puis fermeture : `sudo ufw delete <num>` / `sudo ufw status numbered` pour vérifier.
  **Attention Docker+UFW** : une règle UFW seule **NE filtre pas** un port publié par Docker
  (table nat `DOCKER` avant `INPUT`/`OUTPUT`, docker-proxy, chaîne `FORWARD`/`DOCKER-USER`).
  Elle doit être combinée à une règle `DOCKER-USER` (voir Voie B) ; elle disparaît au
  redémarrage de Docker et doit être réappliquée à chaque fenêtre.

## Démarrage (boucle locale, sans pare-feu)

```bash
docker compose -f infra/librespeed-local/compose.yaml up -d
docker compose -f infra/librespeed-local/compose.yaml ps
docker logs wifiadmin-librespeed-local-speedtest-1
```

Le service écoute uniquement sur `127.0.0.1:8280` (pas de port public).
`restart: no` : ne redémarre pas après reboot.

Arrêt et nettoyage :

```bash
docker compose -f infra/librespeed-local/compose.yaml down
docker compose -f infra/librespeed-local/compose.yaml down -v
# Supprime aussi le réseau wifiadmin-librespeed-local si restant :
docker network prune --filter name=wifiadmin-librespeed-local -f  # inspecter d'abord
docker inspect wifiadmin-librespeed-local-speedtest-1
```

## Vérification des endpoints (petits transferts techniques uniquement)

Aucun benchmark complet. Les blocs de 1 Mo maximum sont utilisés uniquement pour
prouver la disponibilité et les codes HTTP. Ne pas utiliser `garbage.php` sans paramètre
(génère un transfert par défaut de plusieurs Mo selon la version).

```bash
BASE=http://127.0.0.1:8280

# empty.php GET (ping)
curl -s -o /dev/null -w "GET empty.php : %{http_code} %{size_download} o %{time_total}s\n" \
  "$BASE/empty.php?r=12345"

# empty.php POST (upload, petit — 64 Ko, jamais de gros volume ici)
dd if=/dev/urandom of=/tmp/upload-64k.bin bs=1024 count=64 status=none
curl -s -o /dev/null -w "POST empty.php : %{http_code} %{size_upload} o %{time_total}s\n" \
  -X POST --data-binary @/tmp/upload-64k.bin "$BASE/empty.php?r=12345"
rm /tmp/upload-64k.bin

# garbage.php faible volume (ckSize=1 ≈ ~1 Mo selon implémentation, plafond technique)
curl -s -o /dev/null -w "GET garbage.php : %{http_code} %{size_download} o %{time_total}s redirect=%{redirect_url}\n" \
  "$BASE/garbage.php?ckSize=1&r=12345"

# Vérifier l'absence de redirection inattendue et les en-têtes
curl -s -D - -o /dev/null "$BASE/empty.php?r=12345"   # suivi : Location:, server:, etc.
curl -s -D - -o /dev/null "$BASE/garbage.php?ckSize=1&r=12345"

# Limites des tailles POST : tester 0 Ko et 1 Mo (ne jamais dépasser 10 Mo ici)
# L'app plafonne a 10 Mo upload / 20 Mo download / 30 s (voir SpeedTestPolicy).

# Arrêt / redémarrage reproductible
docker compose -f infra/librespeed-local/compose.yaml restart
docker compose -f infra/librespeed-local/compose.yaml down
docker compose -f infra/librespeed-local/compose.yaml up -d
# Re-vérifier empty.php GET ci-dessus apres redémarrage.
```

Notes opérationnelles :

- `empty.php` ne doit jamais retourner de `3xx` inattendu ; l'app OkHttp a
  `followRedirects(false)` et `followSslRedirects(false)` : tout `3xx` devient `ServerUnavailable`.
- `garbage.php?ckSize=1` reste un transfert technique (< 1–2 Mo). Aucun test de débit réel
  n'est lancé depuis Ubuntu.
- `getIP.php` n'est **jamais** appelé par l'app (anti-fuite IP). Ne pas l'exercer pendant la validation.
- `telemetry.php` / `results/` restent désactivés (`TELEMETRY=false`). Aucun résultat n'est collecté.

## Connexion Pixel 7 Pro → Ubuntu (sans pare-feu, recommandé)

`127.0.0.1` sur le Pixel désigne le Pixel, pas l'ordinateur. Deux chemins reproductibles :

### Voie A — `adb reverse` (boucle, sans pare-feu, sans TLS custom)

```bash
export ADB=/home/ismael/Android/Sdk/platform-tools/adb
$ADB devices -l
$ADB reverse tcp:8280 tcp:8280
$ADB reverse --list
# Sur le Pixel, l'app debug (buildConfig SPEEDTEST_BASE_URL=http://127.0.0.1:8280)
# atteint le backend Ubuntu sans passer par le routeur ZLT ni Internet.
# Nettoyage : pas de règle pare-feu a retirer ; le reverse est effacé a la prochaine
# connexion ADB, ou explicitement : $ADB reverse --remove tcp:8280
```

Avantage : aucune ouverture LAN, aucune CA. Limite : le dialecte loopback de
`SpeedTestEndpointValidator` autorise `http://127.0.0.1` uniquement (rejet hors loopback).
**Ne lance aucun test de débit réel sans autorisation explicite.**

### Voie B — HTTPS dev avec CA locale (profil `tls`, après autorisation de pare-feu)

1. Générer la CA locale et le certificat SAN (`127.0.0.1`, IP LAN, `localhost`) :

   ```bash
   LAN_IP=192.168.1.174 infra/librespeed-local/generate-local-ca.sh
   # La clé privée (*.key, key.pem) reste locale. Ne jamais la committer.
   cp infra/librespeed-local/tls/ca.crt app/src/debug/res/raw/local_ca.crt
   git add app/src/debug/res/raw/local_ca.crt   # certificat PUBLIC uniquement
   ```

2. Config Android debug-only : `network_security_config.xml` (`debug-overrides` trust `local_ca.crt`)
   + manifeste debug merge. La release n'embarque jamais cette CA.

3. Démarrer le profil TLS (boucle en premier, LAN seulement après accord) :

   ```bash
   docker compose --profile tls -f infra/librespeed-local/compose.yaml up -d
   curl --cacert infra/librespeed-local/tls/ca.crt https://127.0.0.1:8443/empty.php?r=12345 -v
   # LAN seulement apres autorisation explicite :
   # 1) Dans compose.yaml, remplacer (profil tls uniquement) :
   #    ports:
   #      - "192.168.1.174:8443:8443"   # IP LAN concrete de wlp58s0, a confirmer le jour J
   #    (JAMAIS de CIDR 192.168.1.0/24 en host_ip : host_ip = adresse unique, voir doc Docker.)
   # 2) Appliquer le filet DOCKER-USER limite au Pixel /32 (sudo, voir Voie B §4) :
   #    sudo iptables -I DOCKER-USER -i wlp58s0 -m conntrack --ctstate ESTABLISHED,RELATED -j ACCEPT
   #    sudo iptables -I DOCKER-USER -i wlp58s0 ! -s 192.168.1.115/32 -j DROP
   #    (Alternative : ipset de sources autorisees + DROP.)
   # 3) Completer par UFW limite au meme /32 (documentation, insuffisant seul) :
   #    sudo ufw allow from 192.168.1.115/32 to any port 8443 proto tcp comment 'WiFi Admin LibreSpeed dev'
   ```

   Vérifier le SAN (`openssl x509 -in infra/librespeed-local/tls/cert.pem -noout -text | grep -A1 "Subject Alternative Name"`)
   avant tout build debug. Ne jamais désactiver la validation TLS dans l'app
   (aucun `TrustManager` permissif, aucun `HostnameVerifier` custom).

4. Fermeture et nettoyage explicites :

   ```bash
   docker compose --profile tls -f infra/librespeed-local/compose.yaml down
   sudo ufw status numbered   # relever le <num> puis :
   sudo ufw delete <num>
   sudo ufw status numbered   # confirmer la suppression
   $ADB reverse --remove tcp:8280  # si Voie A empruntee
   ```

Isolation ZLT : le conteneur est en réseau bridge `librespeed-local`, sans credentials,
sans volume, sans `network_mode: host`, et ne joignable que par `speedtest:8080`
interne. Il ne connaît pas le protocole d'administration ZLT (`/cgi-bin`, credentials routeur)
et ne doit jamais y accéder. Aucune variable routeur n'est injectée dans le conteneur.

## Références

- Documentation Docker officielle : <https://github.com/librespeed/speedtest/blob/master/doc_docker.md>
- Backend : `MODE=backend` sert `garbage.php`, `empty.php`, `getIP.php` sur le port web.
- App : `LibreSpeedClient.kt` n'utilise que `empty.php` et `garbage.php` ; `getIP.php` n'est jamais appelé.
