# Mission 16 — Rapport du premier benchmark LibreSpeed local (Phase B)

Date d'exécution : 2026-11-17 (UTC).
Branche : `feature/mission-16-local-speedtest-validation` (PR #11).
Commit de référence Phase A : `d1ad939`.

## 1. Environnement réel

| Élément | Valeur |
|---|---|
| Date | 2026-11-17 |
| Pixel 7 Pro | Android 17, API 37, serial `2B091FDH3004DG` |
| Wi-Fi Pixel | `192.168.1.189/24`, SSID Smartconnect, 5 GHz (5240 MHz) |
| Ubuntu | `wlp58s0`, `192.168.1.174/24`, route par défaut `192.168.1.1` |
| Routeur | ZLT `192.168.1.1` |
| Docker | 29.9.3, backend iptables (nf_tables), userland proxy actif |
| UFW | désactivé (`ENABLED=no`) |
| LibreSpeed | 6.3.1 backend, `MODE=backend`, `TELEMETRY=false`, `DISABLE_IPINFO=true` |
| Caddy | 2-alpine, profil `tls`, HTTPS sur 8443 |
| VPN | désactivé manuellement par l'utilisateur pendant le run |
| Permission LAN | `ACCESS_LOCAL_NETWORK` accordée (`granted=true`) |
| `adb reverse` | vide (aucun tunnel) |

## 2. Versions et configuration TLS

- Certificat serveur : `CN = localhost`, SAN `IP:127.0.0.1`, `IP:192.168.1.174`, `DNS:localhost`.
- Émetteur : `CN = WiFi Admin Dev CA`.
- Validité serveur : 2026-11-08 → 2027-02-06.
- `openssl verify -CAfile tls/ca.crt cert.pem` : **OK**.
- CA embarquée debug `app/src/debug/res/raw/local_ca.crt` SHA-256 identique à `tls/ca.crt` (`fa545bf7…`).
- Aucune CA privée montée dans Caddy (uniquement `cert.pem` + `key.pem`).
- `no-new-privileges` appliqué au conteneur Caddy.

## 3. Configuration réseau (Phase B)

- Publication temporaire : `192.168.1.174:8443:8443` uniquement (pas de `0.0.0.0`, pas d'IPv6).
- Filtrage principal : `raw PREROUTING` sur `wlp58s0`, drop des sources non-Pixel vers `192.168.1.174:8443` (pré-DNAT, avant conntrack).
- `DOCKER-USER` vidé (les règles post-DNAT étaient inopérantes car le trafic Pixel passe par `docker-proxy`/INPUT).
- Complément pré-DNAT actif pendant toute la fenêtre.

## 4. Preuves de filtrage

### Contrôle positif
- `SpeedTestHttpsPingInstrumentedTest` : **PASS** (0.031 s, HTTP 200, TLS accepté, aucun `adb reverse`).
- Exécuté avec l'application au premier plan (réseau débloqué).

### Contrôle négatif
- Règle basculée pour exclure le Pixel (`-s 192.168.1.189 -j DROP`).
- Ping instrumenté : **FAIL** (timeout attendu).
- Compteur `raw PREROUTING` : 0 → **4 paquets / 240 octets** (incrémenté).
- Règle restaurée (`! -s 192.168.1.189 -j DROP`).
- Ping positif relancé : **PASS** (rétablissement confirmé).

### Absence d'exposition imprévue
- Aucun `getIP.php` ni `telemetry.php` dans les logs Caddy (confirmé).
- Aucun serveur Internet public contacté.
- Aucun accès à l'API d'administration ZLT.
- Aucun credential routeur transféré.

## 5. Budget effectif (build réduit)

| Paramètre | Valeur |
|---|---|
| `SPEEDTEST_BASE_URL` | `https://192.168.1.174:8443` |
| `SPEEDTEST_MAX_DOWNLOAD_BYTES` | 1 048 576 (1 Mio) |
| `SPEEDTEST_MAX_UPLOAD_BYTES` | 262 144 (256 Kio) |
| `SPEEDTEST_MAX_DURATION_MILLIS` | 10 000 (10 s) |
| Sondes latence | 5 |
| Total applicatif maximal | 1 310 720 octets (1,25 Mio) |
| Plafonds de production | inchangés (20 Mio / 10 Mio / 30 s) |

## 6. Résultat du benchmark

### Latence
- 5 sondes (`GET /empty.php`), toutes HTTP 200.
- Latence médiane affichée : **9 ms**.

### Download
- 1 requête `GET /garbage.php?ckSize=1`, HTTP 200.
- Volume serveur : **1 048 576 octets** (1 Mio) — plafond respecté.
- Durée serveur : 0,240 s.
- Débit affiché : **25,3 Mbps (plafonné)**.

### Upload
- 1 requête `POST /empty.php`, HTTP 200.
- Volume serveur : `bytes_read = 262 144` (256 Kio) — plafond respecté.
- Durée serveur : 0,050 s.
- Débit affiché : **19,1 Mbps (plafonné)**.

### Résumé

| Phase | Octets | Durée serveur | Débit affiché | capped |
|---|---|---|---|---|
| Latence | 5 × 0 o | < 0,1 s | 9 ms | n/a |
| Download | 1 048 576 o | 0,240 s | 25,3 Mbps | oui |
| Upload | 262 144 o | 0,050 s | 19,1 Mbps | oui |
| **Total** | **1 310 720 o** | **< 1 s** | — | — |

- État final : **succès** (aucune erreur, aucune annulation, aucun timeout).
- Consommation affichée : 1,0 Mo / 0,3 Mo.
- `capped = true` sur les deux phases (budget atteint).

## 7. Limites métrologiques

1. **Fenêtre Download courte** : 256 Kio de warm-up sur 1 Mio laissent 768 Kio mesurés. Sur ce LAN rapide, la fenêtre dure ~0,24 s. Le chiffre est une **valeur indicative / plancher**, pas la capacité du lien.
2. **Upload de 256 Kio** : le temps client inclut l'établissement de connexion ; le débit est sous-estimé.
3. **`capped` toujours vrai** sur un run réussi : « plafonné » signifie « budget de mesure atteint », pas « lien saturé ».
4. **Chemin local** : serveur et client partagent le même Wi-Fi ; la mesure n'est pas le débit Internet.
5. **Débit applicatif** : les compteurs ignorent TLS/TCP/retransmissions ; ~6 % sous un LibreSpeed navigateur.
6. **Durée réelle** : non affichée dans l'application ; relevée par horodatage et logs Caddy (~0,3 s total côté serveur).
7. **Biais de fenêtre Download** : le départ de fenêtre est pris après la lecture qui franchit 256 Kio (biais positif possible de quelques %), non corrigé.
8. **Résultat précédent** : après un échec, l'écran peut laisser le résultat précédent affiché ; ne pas l'attribuer au run échoué.

Ces limites interdisent de présenter le résultat comme une mesure scientifique du débit Wi-Fi ou Internet.

## 8. Nettoyage et état final

| Contrôle | Résultat |
|---|---|
| Port 8443 | **fermé** (aucun listener, aucun `docker-proxy`) |
| Règles raw PREROUTING 8443 | **supprimées** (aucun résidu) |
| Services 5433/5434/6379 | **intacts** (6 listeners, conteneurs actifs) |
| `adb reverse --list` | vide |
| Override `/tmp/opencode/librespeed-lan.override.yaml` | supprimé |
| Git | `?? assets/` uniquement (26 fichiers préservés) |
| Conteneur LibreSpeed | arrêté (`down`) |
| Conteneur Caddy | arrêté (`down`) |

## 9. Tests de non-régression

- `./gradlew testDebugUnitTest assembleDebug compileReleaseKotlin --offline --rerun-tasks` : **BUILD SUCCESSFUL**, 467 tests, 0 échec.
- PR #11 : 2 checks CI verts (`JVM tests and debug build`, `Offline router scenarios`).
- `main` intacte.

## 10. Conclusion

Le premier benchmark LibreSpeed réel sur le chemin Wi-Fi local est **réussi** :
- Latence 9 ms, Download 25,3 Mbps (plafonné), Upload 19,1 Mbps (plafonné).
- Respect strict des plafonds (1 Mio / 256 Kio / 10 s, total 1,25 Mio applicatif).
- Aucun serveur externe, aucune télémétrie, aucune fuite de credentials.
- Filtrage positif et négatif démontrés.
- Nettoyage complet et prouvé.

Les résultats sont des **valeurs indicatives** (budget de fumée), pas des mesures de capacité. Ils valident le fonctionnement complet du moteur (latence, download, upload, calcul, plafonds, affichage, annulation).

## 11. Actions restantes

- La PR #11 reste ouverte et non fusionnée (autorisation requise).
- Le build réduit a été installé temporairement sur le Pixel ; le code de la branche utilise les plafonds de production par défaut.
- Aucune modification de `main`.
- `assets/` préservé (26 fichiers).