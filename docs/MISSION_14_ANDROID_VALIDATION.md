# Mission 14 — Validation Android / connexion Pixel 7 Pro

> Date : 2026-11-08 · Appareil : Pixel 7 Pro (`cheetah`, via ADB)
> Principe : le vrai moteur LibreSpeed est validé **sans** trafic Internet réel,
> **sans** désactivation TLS, **sans** ouvertures pare-feu non autorisées.

## 1. Le problème de `127.0.0.1`

`127.0.0.1` est la boucle locale de la machine qui émet la requête : sur le Pixel,
`http://127.0.0.1:8280` vise le Pixel, pas l’ordinateur Ubuntu. Il faut donc un
pont entre les deux appareils. Deux chemins reproductibles sont livrés ;
le LAN direct est documenté mais **jamais ouvert sans autorisation explicite**.

## 2. Choix du réseau (Wi-Fi, sans `bindProcessToNetwork`)

- `AndroidWifiNetworkRouteProvider` lie chaque run LibreSpeed au Wi-Fi validé par
  `AndroidNetworkMonitor` : `socketFactory = network.socketFactory` **et**
  `dns = network.getAllByName` (même `Network`). Sans Wi-Fi → `NoWiFi` **avant**
  tout octet (`LibreSpeedEngine.kt`, `SpeedTestRoutingTest` : `requestCount==0`).
- Aucun `bindProcessToNetwork` (le trafic routeur reste sur son propre réseau).
  Preuve : `grep bindProcessToNetwork app/` = 0 occurrence de code (seuls commentaires de rejet).
- Le Pixel est sur le même sous-réseau que le routeur (`192.168.1.0/24`) :
  dumpsys a montré le réseau Wi-Fi `WIFI CONNECTED` avec l’IP du téléphone sur ce sous-réseau.
  Ubuntu est joignable sur son IP LAN (exemple de labo `192.168.1.174/24` sur `wlp58s0`) —
  **cette valeur est un exemple, jamais codée en dur**. Pour le LAN direct,
  `SpeedTestEndpointValidator` impose **HTTPS** (`http` hors loopback rejeté) :
  `https://[IP-LAN]:8443` est accepté, `http://[IP-LAN]` est rejeté.

## 3. Voie A — `adb reverse` (recommandée, sans pare-feu)

Boucle locale Pixel ↔ Ubuntu par tunnel ADB, aucune règle pare-feu, aucune CA :

```bash
export ADB=/home/ismael/Android/Sdk/platform-tools/adb
$ADB reverse tcp:8280 tcp:8280
$ADB reverse --list          # → UsbFfs tcp:8280 tcp:8280 attendu
$ADB shell "nc -w 2 127.0.0.1 8280 </dev/null && echo open"   # → open observé (2026-11-08)
```

L’app debug (`SPEEDTEST_BASE_URL=http://127.0.0.1:8280` par défaut en debug) atteint
ainsi le backend Ubuntu sans passer par Internet ni par le routeur.
Nettoyage : `$ADB reverse --remove tcp:8280` (ou expiration à la prochaine connexion ADB).

## 4. Voie B — HTTPS dev avec CA locale (profil `tls`, après autorisation de pare-feu)

LibreSpeed n’a pas de TLS natif : la terminaison est faite par Caddy (`caddy:2-alpine`)
qui fait `reverse_proxy speedtest:8080`. La CA est **locale, debug-only** :

1. Génération (clés privées **jamais** commitées) :

   ```bash
   LAN_IP=[IP-LAN] infra/librespeed-local/generate-local-ca.sh
   # Vérifier : openssl x509 -in infra/librespeed-local/tls/cert.pem -noout -text | grep -A1 "Subject Alternative Name"
   # → IP:127.0.0.1, IP:[IP-LAN], DNS:localhost attendu
   cp infra/librespeed-local/tls/ca.crt app/src/debug/res/raw/local_ca.crt
   git add app/src/debug/res/raw/local_ca.crt   # certificat PUBLIC uniquement
   ```

2. Android debug-only : `app/src/debug/res/xml/network_security_config.xml`
   (`debug-overrides` trustant `@raw/local_ca` **+** `system`, **jamais** `trust-all`)
   + `app/src/debug/AndroidManifest.xml` (merge déclarant
   `android:networkSecurityConfig="@xml/network_security_config"`). La release
   n’embarque jamais cette CA ni ce fichier. Aucun `TrustManager` permissif,
   aucun `HostnameVerifier` custom, aucun `sslSocketFactory` forcé.

3. Démarrage (boucle d’abord, LAN seulement après accord) :

   ```bash
   docker compose --profile tls -f infra/librespeed-local/compose.yaml up -d
   curl --cacert infra/librespeed-local/tls/ca.crt https://127.0.0.1:8443/empty.php?r=12345 -v
   # LAN seulement après autorisation : ports en [IP-LAN]:8443:8443 puis
   # sudo ufw allow from 192.168.1.0/24 to any port 8443 proto tcp comment 'WiFi Admin LibreSpeed dev'
   ```

   Résultat observé sur la boucle (2026-11-08) : `curl --cacert … → code=200`,
   `sans CA → code=000 "unable to get local issuer certificate"` (échec attendu),
   `openssl s_client … Verify return code: 0 (ok)` avec `subject=CN=localhost, issuer=CN=WiFi Admin Dev CA`.
   Le profil `tls` **n’a pas été laissé démarré** (`down` + `up` backend seul après test ;
   `ss` ne montre plus `8443`, uniquement `127.0.0.1:8280`).

4. Fermeture explicite (à exécuter en fin de fenêtre) :

   ```bash
   docker compose --profile tls -f infra/librespeed-local/compose.yaml down
   sudo ufw status numbered   # relever le <num> puis :
   sudo ufw delete <num>
   sudo ufw status numbered   # confirmer la suppression
   ```

**Aucune ouverture LAN n’a été réalisée pendant la Mission 14.** Deux décisions
restaurent la posture sûre : (a) le profil TLS n’écoute que sur `127.0.0.1` tant
qu’il n’est pas explicitement ré-ouvert, (b) la **release reste désactivée** :
`defaultConfig.SPEEDTEST_BASE_URL == ""` → `DisabledSpeedTestEngine` → zéro octet.

## 5. Configuration de WiFi Admin

`app/build.gradle.kts` (fichier livré) :

```kotlin
val speedtestDebugUrl: String =
    (findProperty("wifiadmin.speedtestBaseUrl") as? String)?.takeIf { it.isNotBlank() }
        ?: System.getenv("SPEEDTEST_URL_DEBUG")?.takeIf { it.isNotBlank() }
        ?: "http://127.0.0.1:8280"

defaultConfig { buildConfigField("String","SPEEDTEST_BASE_URL","\"\"") }   // release et défaut
buildTypes { debug { buildConfigField("String","SPEEDTEST_BASE_URL","\"$speedtestDebugUrl\"") } }
```

Vérifications (2026-11-08) :
- `app/build/generated/source/buildConfig/debug/.../BuildConfig.java` :
  `SPEEDTEST_BASE_URL = "http://127.0.0.1:8280"` (debug, attendu via `adb reverse`).
- Aucune URL codée en dur dans `data/` (`git grep "https://" data/speedtest/` vide
  hors fixtures) ; `SpeedTestEndpointValidator` reste inchangé (HTTPS sauf loopback).

Pour la Voie B, construire avec :
`./gradlew :app:assembleDebug -Pwifiadmin.speedtestBaseUrl=https://[IP-LAN]:8443`.

## 6. Validation fonctionnelle (sans trafic Internet réel)

### 6.1 Protocole et métrologie (JVM, MockWebServer + loopback uniquement)

Les tests existants (sans aucun appel Internet) couvrent exactement les critères
Mission 14 §8 — et les ont tous passés le 2026-11-08 avec le code de cette branche :

| Critère | Couverture | Preuve |
|---|---|---|
| Client Kotlin vers serveur contrôlé, `providerLabel` host seul | `LibreSpeedEngineTest:94-99` | vert |
| Download / Upload (octets réels) | `LibreSpeedEngineTest:101-119`, `LibreSpeedUploadTest:75-106` | vert |
| Latence (médiane de 5) et jitter | `SpeedTestCalculatorTest` (attente littérale `5.0` sur `[20,22,25,30,40]`) | vert |
| Gestion des erreurs | `LibreSpeedEngineTest:134-143` (503 → `ServerUnavailable`), corps vide → `Incomplete` jamais `0.0 Mbps` | vert |
| Annulation | `LibreSpeedEngineTest:145-171`, `LibreSpeedUploadTest:108-206` (latches déterministes, pas de `sleep`) | vert |
| Deux tests successifs | `SpeedTestReuseTest:98-168` (2 runs → `Done`×2, cancel entre runs → `Done`) | vert |
| Perte du réseau Wi-Fi | `SpeedTestNetworkLossRepoTest:96-133` (wifiA→wifiB → `Cancelled`, slot libéré) | vert |
| Plafonds `20 Mo / 10 Mo / 30 s` | `LibreSpeedEngineTest:121-132` (plafond exact + `capped`), `SpeedTestRepositoryBackstopTest` | vert |
| Aucun fallback mobile | `SpeedTestRoutingTest:101-158` (`null → NoWiFi`, `requestCount==0`, 2 runs → 2 résolutions) | vert |
| Isolation moteur ↔ routeur | `Mission12BoundaryTest` (aucun import `ZltRouterClient`, aucun `https://` en dur) + `git diff origin/develop...HEAD router-core/` = vide | vert |

Détails métrologiques (formule `×1.0`, warm-up 256 Kio, `ckSize`, plafonds en flux
+ backstop repository `withTimeout(30 s)`) : voir `docs/MISSION_13_METROLOGY.md`
et `docs/MISSION_13_CORRECTION_REPORT.md` (lus par le sous-agent Tests).

### 6.2 Commandes et résultats (CI : aucun serveur Internet, aucun benchmark)

```text
./gradlew :router-core:test :poc-cli:test :app:testDebugUnitTest
→ BUILD SUCCESSFUL in 27s (34 tâches, dont 44 classes de test app ; 22 XML router-core/poc-cli)

./gradlew :app:assembleDebug
→ BUILD SUCCESSFUL in 3s (APK debug 22 Mo ; BuildConfig debug = http://127.0.0.1:8280)

./gradlew :poc-cli:installDist && python3 tools/fake_router_scenarios.py
→ 10/10 ok, « no write was attempted »
python3 tools/fake_router_expiry_scenarios.py
→ 3/3 ok (renew-refused, renew-ok, renew-cooldown)

./gradlew :app:connectedDebugAndroidTest -Pandroid.testInstrumentationRunnerArguments.class=dev.wifiadmin.app.SpeedTestInstrumentedTest
→ BUILD SUCCESSFUL in 25s, Running tests on Pixel 7 Pro - 17 (Fake engine, zéro réseau)
```

La CI (`.github/workflows/ci.yml`) ne fait **jamais** de benchmark ni d’appel
a un serveur Internet : seuls `testDebugUnitTest`, `assembleDebug` et les
scénarios FakeRouter loopback y sont exécutés (aucun job `connectedDebugAndroidTest`).

### 6.3 Sur Pixel 7 Pro

- APK debug installé et instrumentés verts **sans trafic réel** (moteur fake :
  boutons, bannière, jitter, `Latence sous charge : indisponible`, consentement
  explicite avant le 1ᵉʳ test — `SpeedTestInstrumentedTest`, 12 tests).
- Liaison Wi-Fi visible (`dumpsys connectivity` : SSID du labo, `11ax`, RSSI -63,
  bande 2,4 GHz) et messages de connexion déjà couverts par les instrumentés.
- **Aucun test de débit réel (jusqu’à 30 Mo) n’a été lancé sur le Pixel.**
  Autorisation explicite requise avant : build debug avec BASE_URL,
  `adb reverse` actif, consentement utilisateur, mention `jusqu’à 30 Mo/test`,
  et exposition IP (le serveur voit l’IP publique du Wi-Fi pendant la mesure).

Plan de capture (à exécuter **avec** autorisation, inchangé depuis Mission 12) :
`capture_ready_light`, `capture_running`, `capture_result_dark`, `capture_disabled_dark`
via `SpeedTestInstrumentedTest` (sans réseau). Ne pas lancer de preuve de débit
sans accord daté et traçé.

## 7. Sécurité (en bref)

- TLS **jamais désactivé** : pas de `TrustManager` permissif, pas de `HostnameVerifier`
  custom, pas de `cleartext` pour la mesure (`networkSecurityConfig` debug-only,
  `debug-overrides` = CA locale + système). `followRedirects(false)` déjà forcé.
- Clés privées hors git (`.gitignore` + `tls/` local) ; seule la CA publique
  (`local_ca.crt`) est versionnée en debug.
- Aucun élargissement release : 3 permissions inchangées (`INTERNET`,
  `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK`), `allowBackup=false`.
  Le validateur impose HTTPS sauf loopback : un `http://[IP-LAN]` serait rejeté.

Voir `docs/MISSION_13_SECURITY_REVIEW.md`, `docs/MISSION_13_CORRECTION_REPORT.md`
(CORR-01/CORR-02) et le rapport du sous-agent Sécurité pour la check-list PR.
