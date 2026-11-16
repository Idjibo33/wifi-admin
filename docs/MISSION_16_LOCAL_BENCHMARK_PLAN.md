# Mission 16 — Plan du premier benchmark LibreSpeed local (Phase A)

Date de préparation : 2026-11-16 (UTC).
Appareil : Pixel 7 Pro, Android 17 (API 37, `2B091FDH3004DG`).
Serveur : Ubuntu, `wlp58s0` `192.168.1.174/24`, routeur `192.168.1.1`.
Branche : `feature/mission-16-local-speedtest-validation`.

**Phase A uniquement.** Aucun port LAN n'a été ouvert, aucune règle de pare-feu
n'a été modifiée, aucun transfert Download/Upload réel n'a été lancé. La Phase B
est bloquée jusqu'à autorisation explicite (voir §11).

---

## 1. Objet et périmètre

Vérifier le fonctionnement complet du moteur LibreSpeed sur le chemin Wi-Fi réel :

1. latence (5 sondes) ;
2. Download borné ;
3. Upload borné ;
4. calcul des résultats ;
5. respect des plafonds ;
6. affichage dans l'application ;
7. annulation et nettoyage.

Ce benchmark mesure le **chemin local** Pixel → point d'accès ZLT → Ubuntu.
Il ne mesure pas le débit Internet du fournisseur d'accès et ne doit pas être
comparé à un test grand public.

---

## 2. État Git vérifié

| Élément | État |
|---|---|
| PR #10 | **MERGED** le 2026-11-16T13:58:47Z |
| HEAD pré-fusion | `50a37b22477e52bcead93b26e36c5387295679cc` (conforme) |
| Commit de fusion (squash) | `c527deb468a6be934d78ebd7ef321c77ef84077b` |
| `develop` local / `origin/develop` | `c527deb` (synchronisés) |
| `main` | inchangée (locale `1103658`, `origin/main` `1bfcf754`) |
| `assets/` | 26 fichiers non suivis, préservés |
| CI sur `c527deb` | 2/2 verts (JVM + scénarios routeur hors ligne) |

---

## 3. Environnement vérifié (2026-11-16)

### Ubuntu

| Élément | Valeur observée |
|---|---|
| Interface | `wlp58s0` |
| Adresse LAN | `192.168.1.174/24` (DHCP) |
| Route par défaut | `192.168.1.1` via `wlp58s0` |
| Docker | 29.9.3, backend iptables, userland proxy actif |
| Ports publiés existants | `0.0.0.0:5433`, `0.0.0.0:5434`, `0.0.0.0:6379` (+ IPv6) — **à ne pas perturber** |
| Port 8443 | **fermé** (aucun listener) |
| Port 8280 | **fermé** |
| UFW | **désactivé** (`/etc/ufw/ufw.conf` → `ENABLED=no`) |
| Conteneur LibreSpeed | `wifiadmin-librespeed-local-speedtest-1` arrêté (`Exited (0)`, 7 jours) |
| Images | `ghcr.io/librespeed/speedtest:6.3.1`, `caddy:2-alpine` présentes |

Conséquence : **UFW ne constitue aucune protection ici.** Le seul contrôle
possible pour un port publié par Docker est `DOCKER-USER` (voir §7).

### TLS

| Élément | Valeur |
|---|---|
| CA | `CN = WiFi Admin Dev CA` |
| Certificat serveur | `CN = localhost`, SAN `IP:127.0.0.1`, `IP:192.168.1.174`, `DNS:localhost` |
| Validité serveur | 2026-11-08 → **2027-02-06** |
| Validité CA | 2026-11-08 → 2027-11-08 |
| `openssl verify` | **OK** |
| `openssl -checkip 192.168.1.174` | **match** |
| CA embarquée debug | `app/src/debug/res/raw/local_ca.crt`, SHA-256 identique à `tls/ca.crt` |
| Portée | `debug-overrides` uniquement ; release sans CA locale ; aucun TrustManager permissif |

### Pixel

| Élément | État |
|---|---|
| Connexion ADB | **non détectée au moment de la rédaction** (à rebrancher pour la Phase B) |
| Dernière IP observée par l'audit | `192.168.1.189/24` sur `wlan0` — **à reconfirmer le jour J** (DHCP) |
| Version | Android 17 / API 37 (vérifié en M15.1) |
| VPN | aucun tunnel actif au dernier contrôle ; le test épingle les sockets sur le Wi-Fi, donc un VPN full-tunnel n'intercepte pas la mesure |
| Permission LAN | phase de recette clôturée à la demande de l'utilisateur (M15.1). Contrôle automatique `dumpsys` au branchement, sans redemander de scénario |

> Les anciennes adresses `192.168.1.115` et `192.168.1.174` ne sont pas
> réutilisées sans vérification : `192.168.1.174` est confirmée aujourd'hui,
> `192.168.1.115` est périmée (le Pixel observé est `.189`).

---

## 4. Audit du moteur (sans trafic)

Fichiers : `SpeedTestPolicy.kt`, `LibreSpeedClient.kt`, `LibreSpeedEngine.kt`,
`SpeedTestEngine.kt` (repository), `AndroidWifiNetworkRouteProvider.kt`,
`SpeedTestCalculator.kt`, `SpeedTestScreen.kt`.

| Point | Constat |
|---|---|
| Plafonds réels | Download arrêté au compteur d'octets applicatifs ; Upload `Content-Length` exact ; durée bornée par le repository (`withTimeout`) ; 5 sondes de latence |
| Latence | 5 GET `empty.php`, **médiane** (+ gigue = moyenne des écarts consécutifs) |
| Download | Un `garbage.php` dimensionné (`ckSize`), lecture par blocs de 64 Kio, arrêt au plafond, 256 Kio de warm-up exclus de la fenêtre |
| Upload | Blocs pseudo-aléatoires, `Content-Length` = plafond, contrôle d'annulation tous les 64 Kio |
| Erreurs | Timeout, serveur injoignable, incomplet, permission, Wi-Fi absent — messages distincts |
| Perte Wi-Fi | `watchNetworkLoss()` annule sur perte **ou** changement d'identité réseau |
| VPN | sockets + DNS épinglés sur le `Network` Wi-Fi : la mesure contourne le tunnel par conception, aucun fallback cellulaire |
| Annuler | `engine.cancel()` ferme tous les `Call` ; le slot single-flight n'est libéré qu'à la fin réelle du job |
| Stockage distant | `TELEMETRY=false`, `DISABLE_IPINFO=true`, `getIP.php` et `telemetry.php` jamais appelés, aucun volume |
| Fuite | aucun log applicatif, aucun credential routeur, aucun accès au protocole ZLT |

### Corrections apportées en Mission 16

1. **Budget réduit debug-only** (`SpeedTestBudget.kt`) : les plafonds peuvent
   être abaissés via des propriétés Gradle, et sont **re-clampés** sur la
   production (20 Mio / 10 Mio / 30 s). Seules les valeurs non positives ou
   supérieures à la production retombent sur celle-ci ; toute valeur positive
   inférieure est acceptée (une valeur minuscule rendrait juste la mesure
   inutile, jamais plus grosse). Impossible d'élargir le budget.
2. **Politique unique** : `WifiAdminApplication` construit **un** `SpeedTestPolicy`
   et l'injecte au moteur **et** au repository. Avant, chacun avait son propre
   `SpeedTestPolicy()` par défaut : une réduction de durée côté repository
   aurait laissé le moteur à 20/10 Mio (incohérence silencieuse).
3. **UI honnête** : la bannière et le dialogue de consentement affichent les
   plafonds réels dérivés de la politique. La bannière montre le **total**
   (`1,25 Mo` en validation), le dialogue détaille les trois plafonds
   (`1 Mo / 256 Ko / 10 s`) avec la mention « budget réduit de validation ».
   Le texte « moteur désactivé » ne cite plus de chiffres codés en dur.
4. **Durée réellement bornée** : `withTimeout` seul ne peut pas préempter une
   lecture OkHttp bloquante ; un **watchdog** appelle désormais `engine.cancel()`
   à l'échéance, ce qui ferme l'appel en vol. Un run est donc rapporté
   `Timeout` au lieu de « Annulé », et la durée murale reste bornée par le
   plafond (les plafonds d'octets restent la limite de sécurité dure).
5. **Tests déterministes** : délais MockWebServer des tests d'annulation ramenés
   de 5 s à 2 s (le `shutdown()` de MockWebServer expirait à 5 s de façon
   intermittente), avec assertion de temps pour prouver la fermeture réelle de
   l'appel.
6. **Caddy durci** : seul le couple `cert.pem`/`key.pem` est monté (plus le
   répertoire `tls/` entier, qui contenait la clé privée de la CA), et
   `no-new-privileges` est appliqué au conteneur exposé.

### Revue indépendante (Phase A)

Une revue read-only du diff a été menée en parallèle des audits. Verdict :
**aucun bloqueur**, deux points majeurs, corrigés avant commit.

| Point relevé | Résolution |
|---|---|
| La durée de 10 s n'était pas préemptive : `withTimeout` seul ne coupe pas une lecture bloquante | Watchdog `engine.cancel()` à l'échéance, classé `Timeout` ; test `deadline cancels a blocking engine` |
| La preuve de build réduit n'existait pas dans l'état courant | Build réduit rejoué, sortie `BuildConfig` consignée en §5 |
| Texte « moteur désactivé » avec plafonds codés en dur | Phrase sans chiffres, dérivée du contexte |
| Test d'annulation ne prouvait pas la fermeture du socket | Assertion de temps (< 1,5 s pour un corps retardé de 2 s) |
| Formatage dépendant de la locale | `Locale.ROOT` + test des bornes (0, 1 023 Kio, 1,5 Mio, 1 048 575 o) |

**Risque résiduel accepté** : les trois littéraux de production sont dupliqués
dans `app/build.gradle.kts` (copie `BuildConfig`) et `SpeedTestPolicy.kt`
(source de vérité). S'ils divergentait à la hausse côté Kotlin, le build release
conserverait les valeurs basses et afficherait « budget réduit » : les plafonds
resteraient **plus stricts**, jamais plus larges. Un commentaire croisé le
signale dans les deux fichiers.

---

## 5. Budget effectif et volume de données

| Paramètre | Production | **Validation M16** |
|---|---|---|
| Download max | 20 Mio | **1 Mio (1 048 576 o)** |
| Upload max | 10 Mio | **256 Kio (262 144 o)** |
| Durée max | 30 s | **10 s** |
| Sondes latence | 5 | 5 |
| **Volume applicatif max / run** | 30 Mio | **1,25 Mio (1 310 720 o)** |
| Volume filaire estimé (TLS/TCP ~+3 %) | ~31 Mio | **~1,29 Mio (1 350 042 o)** |

Ces valeurs sont des **plafonds** : le moteur s'arrête au plafond atteint.
Une seule exécution réelle est prévue ; tout nouveau run nécessitera une
autorisation et un volume additionnel annoncés.

Commande de build de validation :

```bash
./gradlew :app:assembleDebug --offline \
  -Pwifiadmin.speedtestBaseUrl=https://192.168.1.174:8443 \
  -Pwifiadmin.speedtestMaxDownloadBytes=1048576 \
  -Pwifiadmin.speedtestMaxUploadBytes=262144 \
  -Pwifiadmin.speedtestMaxDurationMillis=10000
```

Contrôle avant installation (les valeurs doivent être exactement celles ci-dessus) :

```bash
grep SPEEDTEST_MAX_ \
  app/build/generated/source/buildConfig/debug/dev/wifiadmin/app/BuildConfig.java
```

Preuve produite en Phase A le 2026-11-16 (build réduit, `/tmp/opencode/m16-reduced-build.txt`) :

```
SPEEDTEST_BASE_URL             = "https://192.168.1.174:8443"
SPEEDTEST_MAX_DOWNLOAD_BYTES   = 1048576L
SPEEDTEST_MAX_UPLOAD_BYTES     = 262144L
SPEEDTEST_MAX_DURATION_MILLIS  = 10000L
```

Sans les trois `-P`, la même commande produit `20971520L / 10485760L / 30000L`
(plafonds de production). L'APK installé en Phase B doit être celui construit
avec les `-P` et contrôlé par le `grep` ci-dessus.

---

## 6. Serveur LibreSpeed / Caddy

- Image `ghcr.io/librespeed/speedtest:6.3.1`, `MODE=backend`,
  `TELEMETRY=false`, `DISABLE_IPINFO=true`, aucun volume, `restart: no`,
  `no-new-privileges`.
- Caddy en profil `tls`, certificat explicite (pas d'ACME, pas d'appel sortant).
- Caddy durci : montage de `cert.pem` et `key.pem` uniquement (jamais le
  répertoire `tls/` avec `ca.key`), `no-new-privileges`.
- Par défaut : Caddy publié uniquement sur `127.0.0.1:8443`.
- `DISABLE_IPINFO` + absence d'appel `getIP.php` : aucun résultat, aucune
  adresse IP publique envoyée à un tiers.

Le profil `tls` **ne suffit pas** en l'état : la ligne LAN est commentée dans
`compose.yaml`. L'ouverture se fera par un fichier override **hors dépôt**
(`/tmp/opencode/librespeed-lan.override.yaml`), afin qu'aucune IP DHCP ne soit
versionnée dans un fichier de composition. Le présent document, lui, enregistre
les adresses *observées* comme preuve d'audit.

---

## 7. Protection réseau proposée (Phase B, après autorisation)

### 7.1 Principe

1. Installer la restriction **avant** de publier le port (fail-closed).
2. Publier uniquement sur l'adresse LAN concrète : `192.168.1.174:8443:8443`.
   Jamais `0.0.0.0`, jamais un CIDR.
3. Restreindre les nouvelles connexions à l'IP du Pixel `/32` via `DOCKER-USER`.
   UFW étant désactivé, `DOCKER-USER` est le seul contrôle.
4. Le paquet est déjà DNATé quand il traverse `DOCKER-USER` : `-d 192.168.1.174`
   ne matcherait jamais ; on utilise `--dport 8443` (port conteneur, ici égal au
   port hôte) et `--ctorigdst 192.168.1.174` (adresse réellement appelée).

### 7.2 Commandes exactes, effet, vérification

**Étape 0 — baseline (lecture seule)**

```bash
export ADB=/home/ismael/Android/Sdk/platform-tools/adb
ip -4 -brief addr show wlp58s0          # attendu 192.168.1.174/24
$ADB shell ip -4 addr show wlan0        # relever <PIXEL_IP>
ss -ltn 'sport = :8443'                 # attendu : vide
ss -ltn 'sport = :5433 or sport = :5434 or sport = :6379'   # baseline non-régression
docker ps --format '{{.Names}}\t{{.Ports}}' > /tmp/opencode/m16-ps-before.txt
sudo iptables -S DOCKER-USER > /tmp/opencode/m16-docker-user-before.txt
```

**Étape 1 — règle DROP (avant publication)**

```bash
sudo iptables -I DOCKER-USER 1 -p tcp --dport 8443 \
  -m conntrack --ctstate NEW ! -s <PIXEL_IP>/32 \
  -m conntrack --ctorigdst 192.168.1.174 \
  -m comment --comment wifiadmin-m16-drop -j DROP
sudo iptables -L DOCKER-USER -n -v --line-numbers
```

- **Effet attendu** : aucune nouvelle connexion vers `.174:8443` hors Pixel.
  Inerte tant que le port n'écoute pas.
- **Pas de règle ACCEPT** : le DROP ne vise que `NEW` et épargne le Pixel ;
  le trafic établi n'est pas concerné.
- **Vérification** : règle en position 1, compteur à 0.
- **Risque** : si `<PIXEL_IP>` est faux, la mesure échoue **fermée** (timeout),
  jamais ouverte.
- **Rollback** : voir §7.5.

**Étape 2 — publication LAN (override hors dépôt)**

```bash
cat > /tmp/opencode/librespeed-lan.override.yaml <<'EOF'
services:
  caddy:
    ports:
      - "192.168.1.174:8443:8443"
EOF

docker compose -f infra/librespeed-local/compose.yaml \
  -f /tmp/opencode/librespeed-lan.override.yaml --profile tls config \
  | grep -A3 'host_ip: 192.168'

docker compose -f infra/librespeed-local/compose.yaml \
  -f /tmp/opencode/librespeed-lan.override.yaml --profile tls up -d
```

- **Effet attendu** : deux bindings Caddy, `127.0.0.1:8443` (validation locale)
  et `192.168.1.174:8443` (Pixel). Aucun `0.0.0.0`.
- **Vérification** :

```bash
ss -ltn 'sport = :8443'                              # uniquement 192.168.1.174:8443
ss -ltn 'sport = :8443' | grep -c '0.0.0.0\|\[::\]'  # attendu 0
docker inspect wifiadmin-librespeed-local-caddy-1 \
  --format '{{json .NetworkSettings.Ports}}'
sudo iptables -t nat -S DOCKER | grep 8443           # DNAT vers 172.x.y.z
```

**Étape 3 — contrôle TLS local (ne prouve pas le filtrage)**

```bash
curl -sS -o /dev/null -w '%{http_code} %{size_download}\n' \
  --cacert infra/librespeed-local/tls/ca.crt \
  https://192.168.1.174:8443/empty.php?r=12345       # attendu 200 0
curl -sSI --cacert infra/librespeed-local/tls/ca.crt \
  https://192.168.1.174:8443/empty.php?r=12345 | head -5   # aucun 3xx
```

> Ce `curl` part de l'hôte : il traverse `OUTPUT`, pas `DOCKER-USER`. Il valide
> le certificat et l'absence de redirection, **pas** la restriction réseau.

**Étape 4 — contrôle négatif (obligatoire pour dire que le filtre fonctionne)**

1. Test instrumenté positif (un seul ping, aucun débit) :

```bash
./gradlew :app:connectedDebugAndroidTest \
  -Pandroid.testInstrumentationRunnerArguments.class=dev.wifiadmin.app.data.speedtest.SpeedTestHttpsPingInstrumentedTest \
  -Pwifiadmin.speedtestBaseUrl=https://192.168.1.174:8443
```

2. Basculer temporairement le DROP sur une adresse bidon :

```bash
sudo iptables -R DOCKER-USER 1 -p tcp --dport 8443 \
  -m conntrack --ctstate NEW ! -s 192.168.1.254/32 \
  -m conntrack --ctorigdst 192.168.1.174 \
  -m comment --comment wifiadmin-m16-drop -j DROP
```

3. Relancer le même test : il doit **échouer** (connexion refusée/timeout), et
   le compteur DROP doit s'incrémenter (`sudo iptables -L DOCKER-USER -n -v`).
4. Restaurer la règle avec `<PIXEL_IP>` et revérifier le succès.

Si le contrôle négatif ne peut pas être exécuté, le filtrage sera déclaré
**non prouvé** (pas « vérifié ») dans le rapport.

### 7.3 Ce que la règle ne couvre pas

- Le trafic **de l'hôte Ubuntu lui-même** (OUTPUT) n'est pas filtré — c'est
  voulu, il sert à la validation TLS locale.
- Les autres conteneurs/VM du poste (bridges) sont couverts par le DROP sans
  `-i`, mais une compromission de l'hôte reste hors modèle.
- Les règles `DOCKER-USER` disparaissent au redémarrage de Docker : à
  réappliquer à chaque fenêtre.

### 7.4 Risques

| Risque | Réponse |
|---|---|
| IP DHCP du Pixel changée | La règle échoue **fermée** ; relever l'IP avant la fenêtre |
| IP Ubuntu changée | SAN TLS invalide et binding impossible : régénérer le certificat serveur avant, jamais désactiver TLS |
| Port conteneur ≠ port hôte | `--dport` matcherait mal ; ici `8443:8443`, à revérifier dans `docker inspect` |
| Redémarrage Docker | règles perdues → revérifier `iptables` après tout `up` |
| UFW désactivé | aucune protection de secours ; `DOCKER-USER` est l'unique contrôle |
| Logs Caddy | conservent l'IP du Pixel jusqu'au `down` ; à traiter comme donnée personnelle |
| Exposition préexistante | `5433/5434/6379` restent publiés sur `0.0.0.0` (hors périmètre, non modifiés) |

### 7.5 Nettoyage et preuve de fermeture

Ordre impératif : **fermer le port avant de retirer les règles**.

```bash
# 1) arrêt de la pile -> le port disparaît
docker compose -f infra/librespeed-local/compose.yaml \
  -f /tmp/opencode/librespeed-lan.override.yaml --profile tls down

# 2) preuve de fermeture
ss -ltn 'sport = :8443'                       # vide
pgrep -af docker-proxy | grep 8443            # vide
docker ps -a --filter name=wifiadmin-librespeed-local

# 3) suppression de la règle (spec exacte)
sudo iptables -D DOCKER-USER -p tcp --dport 8443 \
  -m conntrack --ctstate NEW ! -s <PIXEL_IP>/32 \
  -m conntrack --ctorigdst 192.168.1.174 \
  -m comment --comment wifiadmin-m16-drop -j DROP

# 4) absence de résidu
sudo iptables -S DOCKER-USER | grep -c 8443          # 0
sudo iptables -S DOCKER-USER | grep -c wifiadmin-m16 # 0
sudo iptables -S DOCKER-USER > /tmp/opencode/m16-docker-user-after.txt
diff /tmp/opencode/m16-docker-user-before.txt /tmp/opencode/m16-docker-user-after.txt
sudo iptables -t nat -S DOCKER | grep -c 8443        # 0

# 5) non-régression des autres services
ss -ltn 'sport = :5433 or sport = :5434 or sport = :6379'   # toujours 6 lignes
docker ps --format '{{.Names}}\t{{.Ports}}' | diff /tmp/opencode/m16-ps-before.txt -

# 6) aucun tunnel ADB
$ADB reverse --list
rm -f /tmp/opencode/librespeed-lan.override.yaml
git status --porcelain                        # uniquement ?? assets/
```

---

## 8. Contrôle obligatoire avant benchmark

| Contrôle | Statut Phase A |
|---|---|
| PR #10 intégrée | **OK** (`c527deb`, CI 2/2) |
| Build debug disponible | **OK** (assembleDebug vert ; build réduit construit et `BuildConfig` contrôlé, §5) |
| Pixel connecté au Wi-Fi | **À vérifier** (ADB non détecté à la rédaction) |
| Permission LAN accordée | **À vérifier automatiquement** au branchement (`dumpsys`) ; phase manuelle clôturée |
| VPN compatible | **À vérifier** au branchement (aucun tunnel au dernier contrôle ; la mesure est épinglée Wi-Fi) |
| Certificat TLS valide | **OK** (verify OK, SAN `.174`, exp. 2027-02-06) |
| Configuration Caddy prête | **OK** (profil `tls`, images présentes, override LAN prêt) |
| Filtrage réseau préparé | **OK en plan** ; application + contrôle négatif = Phase B |
| Budget de transfert limité | **OK** (1 Mio / 256 Kio / 10 s, re-clampé ; preuve §5) |
| Annulation vérifiée | **OK en tests JVM** (fermeture d'appel mesurée) ; vérification UI pendant le run |
| Plan de rollback complet | **OK** (§7.5) |

Un contrôle essentiel non satisfait en Phase B = arrêt avant tout transfert.

---

## 9. Limites métrologiques (à assumer dans le rapport)

1. **Fenêtre Download courte** : 256 Kio de warm-up sur 1 Mio laissent 768 Kio
   mesurés. Sur un LAN rapide, la fenêtre dure quelques centaines de ms. Le
   chiffre est une **valeur indicative / plancher**, pas la capacité du lien.
2. **Upload de 256 Kio** : le temps inclut l'établissement de connexion ; le
   débit est sous-estimé, surtout sur un lien rapide.
3. **`capped` toujours vrai** sur un run réussi : « plafonné » signifie
   « budget de mesure atteint », pas « lien saturé ».
4. **Chemin local** : serveur et client partagent le même Wi-Fi ; la mesure
   n'est pas le débit Internet.
5. **Bon débit applicatif** : les compteurs ignorent TLS/TCP/retransmissions ;
   pas de facteur 1,06, donc ~6 % sous un LibreSpeed navigateur.
6. **Durée réelle non affichée dans le résultat** : le dialogue de consentement
   annonce la durée plafond, mais l'écran de résultat ne montre pas la durée
   mesurée ; elle sera relevée par horodatage et logs Caddy. Un dépassement
   pathologique reste possible de quelques instants (libération des sockets),
   mais le watchdog ferme l'appel à l'échéance et les octets restent plafonnés.
7. **Biais de fenêtre Download** : le départ de fenêtre est pris après la
   lecture qui franchit 256 Kio (biais positif possible de quelques %), non
   corrigé dans cette mission.
8. **Résultat précédent** : après un échec, l'écran peut laisser le résultat
   précédent affiché ; ne jamais l'attribuer au run échoué.

Ces limites sont documentaires : elles ne nécessitent pas de modification du
moteur pour un premier test de fumée, mais interdisent de présenter le résultat
comme une mesure scientifique.

---

## 10. Procédure Phase B (après autorisation seulement)

1. Relever l'IP Ubuntu et l'IP Pixel ; vérifier le SAN TLS et les ports.
2. Capturer la baseline `DOCKER-USER` + `docker ps` + `ss`.
3. Installer la règle DROP (§7.2 étape 1), vérifier.
4. Publier via l'override (§7.2 étape 2), vérifier `ss`/`inspect`.
5. Contrôle TLS local (§7.2 étape 3).
6. Contrôle positif + négatif du filtre (§7.2 étape 4).
7. Construire et installer le build debug à budget réduit (§5), vérifier
   `BuildConfig`.
8. Lancer `adb shell dumpsys package dev.wifiadmin.app | grep ACCESS_LOCAL_NETWORK`
   et confirmer l'absence de VPN (`ip link`), sans solliciter de recette manuelle.
9. **L'utilisateur déclenche lui-même** un unique test dans l'application.
10. Relever : latence, Download, Upload, consommation, `capped`, durée observée,
    logs Caddy (`garbage.php` / `empty.php`), absence de `getIP`/`telemetry`.
11. Vérifier l'annulation (un tap « Annuler » pendant un second run **uniquement
    si autorisé**, sinon l'annulation est couverte par les tests JVM).
12. Nettoyer immédiatement (§7.5) et produire
    `docs/MISSION_16_LOCAL_BENCHMARK_REPORT.md`.

Aucun retry automatique, aucune exécution en arrière-plan, aucun second run
sans autorisation et volume additionnel annoncés.

---

## 11. Demande d'autorisation

**« Autorisez-vous l'ouverture temporaire et protégée de LibreSpeed sur le LAN
ainsi que l'exécution d'un benchmark réel Download/Upload limité aux volumes
indiqués ? »**

Tant que cette autorisation n'est pas donnée explicitement, la Phase B ne
démarre pas : aucun port ouvert, aucune règle de pare-feu, aucun transfert.
