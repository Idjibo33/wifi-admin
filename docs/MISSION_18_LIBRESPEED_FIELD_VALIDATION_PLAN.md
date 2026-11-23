# Mission 18 — Préparation de la validation réelle du SpeedTest LibreSpeed

Date de préparation : 2026-11-23.  
Branche : `feature/mission-18-librespeed-field-validation`.  
Statut : **plan prêt, aucune exposition réseau ni benchmark réel effectué**.  
Autorisation requise avant exécution : ouverture LAN temporaire protégée + **un seul** benchmark.

Ce document prépare la Mission 17 à passer du laboratoire JVM au serveur LibreSpeed réel. Il ne répète pas les validations fonctionnelles déjà clôturées et ne modifie ni les plafonds de production, ni le routeur ZLT, ni `main`.

---

## 1. État Git initial

Vérifié en lecture seule avant création de la branche :

| Élément | État confirmé |
|---|---|
| PR #12 | `MERGED` dans `develop` le 2026-11-23T20:18:33Z |
| Head PR validé | `09e52ead4c52b068b4436fb5ee38d353a6876232` |
| Squash merge | `49d21e36ff51820e6edf3a82bc211f9f30f55fb4` |
| Sujet du squash | `feat(speedtest): complete time-based measurement and result classification (Mission 17)` |
| CI post-fusion, sur le SHA du squash | `JVM tests and debug build` = success ; `Offline router scenarios` = success |
| `develop` local / distant | `49d21e36…` / `49d21e36…` (synchronisés) |
| `main` locale | `1103658bb288af262558d13f152c5a5923384c33` — inchangée |
| `main` distante | `1bfcf7540882e79a32aca257ad942222b6b88798` — inchangée |
| Branche de travail | `feature/mission-18-librespeed-field-validation`, créée depuis `develop` |
| Working tree | propre, hors 26 fichiers non suivis `assets/` préexistants |
| Plafonds production | Download 20 Mio, Upload 10 Mio, durée globale 30 s — inchangés |

Aucune opération destructive (`clean`, `reset --hard`, suppression de `assets/`) n'a été effectuée.

---

## 2. Versions et topologie

Relevé du 2026-11-23. Les adresses DHCP doivent être re-relevées le jour J ; ce ne sont pas des valeurs permanentes.

| Composant | Valeur relevée |
|---|---|
| Interface LAN Ubuntu | `wlp58s0`, `192.168.1.174/24` |
| Route par défaut Ubuntu | via `192.168.1.1` dev `wlp58s0` |
| Docker | 29.9.3, backend `iptables`, userland proxy actif |
| Image LibreSpeed | `ghcr.io/librespeed/speedtest:6.3.1` (`sha256:bc34bf69b6c1…`), PHP 8.5.12 |
| Image Caddy | `caddy:2-alpine`, Caddy 2.11.9 (`sha256:78bd35025c7b…`) |
| Profil compose | `wifiadmin-librespeed-local`, services `speedtest` + `caddy` |
| Backend | `MODE=backend`, `TELEMETRY=false`, `DISABLE_IPINFO=true`, `WEBPORT=8080`, publication par défaut `127.0.0.1:8280:8080` |
| Caddy | `:8443`, certificat local, `reverse_proxy speedtest:8080`, log `stdout` format `console` |
| Publication LAN par défaut | `192.168.1.174:8443:8443` **commentée** dans `compose.yaml` |
| Certificat serveur | `CN=localhost`, SAN `IP:127.0.0.1`, `IP:192.168.1.174`, `DNS:localhost`, valide jusqu'au 2027-02-06 |
| UFW | désactivé (`ENABLED=no`) — aucun secours |
| Pixel | Pixel 7 Pro, Android 17, ADB `2B091FDH3004DG` |
| Wi-Fi Pixel | `wlan0` = `192.168.1.189/24`, réseau `NOT_VPN`, `NOT_METERED`, `VALIDATED` |
| Application | `dev.wifiadmin.app`, versionName `0.1.0`, versionCode 1, targetSdk 37 |
| Permissions | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK` accordées |
| `_adb reverse_` | **actif** : `tcp:8080 → tcp:8080` — à vider avant le benchmark |
| État serveur | aucun conteneur LibreSpeed/Caddy, aucun listener `8443` ou `8280` actuellement |

Identifiants Wi-Fi (SSID/BSSID/MAC) et secrets ne sont pas consignés ici.

---

## 3. Contrôles préflight

Commandes de lecture à rejouer le jour J, avant toute ouverture de port :

```bash
ROOT=/home/ismael/Documents/wifi-admin/wifi-admin-app
ADB=/home/ismael/Android/Sdk/platform-tools/adb
cd "$ROOT"

git rev-parse HEAD                                    # attendu : commit de la branche M18
git status --porcelain                                # attendu : ?? assets/ uniquement

ip -4 -brief addr show wlp58s0                        # noter l'IP Ubuntu actuelle
ip -4 route show default
ss -ltn 'sport = :8443 or sport = :8280'              # attendu : vide
ss -ltn 'sport = :5433 or sport = :5434 or sport = :6379'  # baseline autres services

docker compose -f infra/librespeed-local/compose.yaml --profile tls ps -a
docker ps --format '{{.Names}}\t{{.Ports}}'

"$ADB" devices -l
"$ADB" shell ip -4 addr show wlan0                    # noter <PIXEL_IP>
"$ADB" shell dumpsys connectivity | grep -i vpn       # attendu : pas d'agent VPN actif
"$ADB" reverse --list                                 # attendu : vide après retrait
"$ADB" shell dumpsys package dev.wifiadmin.app \
  | grep -E 'versionName|targetSdk|INTERNET|ACCESS_NETWORK_STATE|ACCESS_LOCAL_NETWORK'

openssl x509 -in infra/librespeed-local/tls/cert.pem \
  -noout -enddate -ext subjectAltName
```

Résultats du préflight déjà effectué :

- Ubuntu et Pixel sur le même LAN `192.168.1.0/24` ; route par défaut commune `.1`.
- Aucun serveur LibreSpeed exposé ; aucun listener `8443`.
- Pixel en Wi-Fi, sans VPN actif sur la session réseau utilisée (`NOT_VPN`).
- Permissions LAN accordées ; aucune nouvelle permission à demander.
- `adb reverse` non vide : **à supprimer avant le run** avec `"$ADB" reverse --remove-all`. Ne pas toucher au VPN ni au routeur.
- État `iptables -t raw` **non capturé** : `sudo -n` exige un mot de passe sur ce poste. L'opérateur disposant de sudo local doit relever la baseline avant toute application :
  `sudo -n iptables -t raw -S PREROUTING`, `sudo -n iptables -S DOCKER-USER`, `sudo -n iptables -t nat -S DOCKER`.

Aucune de ces commandes n'a modifié l'environnement.

---

## 4. Risques identifiés

1. **Chunked long non prouvé** : le corps chunked est validé sous MockWebServer, pas contre LibreSpeed 6.3.1 réel. Risque résiduel officiel de la Mission 17.
2. **`413` possible** : `post_max_size = 32M` dans l'image ; le plafond applicatif est 10 Mio, donc dessous, mais le comportement d'un POST chunked soutenu reste à confirmer.
3. **`docker-proxy` / INPUT** : les règles `DOCKER-USER` post-DNAT ne filtrent pas ce chemin. Le filtre doit être en `raw PREROUTING`, avant DNAT et avant conntrack.
4. **UFW désactivé** : pas de filet de sécurité ; la règle temporaire est le seul filtre.
5. **DHCP** : si l'IP Ubuntu change, le SAN TLS devient invalide. Régénérer le certificat si nécessaire ; **jamais désactiver la validation TLS**.
6. **iOS/Android VPN lockdown** : les sockets épinglés au Wi-Fi peuvent être refusés si un VPN est actif ; arrêt immédiat, pas de repli automatique.
7. **`adb reverse` résiduel** :隧道USB encore configuré ; contraire à l'exigence « aucun tunnel ».
8. **Résultat plafonné attendu** : sur LAN rapide, les plafonds sont atteints avant les fenêtres. Ce n'est pas un échec de compatibilité, mais ce n'est pas une mesure stabilisée.
9. **Ancien résultat affiché** : l'UI affiche `state.result ?: state.previousResult` ; dater chaque capture via « Date du test » / `at`.
10. **Logs Caddy** : contiennent l'IP du Pixel ; données personnelles à stocker localement et à ne pas publier telles quelles.

---

## 5. Plan d'exposition LAN temporaire

**Non appliqué.** À n'exécuter qu'après autorisation explicite.

- Démarrer la pile avec le profil `tls`, sans exposer le LAN dans le dépôt :
  `docker compose -f infra/librespeed-local/compose.yaml --profile tls up -d`.
- Pour la fenêtre autorisée uniquement, créer un override **hors dépôt** :
  `/tmp/opencode/librespeed-m18.override.yaml` avec :
  ```yaml
  services:
    caddy:
      ports:
        - "192.168.1.174:8443:8443"
  ```
  Remplacer l'IP par celle relevée le jour J.
- Aucune entrée `0.0.0.0`, aucune entrée `[::]`, aucun `network_mode: host`, aucun `ip6tables`, aucune modification du compose versionné.
- Poser la règle `raw PREROUTING` **avant** `docker compose up` (fail-closed).
- Vérifier après démarrage :
  `ss -ltn 'sport = :8443'` → uniquement `192.168.1.174:8443` ;
  `docker inspect ... --format '{{json .NetworkSettings.Ports}}'` ;
  compteur de la règle DROP à zéro.
- Contrôle positif : ping HTTPS depuis le Pixel doit réussir.
- Contrôle négatif : bascule temporaire de la règle vers `-s <PIXEL_IP> -j DROP` ; le ping doit échouer et le compteur DROP doit augmenter ; restaurer la règle nominale ; le ping doit repasser.
- Si le contrôle négatif n'a pas pu être joué, le rapport devra dire **« filtrage non prouvé »**.

---

## 6. Stratégie de filtrage

La règle nominale, pré-DNAT, sur l'interface LAN réelle :

```bash
sudo -n iptables -t raw -I PREROUTING 1 -i wlp58s0 -p tcp --dport 8443 \
  -d 192.168.1.174 '!' -s 192.168.1.189/32 \
  -m comment --comment wifiadmin-m18-drop -j DROP
```

Points importants :

- `-d` est l'**IP originale** vue en `PREROUTING`, avant DNAT.
- Pas de `-m conntrack` en table `raw` : l'état n'est pas encore attribué.
- Une erreur sur `! -s <IP>/32` échoue fermé, jamais ouvert.
- Aucune règle `DOCKER-USER` ajoutée ; laisser ce chaînon tel quel.
- La règle disparaît au redémarrage de Docker : à réappliquer à chaque fenêtre.
- Contrôle négatif : remplacer conditionnellement par `-s <PIXEL_IP>/32 -j DROP`, vérifier le compteur avec `sudo -n iptables -t raw -L PREROUTING -n -v --line-numbers`, puis restaurer.

---

## 7. Vérifications TLS

- Le client SpeedTest utilise les défauts OkHttp + plateforme : pas de pinning, pas de `hostnameVerifier` personnalisé, redirections HTTP/HTTPS désactivées (`followRedirects=false`, `followSslRedirects=false`).
- `SpeedTestEndpoint` n'accepte que `https`, sauf loopback explicite en debug.
- En debug, `network_security_config.xml` ajoute uniquement la CA locale de laboratoire aux autorités système ; il n'y a **aucun trust-all**. En release, système seul.
- Vérifier avant le run :
  ```bash
  cmp infra/librespeed-local/tls/ca.crt app/src/debug/res/raw/local_ca.crt
  openssl x509 -in infra/librespeed-local/tls/cert.pem -noout -subject -enddate -ext subjectAltName
  ```
- Attendu : SAN contient l'IP LAN actuelle d'Ubuntu, `DNS:localhost` et `IP:127.0.0.1`.
- Si l'IP a changé : régénérer le certificat avec le script existant, jamais assouplir la validation.
- `usesCleartextTraffic=true` reste un réglage global historique ; la couche applicative refuse malgré tout une URL non HTTPS hors loopback.
- Preuve optionnelle : `SpeedTestHttpsPingInstrumentedTest`, **1 ping, aucun transfert de débit**, seulement après autorisation et avec la fenêtre filtrée ouverte.

---

## 8. Protocole de compatibilité LibreSpeed 6.3.1

Ordre recommandé le jour J :

1. **Test loopback 64 Kio, avant ouverture LAN.** Avec une pile déjà démarrée :
   ```bash
   head -c 65536 /dev/urandom > /tmp/opencode/m18-chunked-64k.bin
   cat /tmp/opencode/m18-chunked-64k.bin | curl --http1.1 -v -sS -o /dev/null \
     -w 'status=%{http_code} upload=%{size_upload}\n' \
     -H 'Transfer-Encoding: chunked' \
     -H 'Content-Type: application/octet-stream' \
     -H 'Content-Encoding: identity' \
     -H 'Accept-Encoding: identity' \
     --data-binary @- \
     --cacert infra/librespeed-local/tls/ca.crt \
     'https://127.0.0.1:8443/empty.php?r=424242'
   ```
   Exiger : `Transfer-Encoding: chunked` présent, **aucun** `Content-Length`, HTTP 200, aucun 3xx/411/413/5xx.
2. Preuve serveur : `docker logs wifiadmin-librespeed-local-caddy-1` → ligne avec `status=200` et `bytes_read=65536`.
3. Nettoyage immédiat de ce test : `docker compose -f infra/librespeed-local/compose.yaml --profile tls down`, `rm -f /tmp/opencode/m18-chunked-64k.bin`, `ss -ltn 'sport = :8443'` vide.
4. Ce test ne prouve que le POST chunked court. Il ne prouve ni un flux long de ~11 s, ni l'absence de buffering à 10 Mio, ni le chemin Pixel/LAN. Ces points restent l'objet du benchmark autorisé.
5. Le benchmark réel doit produire dans les logs Caddy :
   - 5 × `GET /empty.php?r=…` → 200 ;
   - N × `GET /garbage.php?ckSize=…&r=…` → 200 ;
   - 1 × `POST /empty.php?r=…` → 200, `bytes_read` cohérent avec `uploadBytes` ;
   - IP source = IP Wi-Fi du Pixel ;
   - aucun `getIP.php`, `telemetry.php`, 3xx, ni appel à un hôte externe.

Aucun service public ne doit être utilisé comme substitut. Aucune validation MockWebServer ne suffit.

---

## 9. Budget proposé pour un seul benchmark

Recommandation : **un seul run avec la politique de production**, sans réduction artificielle ni ralentissement.

| Phase | Budget | Justification |
|---|---|---|
| Latence | 5 000 ms maximum | `min(latencyProbes × 1 s, maxDuration / 5)` ; les pings réels consomment beaucoup moins |
| Marge | 3 000 ms | `maxDuration / 10` |
| Download | 11 000 ms | `(30 000 − 5 000 − 3 000) / 2` |
| Upload | 11 000 ms | `(30 000 − 5 000 − 3 000) / 2` |
| Watchdog global | 30 000 ms | dernier recours uniquement |
| Volume max | 20 Mio + 10 Mio = 31 457 280 octets | hors corps vides de latence |

Attendu sur le LAN rapide mesuré en Mission 16 :

- Download : `VOLUME_CAP`, `cappedDownload=true`, ~20 971 520 octets, fenêtre courte (~1,6 s à 100 Mbps).
- Upload : `VOLUME_CAP`, `cappedUpload=true`, ~10 485 760 octets, fenêtre courte (~0,8 s à 100 Mbps).
- `isStabilised() == false` : c'est le comportement correct, pas un échec.

Variante debug déjà documentée, **non recommandée pour le run officiel** : 1 Mio / 256 Kio / 10 s, soit latence 2 s, marge 1 s, download 3,5 s, upload 3,5 s. Elle consomme moins mais reste plafonnée sur LAN rapide et valide moins bien le POST à 10 Mio.

Aucun plafond n'est augmenté ; la proposition 50/25 Mio reste abandonnée. Le cadencement `PacedSink` reste strictement dans les tests JVM.

---

## 10. Critères de réussite

### Compatibilité protocolaire — objectif principal

- HTTP 200 sur toutes les requêtes attendues, sans redirection.
- POST chunked accepté par Caddy 2.11.9 + LibreSpeed 6.3.1 : `bytes_read` serveur cohérent avec `uploadBytes`.
- Octets Download/Upload ≤ plafonds ; durée ≤ 30 s.
- `downloadEndReason` et `uploadEndReason` renseignés et cohérents avec les faits observés.
- `cappedDownload`/`cappedUpload` honnêtes ; `VOLUME_CAP` accepté.
- Aucun appel ZLT, télémétrie, `getIP.php`, serveur externe.
- Filtrage positif et négatif prouvés ; rollback vérifié.

### Métrologie exploitable — critère plus strict, non promis

- `downloadEndReason == TIME_WINDOW` **et** `uploadEndReason == TIME_WINDOW`.
- `cappedDownload == false` et `cappedUpload == false`.
- `downloadDurationMillis >= 1000` et `uploadDurationMillis >= 1000`.
- `SpeedTestCalculator.isStabilised(result) == true`.

Sur le LAN actuel, ce second jeu de critères est peu probable avec 20/10 Mio. Il devra être rapporté comme « non atteint » sans modifier les plafonds.

---

## 11. Critères d'arrêt immédiat

Avant tout transfert :

- VPN actif ou repli cellulaire détecté.
- `adb reverse --list` non vide.
- IP Ubuntu différente du SAN sans certificat régénéré.
- Contrôle TLS négatif ou CA locale absente de l'APK debug.
- Contrôle négatif du filtrage non concluant.
- Baseline firewall indisponible (sudo local absent) ou différence inattendue.
- Build/APK non conforme au commit de la branche M18.

Pendant le run :

- `PREMATURE`, `TIMEOUT`, `CANCELLED`, `Incomplete`, `ServerUnavailable`, `NoWiFi`, `PermissionRequired`.
- HTTP ≠ 200, 3xx, 411, 413, 5xx.
- Dépassement d'un plafond ou de la durée globale.
- `bytes_read` serveur incompatible avec `uploadBytes`.
- Perte/changement Wi-Fi, apparition d'un VPN, trafic vers une IP ou un hôte externe.
- Modification d'un autre conteneur Docker ou d'un autre port publié.
- Impossibilité d'annuler proprement (socket non fermé).

Tout arrêt entraîne le nettoyage complet et un rapport d'échec ; aucun deuxième run ne doit être lancé sans nouvelle autorisation.

---

## 12. Plan de rollback

Ordre impératif, validé en Mission 16 :

1. **Fermer le service exposé** : `docker compose -f … -f /tmp/opencode/librespeed-m18.override.yaml --profile tls down`.
2. **Vérifier la fermeture effective** : `ss -ltn 'sport = :8443'` vide et `pgrep -af docker-proxy | grep 8443` vide.
3. **Supprimer uniquement les règles temporaires** `wifiadmin-m18-drop` / `wifiadmin-m18-negative` de `raw PREROUTING`.
4. **Vérifier la restauration** : `sudo -n iptables -t raw -S PREROUTING | grep -c 8443` = 0 ; diff avec la baseline ; listeners 5433/5434/6379 inchangés ; `docker ps` identique à la baseline.
5. **Confirmer les autres services Docker intacts** ; supprimer l'override hors dépôt ; confirmer `adb reverse --list` vide.

Garde automatique à prévoir dans le script d'exécution :

- variable d'autorisation explicite, par exemple `M18_AUTHORIZED=I_AUTHORIZE_M18` ;
- `set -euo pipefail` ;
- `trap cleanup EXIT INT TERM` avec garde `CLEANED` ;
- baseline `iptables`, `ss`, `docker ps` sauvegardée avant toute modification ;
- variables `RULES=1` / `STACK=1` pour ne retirer que ce qui a été posé.

Récupération manuelle si le `trap` n'a pas joué : exécuter les cinq étapes ci-dessus dans l'ordre, puis rejouer les commandes de vérification de la section 3.

---

## 13. Preuves à collecter

Stockage local suggéré : `/tmp/opencode/m18-evidence/`, non commité.

| Preuve | Contenu |
|---|---|
| `git.txt` | HEAD, branche, `git status --porcelain` |
| `preflight-ubuntu.txt` | `ip`, `ip route`, `ss`, `docker ps`, baseline pare-feu |
| `preflight-pixel.txt` | modèle, Android, `ip wlan0`, `dumpsys connectivity`, permissions, `adb reverse --list` |
| `tls.txt` | `openssl x509`, comparaison CA, résultat du ping HTTPS si autorisé |
| `firewall-counters.txt` | compteurs avant/après contrôle positif et négatif |
| `caddy.log` | fenêtre du run : méthodes, paths, statuts, `bytes_read`, IP source |
| `screenshots/` | phases UI, résultat, panneau méta, « Date du test » |
| `datastore.txt` | copie locale du DataStore via `adb exec-out run-as dev.wifiadmin.app cat …` puis extraction JSON (`dlEnd`, `ulEnd`, `dlDurationMs`, `ulDurationMs`, `cappedDl`, `cappedUl`, `durationMs`, `dlBytes`, `ulBytes`, `at`) |
| `cleanup.txt` | preuves de fermeture du port, reste `raw`, diff baseline, `docker ps` final |

L'application n'émet aucun log applicatif : ne pas attendre de logcat pour les raisons de fin. Les raisons brutes ne sont visibles que dans le DataStore. Si `run-as` est indisponible, le rapport devra préciser que seules les preuves UI/Caddy ont été collectées.

Aucun secret, mot de passe, clé privée, token, SSID, BSSID ou MAC ne doit être commis.

---

## 14. Limitations métrologiques

- Le débit Download est calculé après 256 Kio de warm-up ; sur un lien rapide, cette fenêtre est très courte et le résultat plafonné reste une valeur minimale.
- Le départ de fenêtre est pris après le bloc qui franchit 256 Kio : biais positif résiduel de l'ordre de quelques dixièmes de pourcent.
- Le warm-up est fixe, non temporel : trop court sur un lien très lent.
- `uploadDurationMillis` inclut l'établissement de la connexion : le débit upload est légèrement sous-estimé.
- Le débit est applicatif : il exclut TLS/TCP/retransmissions et n'est pas directement comparable à un navigateur LibreSpeed.
- Le chemin Wi-Fi local mesure le lien Wi-Fi vers Ubuntu, pas l'accès Internet ni la latence sous charge.
- Le jitter reste indicatif.
- Les caps sont dupliqués entre `SpeedTestPolicy.kt` et `build.gradle.kts` (BuildConfig) : risque latent de divergence.
- Un EOF systématiquement court est classé `PREMATURE` de façon conservatrice.
- Les anciennes entrées d'historique sans cause (`UNKNOWN`) ne sont jamais annoncées comme stabilisées.

Compromis assumé : budget de données réduit pour protéger un forfait prépayé, au prix d'une précision moindre sur les liens rapides. Aucun ralentissement artificiel de l'application de production n'est prévu.

---

## Point d'arrêt

Ce document est un livrable de préparation. Il n'autorise pas l'exécution.

Autorisation demandée avant toute action suivante : **ouverture temporaire et protégée du serveur LibreSpeed sur le LAN, test loopback contrôlé du POST chunké, puis exécution d'un seul benchmark réel aux volumes et à la durée proposés ci-dessus.**

Tant que cette autorisation n'est pas donnée explicitement, aucun port n'est ouvert, aucune règle firewall n'est appliquée, aucun transfert Download/Upload réel n'est effectué, et le routeur ZLT, `main` et les 26 fichiers `assets/` restent inchangés.
