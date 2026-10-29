# Protocole du routeur ZLT (W304VA PRO / X17M)

> **Objet** : document de référence du protocole HTTP/CGI du routeur **ZLT W304VA PRO (IDU) / ZLT X17M (interface)**, firmware **6.07.0**, passerelle **192.168.1.1**, application officielle **TZLink**.
> Ce document ne décrit que les **commandes en lecture seule** utilisées par la phase 1 du projet `wifi-admin` : **232, 100, 223, 224, 225**. Toute autre commande citée ici est explicitement signalée comme **hors périmètre**.
> La source de vérité du protocole est le dépôt de référence `reference-zlt` (client Python `desktop/router_client.py` et port Kotlin `app/.../ZltRouterClient.kt`). Les numéros de ligne cités dans ce document renvoient à ce dépôt.

---

## 1. Transport

Toutes les commandes passent par un **unique point d'entrée HTTP**, et **toutes sont des `POST`** :

```http
POST /cgi-bin/http.cgi HTTP/1.1
Host: 192.168.1.1
Content-Type: application/json; charset=UTF-8
User-Agent: Mozilla/5.0 (Windows NT 10.0; Win64; x64) ZLT-Monitor/2.0

{ ... corps JSON ... }
```

Points importants :

| Élément | Valeur / règle | Source |
|---|---|---|
| URL | `http://{host}/cgi-bin/http.cgi` (`{host}` par défaut `192.168.1.1`) | `desktop/router_client.py:19`, `app/.../ZltRouterClient.kt:101-102` |
| Méthode HTTP | **Toujours `POST`**, y compris pour les lectures | `desktop/router_client.py:66-76`, `app/.../ZltRouterClient.kt:48-58` |
| `Content-Type` | `application/json; charset=UTF-8` | `desktop/router_client.py:71`, `app/.../ZltRouterClient.kt:46,52` |
| Corps | JSON ; le champ `method` est **dans le corps JSON**, pas dans l'en-tête HTTP | `desktop/router_client.py:82,114`, `app/.../ZltRouterClient.kt:132,185-187` |
| Réponse | JSON ; en cas d'erreur HTTP non 2xx, le client de référence fabrique `{"error": "HTTP <code>"}` | `app/.../ZltRouterClient.kt:61-63` |

Le champ `method` porte la **verbe métier** de la commande (`GET` ou `POST`) et ne doit pas être confondu avec le verbe HTTP, toujours `POST`.

---

## 2. Authentification

### 2.1 Étape 1 — récupérer le jeton de défi (`cmd 232`)

Requête :

```json
{ "cmd": 232, "method": "GET", "sessionId": "" }
```

Réponse attendue :

```json
{ "success": true, "token": "<jeton de défi>" }
```

* Le `sessionId` est **vide** pour cette requête : c'est une étape *pré-session*.
* Le champ `token` est un **jeton à usage unique** challenges : il change à chaque appel.

> Source : `desktop/router_client.py:81-85`, `app/.../ZltRouterClient.kt:130-138`.

### 2.2 Étape 2 — calculer le hash du mot de passe

```text
passwd = SHA-256( token + password )   ->  hexadécimal minuscule
```

Règles strictes :

* **Aucun sel** n'est ajouté.
* **L'ordre est capital** : le **jeton d'abord**, le mot de passe ensuite. `SHA-256(password + token)` est faux.
* La sortie est de l'**hexadécimal minuscule** sur 64 caractères.
* L'encodage d'entrée est **UTF-8**.

> Source : `desktop/router_client.py:87-88` (`hashlib.sha256((token + self.password).encode('utf-8')).hexdigest()`), `app/.../ZltRouterClient.kt:140-141` et `:1127-1131` (`sha256`), `app/.../ZltRouterClientTest.kt:77-84` (test : SHA-256 de `token+password`).

### 2.3 Étape 3 — s'authentifier (`cmd 100`)

Requête :

```json
{
  "cmd": 100,
  "method": "POST",
  "sessionId": "<session id généré par le client>",
  "username": "<utilisateur>",
  "passwd": "<sha256(token + password) en hex minuscule>",
  "isAutoUpgrade": "0",
  "subcmd": 0
}
```

* `isAutoUpgrade` est la **chaîne** `"0"`, pas le nombre `0`.
* `subcmd` est l'entier `0`.

**Le `sessionId` est généré par le client** : 64 caractères hexadécimaux, soit **deux UUID sans tirets concaténés** (2 × 32 = 64).

> Source : `desktop/router_client.py:89` (`uuid.uuid4().hex + uuid.uuid4().hex`), `app/.../ZltRouterClient.kt:142-143` (`UUID.randomUUID().toString().replace("-", "")` × 2).

### 2.4 Détection du succès de la connexion

> **Corrigé le 29/10/2026 après observation réelle.** L'ancienne règle
> (« `success: true` suffit ») était **fausse** sur ce firmware : un mot de
> passe erroné renvoie `success: true`. Elle laissait donc passer l'écran de
> connexion avec de faux identifiants.

Un mot de passe erroné produit exactement cette réponse (relevé réel, valeurs
non secrètes) :

```json
{"cmd": 100, "login_fail": "fail", "login_times": "1", "success": true}
```

La connexion n'est donc **acceptée** que si les deux conditions suivantes sont
réunies :

| # | Condition |
|---|---|
| 1 | la réponse **ne porte pas** de marqueur d'échec `login_fail` (valeur autre que `success`, `ok`, `0`, `false`) |
| 2 | la réponse porte un **signal positif** : un `sessionId` **non vide**, ou `"message": "success"` |

`"success": true` **seul ne suffit jamais** : pendant un gel, le routeur répond
`{"cmd": 100, "success": true}` sans aucune session.

### 2.4bis Gel après plusieurs échecs

La réponse à `cmd 232` porte deux champs liés au gel :

```json
{"cmd": 232, "success": true, "token": "…", "buffer": "0", "netx_login_time": 37}
```

| Champ | Sens |
|---|---|
| `buffer` | `"0"` pendant la fenêtre de pénalité de **180 s** qui suit un échec |
| `netx_login_time` | **secondes écoulées** depuis l'échec — la valeur **augmente** (vérifié : 844 puis 864 vingt secondes plus tard) |

Le temps d'attente restant est donc `180 - netx_login_time`, **exactement
comme le calcule l'interface web du routeur** (`login.js` : `var times = 180 - data.netx_login_time`).

Quand ce temps restant est **supérieur à 0**, le client **n'envoie pas**
`cmd 100` (une tentative de plus ne ferait que prolonger le gel) et remonte une
`RouterCooldownException` avec le nombre de secondes restantes.

### 2.4ter Verrouillage long

Après plusieurs échecs, le routeur peut aussi répondre à `cmd 100` par un
`{"cmd": 100, "success": true}` **sans `sessionId` ni `login_fail`** : il
refuse sans dire pourquoi ni pour combien de temps. Ce n'est **pas** un mot de
passe erroné. Le client remonte alors une `RouterCooldownException` sans durée,
et l'interface demande de patienter au lieu d'accuser les identifiants.

> Preuve : l'interface web du routeur elle-même (`js/login.js`) ne teste que
> `login_fail == "fail"` / `login_fail2 == "fail"`, puis utilise `data.sessionId`.
> Elle ne se fie **jamais** à `success` seul.

Si la réponse ne contient **pas** de `sessionId`, on **conserve le `sessionId` généré par le client** : c'est lui qui fera foi pour les requêtes suivantes.

### 2.5 Cycle de vie de la session

* Le routeur **n'expose aucun TTL de session explicite**. Il n'existe aucun champ dans les réponses 232/100 qui indique la durée de validité.
* L'expiration se **détecte par le contenu de la réponse** : `message == "NO_AUTH"` ou `message == "LOGIN_TIMEOUT"`.
* Politique appliquée : **se reconnecter, puis répéter la lecture UNE fois**. Si la seconde tentative échoue aussi, l'erreur est remontée telle quelle.

```text
read(cmd)
  ├─ réponse OK              -> retour
  ├─ message NO_AUTH / LOGIN_TIMEOUT
  │     -> login()
  │     -> re-read(cmd)  (UNE seule fois)
  │     -> si échec -> erreur
  └─ erreur réseau (IOException)
        -> re-read(cmd)  (UNE seule fois)  [lecture uniquement]
        -> si échec -> RouterNetworkException
```

> **Au maximum 2 appels réseau par lecture.** La reprise sur erreur réseau est
> sûre ici : la phase 1 n'implémente que des **lectures** (`223`, `224`, `225`),
> dont le rejeu ne fait que dupliquer une requête d'interrogation. Le client de
> référence applique la même règle : il rejoue une lecture, jamais une écriture.

> **Règle de sûreté impérative** : une **écriture ne doit JAMAIS être rejouée à l'aveugle**. Un `POST` peut avoir été appliqué par le routeur avant que la connexion ne casse ; le rejeu doublerait l'action (redémarrage, changement de DNS…). Le client de référence distingue explicitement lectures et écritures à ce sujet (`app/.../ZltRouterClient.kt:190-213`, `app/.../ZltRouterClientTest.kt:100-122`).
> **Dans cette phase, toutes les commandes utilisées sont des lectures** : le risque de rejeu ne concerne donc que l'implémentation future.

---

## 3. Commandes utilisées dans la phase 1

Toutes les requêtes ci-dessous sont des **lectures** : `method` vaut `GET` dans le corps JSON, la méthode HTTP reste `POST`.

### 3.1 Table des commandes

| `cmd` | Nom / usage | Corps de requête | Clés de réponse utilisées |
|---|---|---|---|
| `232` | GET_NEXT_LOGIN_TIME — défi d'authentification | `{"cmd":232,"method":"GET","sessionId":""}` | `success`, `token` |
| `100` | LOGIN — authentification | `{"cmd":100,"method":"POST","sessionId":"<64 hex>","username":"…","passwd":"<sha256 hex>","isAutoUpgrade":"0","subcmd":0}` | `success`, `message`, `sessionId` |
| `223` | Table DHCP / baux | `{"cmd":223,"method":"GET","sessionId":"<session>"}` | `success`, `dhcp_list_info` |
| `224` | Associations Wi-Fi 2,4 GHz | `{"cmd":224,"method":"GET","sessionId":"<session>"}` | `success`, `wlan24g_wifi_info` |
| `225` | Associations Wi-Fi 5 GHz | `{"cmd":225,"method":"GET","sessionId":"<session>"}` | `success`, `wlan5g_wifi_info` |

> Sources : `desktop/router_client.py:82` (232), `:92-100` (100), `:205-207` (223/225/224), `app/.../ZltRouterClient.kt:131-135` (232), `:146-154` (100), `:362-364` (223/225/224).

### 3.2 `cmd 223` — `dhcp_list_info`

Réponse :

```json
{
  "success": true,
  "dhcp_list_info": [
    {
      "mac": "aa:bb:cc:dd:ee:ff",
      "ip": "192.168.1.192",
      "hostname": "pixel-6",
      "interface": "wlan",
      "expires": "3600",
      "ipv6": ""
    }
  ]
}
```

Champs observés dans chaque entrée :

| Champ | Type observé | Description |
|---|---|---|
| `mac` | chaîne | Adresse MAC du client DHCP. Peut être **vide** sur certaines lignes. |
| `ip` | chaîne | Adresse IPv4 attribuée. |
| `hostname` | chaîne | Nom d'hôte. Peut valoir **la chaîne littérale `"NULL"`**. |
| `interface` | chaîne | `wlan` (Wi-Fi) ou `lan` (câblé). |
| `expires` | chaîne | Durée/expiration du bail DHCP. **Format non encore validé.** |
| `ipv6` | chaîne | Adresse IPv6 (souvent vide). |

**Pièges observés** :

* **Toutes les valeurs sont des chaînes JSON**, y compris `expires`. `optString` / `getString` sont donc le bon mode de lecture ; il ne faut jamais supposer un type numérique.
* `hostname` peut être la **chaîne littérale `"NULL"`** (et non `null`). Le client de référence la convertit en `"Unknown Device"` (`desktop/router_client.py:246-250`, `app/.../ZltRouterClient.kt:412-417`). Le port iOS traite `"null"` en casse insensible (`iosApp/.../ZltRouterClient.swift:393`).
* `interface` peut valoir `wlan` **ou** `lan`. Le test de référence utilise `interface: "lan"` pour un poste filaire (`app/.../ZltRouterClientTest.kt:292`).
* **Le format de `expires` n'est pas validé.** Dans les fixtures de référence il apparaît comme `"3600"` (chaîne de secondes), mais le routeur expose aussi une durée de bail `expireTime` au format `"24h"` sur la commande LAN (`app/.../ZltRouterClientTest.kt:372-374`). **Le format réel de `expires` sur le firmware 6.07.0 doit être observé avant toute interprétation** — voir §7.

> Sources : parsing `desktop/router_client.py:241-252` et `:274-291`, `app/.../ZltRouterClient.kt:408-459`, modèle `app/.../data/model/RouterModels.kt:87-103` (`interfaceName`, `expires`, `ipv6`), fixtures `app/.../ZltRouterClientTest.kt:289-293`.

### 3.3 `cmd 224` — `wlan24g_wifi_info`

Réponse :

```json
{
  "success": true,
  "wlan24g_wifi_info": [
    {
      "mac": "aa:bb:cc:dd:ee:ff",
      "ip": "192.168.1.192",
      "rssi": "-60",
      "txrate": "866",
      "rxrate": "866",
      "ssid": "Wifi-2.4G"
    }
  ]
}
```

| Champ | Type observé | Description |
|---|---|---|
| `mac` | chaîne | MAC de l'association radio. |
| `ip` | chaîne | IP vue par le routeur pour cette association. |
| `rssi` | chaîne | Niveau de signal, en dBm (valeur négative). |
| `txrate` | chaîne | Débit PHY en upload. |
| `rxrate` | chaîne | Débit PHY en download. |
| `ssid` | chaîne | SSID (ici en clair pour la liste d'associations). |

### 3.4 `cmd 225` — `wlan5g_wifi_info`

Réponse :

```json
{
  "success": true,
  "wlan5g_wifi_info": [
    {
      "mac": "aa:bb:cc:dd:ee:ff",
      "ip": "192.168.1.192",
      "rssi": "-52",
      "txrate": "1200",
      "rxrate": "1200",
      "ssid": "Wifi-5G"
    }
  ]
}
```

**Mêmes noms de champs que `cmd 224`**, seule la clé du tableau diffère (`wlan5g_wifi_info` au lieu de `wlan24g_wifi_info`).

> Sources : parsing `desktop/router_client.py:220-234`, `app/.../ZltRouterClient.kt:378-396`, fixtures `app/.../ZltRouterClientTest.kt:294-297` (champs `rssi`, `txrate`, `rxrate` pour la bande 5 GHz).

### 3.5 Note sur le SSID

Le SSID des **commandes radio de configuration** (cmd 2 pour le 2,4 GHz, cmd 211 pour le 5 GHz) est **encodé en Base64** et doit être décodé (`app/.../ZltRouterClient.kt:863-871`, `:885-886`, `:890-891` ; fixture Base64 dans `app/.../ZltRouterClientTest.kt:384`).
Dans les listes d'associations `cmd 224` / `cmd 225`, le SSID est repris **tel quel** par le client de référence, sans décodage Base64. **Cela reste à vérifier sur le firmware 6.07.0** : le SSID retourné par `cmd 224`/`cmd 225` peut être en Base64 comme en clair.

---

## 4. Fusion et dé-duplication

### 4.1 Défauts du client de référence que nous corrigeons

Le client de référence (`desktop/router_client.py` et son port Kotlin) construit **une seule table de hachage indexée à la fois par MAC et par IP** :

```python
# desktop/router_client.py:218-234
wifi_map = {}
for w in (wifi5g.get("wlan5g_wifi_info") or []):   # 5 GHz d'abord
    ...
    wifi_map[mac] = {"band": "5 GHz (Wi-Fi 6/ac)", **w}
    if ip:
        wifi_map[ip] = {"band": "5 GHz (Wi-Fi 6/ac)", **w}

for w in (wifi24g.get("wlan24g_wifi_info") or []):   # 2,4 GHz ensuite
    ...
    wifi_map[mac] = {"band": "2.4 GHz (Wi-Fi)", **w}
    if ip:
        wifi_map[ip] = {"band": "2.4 GHz (Wi-Fi)", **w}
```

Défauts constatés :

| # | Défaut | Conséquence |
|---|---|---|
| **D1** | **Une seule table pour le MAC et l'IP**, et la bande **2,4 GHz est traitée après la 5 GHz**. | Si une même IP est presente dans les deux listes (cas réel d'un même hôte associé sur les deux bandes, ou d'une IP réutilisée), **l'entrée 2,4 GHz écrase l'entrée 5 GHz**. Le rapport affiche alors « 2.4 GHz » et perd le débit/ RSSI de la 5 GHz. |
| **D2** | **Aucune dé-duplication de la table DHCP.** | Une même MAC (ou une même IP) apparaissant sur plusieurs lignes DHCP est listée **plusieurs fois**, comme plusieurs appareils. |
| **D3** | La jointure est `wifi_map.get(mac) or wifi_map.get(ip)` (`desktop/router_client.py:252`, `app/.../ZltRouterClient.kt:419`). | Avec l'index unique MAC+IP, une correspondance par IP peut renvoyer l'entrée d'**une autre bande** que celle du MAC. |

Ces défauts sont identiques dans le port Kotlin (`app/.../ZltRouterClient.kt:376-396`, `:419`) et dans le port iOS (`iosApp/.../ZltRouterClient.swift:360-384`, `:395`).

### 4.2 Nos règles de fusion

| # | Règle | Justification |
|---|---|---|
| **R1** | **Jointure par MAC normalisé** (minuscules, espaces retirés). | Le MAC est l'identifiant stable d'un client Wi-Fi. |
| **R2** | **L'IP n'est utilisée qu'en repli**, et **seulement si le MAC ne correspond à rien**. | Une IP peut changer (DHCP) ou être réutilisée ; la MAC est plus fiable. |
| **R3** | **Les deux bandes sont conservées** pour un MAC dual-band : un même MAC présent en 2,4 GHz **et** en 5 GHz donne **deux associations**, pas une seule. | Un même appareil associé aux deux bandes est bien présent deux fois sur le réseau ; écraser l'une des deux (défaut **D1**) perd de l'information. |
| **R4** | **Dé-duplication de la table DHCP par MAC** : au plus une entrée par MAC normalisé. | Corrige **D2**. |
| **R5** | **Les lignes DHCP dont la MAC est vide sont conservées**. | Une entrée sans MAC peut correspondre à un appareil qui n'a pas publié sa MAC ; la supprimer ferait perdre une information réelle du routeur. |

> **Conséquence de R3** : l'identifiant interne d'un appareil n'est pas la MAC seule mais le couple **(MAC, bande)**. La table de sortie contient donc une ligne par association radio.

---

## 5. Règles de classification

Les quatre états possibles sont : `ACTIVE`, `CABLED`, `STALE`, `UNKNOWN`.

| État | Condition | Justification | Limites |
|---|---|---|---|
| **`ACTIVE`** | L'appareil est **présent dans une liste d'associations Wi-Fi** (`wlan24g_wifi_info` ou `wlan5g_wifi_info`). | Le routeur y signale une **association radio courante** : c'est la seule preuve positive d'une connexion Wi-Fi active. | La présence dans la liste prouve une association Wi-Fi, **pas** un trafic en cours. |
| **`CABLED`** | L'entrée DHCP a un `interface` **filaire reconnu** (`lan`, `eth`, `ethernet` — voir §5.0) **ET** l'appareil est **absent** des **deux** listes Wi-Fi. | Un bail DHCP sur interface filaire, sans association radio, correspond à un poste raccordé en Ethernet. | Dépend de la fiabilité du champ `interface` (valeurs `wlan` / `lan` observées). Un `interface` inconnu ne donne **pas** `CABLED` mais `UNKNOWN`. Une association radio non listée par le routeur fausserait ce verdict. |
| **`STALE`** | L'entrée DHCP a un `interface` **Wi-Fi** (`wlan`), l'appareil est **absent des deux** listes Wi-Fi, **ET** `expires` est **objectivement analysable comme déjà expiré**. | Un bail Wi-Fi dont l'échéance est dans le passé et qui n'est plus associé radio décrit un appareil parti. | **Exige de connaître le format de `expires`**, qui n'est pas validé (§7). Tant que le format est inconnu, ce verdict ne peut pas être produit. |
| **`UNKNOWN`** | **Défaut**, pour tout le reste. Y compris : bail DHCP sur interface `wlan`, absent des deux listes Wi-Fi, et dont `expires` est **absent** ou dans un **format inconnu**. | On ne conclut pas ce qu'on ne peut pas prouver. | — |

### 5.0 Précisions d'implémentation sur `interface` et `expires`

Ces deux points sont **implémentés de façon stricte** pour éviter les faux positifs.

**`interface`** — les valeurs sont comparées en minuscules, et les marqueurs **sans fil sont testés avant** les marqueurs filaires, parce que `"wlan"` **contient** la chaîne `"lan"` :

| Catégorie | Marqueurs reconnus | Effet |
|---|---|---|
| Sans fil | `wlan`, `wifi`, `wi-fi`, `wireless` | Sert à `STALE` / `UNKNOWN` |
| Filaire | `lan`, `eth`, `ethernet` | Sert à `CABLED` |
| Inconnu | tout le reste, ou chaîne vide | `UNKNOWN` — **aucune conclusion** n'est tirée |

**`expires`** — seuls les formats **non ambigus** sont acceptés ; tout le reste vaut « inconnu » (donc `UNKNOWN`, **pas** `STALE`) :

| Valeur reçue | Interprétation | Effet |
|---|---|---|
| chaîne vide / absente | inconnue | `UNKNOWN` |
| contient `"expired"` (insensible à la casse) | **expirée** | `STALE` |
| entier `>= 1_000_000_000` (timestamp epoch, en secondes) | timestamp ; `STALE` si `<= now` | `STALE` si passé, sinon `UNKNOWN` |
| entier `< 1_000_000_000` (ex. `"3600"`) | **durée en secondes**, pas un timestamp : ne dit rien sur l'expiration | `UNKNOWN` |
| chaîne non numérique et sans `"expired"` (ex. `"24h"`) | **format inconnu** | `UNKNOWN` |

> Le seuil `1_000_000_000` (2001-09-09) sert de garde-fou : il distingue un **timestamp** d'une **durée**. Sans cette garde-fou, la durée `"3600"` serait lue comme un instant dans le passé et produirait un `STALE` **faux**.
> Cette lecture reste **provisoire** : tant que le format réel de `expires` sur le firmware 6.07.0 n'est pas observé (§7), seule cette liste blanche de formats est acceptée.

### 5.1 Ce que la classification ne dit **pas**

Ces quatre avertissements sont **fondamentaux** et doivent être respectés à la lettre :

1. **L'absence dans les listes Wi-Fi n'est PAS une preuve de connexion active** et **ne rend PAS un appareil « filaire »**. Un appareil Wi-Fi en veille profonde, ou dont le routeur n'a pas encore purgé l'association, disparaît des listes sans être débranché.
2. **Un bail DHCP n'est PAS une preuve de connexion active**. Un bail peut être conservé bien après le départ de l'appareil (jusqu'à l'expiration). La présence en DHCP prouve une **attribution d'adresse**, pas une activité réseau.
3. **Le format de `expires` n'est pas validé** : il ne doit **pas** être supposé. Aucune heuristique « `expires` < maintenant ⇒ périmé » ne doit être codée avant observation du format réel.
4. **Un appareil présent dans les listes Wi-Fi mais absent de la table DHCP** est conservé et classé `ACTIVE` : c'est une association radio observée indépendamment du bail DHCP. Le DHCP n'est pas la source de vérité de la présence Wi-Fi.

---

## 6. Limites du firmware et écarts constatés

| # | Constat | Statut | Source |
|---|---|---|---|
| **F1** | Les valeurs sont **typées en chaînes** (y compris `rssi`, `txrate`, `rxrate`, `expires`). | Constaté sur le firmware de référence | `desktop/router_client.py:132-135`, `:254-257` ; `app/.../ZltRouterClient.kt:254-256`, `:421-424` |
| **F2** | `hostname` peut valoir la **chaîne littérale `"NULL"`**. | Constaté sur le firmware de référence | `desktop/router_client.py:246-250`, `app/.../ZltRouterClient.kt:412-417` |
| **F3** | `dhcp_list_info` est **aussi présent dans la réponse de `cmd 401`** (tableau de bord), utilisé comme repli par les clients de référence. | Constaté ; **hors périmètre de la phase 1** (nous n'utilisons que `cmd 223`) | `desktop/router_client.py:216`, `app/.../ZltRouterClient.kt:371-373` |
| **F4** | **Pas de débit par appareil** : le firmware n'expose aucun compteur par client. | Constaté | `README.md:48` |
| **F5** | **`cmd 25`** (limitation de débit / QoS) est **bloqué pour ce compte** (`LIMITED_ACCESS`). | Constaté ; **hors périmètre** | `desktop/router_client.py:35-37`, `:180` ; `README.md:52` |
| **F6** | **`cmd 401`** : `receiveSpeed` / `sentSpeed` sont **toujours des chaînes vides** sur ce firmware ; le débit WAN est calculé par différence sur les compteurs octets de `cmd 18`. | Constaté ; **hors périmètre** | `desktop/router_client.py:26-27`, `:237` ; `app/.../ZltRouterClient.kt:252-257`, `:398-402` |
| **F7** | L'**écriture `cmd 3`** (configuration LAN / DNS) doit **renvoyer l'intégralité de l'enregistrement LAN** : n'envoyer que les clés DNS réinitialiserait l'IP LAN, le pool et la durée de bail. | Constaté ; **hors périmètre de la phase 1** | `desktop/router_client.py:392-399`, `:444` ; `app/.../ZltRouterClient.kt:563-567`, `:616-623` |
| **F8** | Le **SSID est en Base64** dans les commandes radio de configuration (cmd 2 / cmd 211). | Constaté pour la configuration ; **à vérifier pour `cmd 224`/`cmd 225`** | `app/.../ZltRouterClient.kt:863-871`, `:885-886`, `:890-891` |
| **F9** | **Risque de rejeu sur `POST`** : une écriture peut avoir été appliquée avant la coupure réseau ; elle ne doit pas être rejouée à l'aveugle. | Constaté | `app/.../ZltRouterClient.kt:41-43`, `:190-213` ; `app/.../ZltRouterClientTest.kt:100-122` |
| **F10** | Le **`token` de `cmd 233`** est **porté par la session** : il change à la reconnexion et doit être rafraîchi si la requête est rejouée. | Constaté ; **hors périmètre de la phase 1** (aucune écriture) | `desktop/router_client.py:449`, `app/.../ZltRouterClient.kt:207-210`, `:627-628` |

### 6.1 Portée des constats

| Firmware | Statut |
|---|---|
| **Firmware de référence** (celui observé par le dépôt `reference-zlt`, sur lequel tous les constats **F1–F10** reposent) | Tous les constats **F1–F10** s'appliquent. |
| **Firmware 6.07.0 de l'appareil cible** (ZLT W304VA PRO / X17M) | **Partiellement validé** — voir §7.5. Le format de `expires` reste **à confirmer** ; l'encodage du SSID et les valeurs de `interface` sont validés. |

---

## 7. Validation sur le routeur réel

Cette section décrit la **procedure de validation en lecture seule**, à exécuter sur l'appareil cible (firmware **6.07.0**, passerelle **192.168.1.1**) **avec l'accord explicite de l'utilisateur**.

### 7.1 Commandes qui seront utilisées

Uniquement des lectures. **Aucune commande de modification n'est autorisée.**

| Étape | `cmd` | Rôle | Ce qu'on observe |
|---|---|---|---|
| 1 | `232` | Défi d'authentification | Présence et forme du champ `token`. |
| 2 | `100` | Authentification | Présence éventuelle d'un `sessionId` renvoyé par le routeur ; succès de la connexion. |
| 3 | `223` | Table DHCP | Liste **complète** des champs réellement présents : `mac`, `ip`, `hostname`, `interface`, `expires`, `ipv6`. |
| 4 | `224` | Associations 2,4 GHz | Champs `mac`, `ip`, `rssi`, `txrate`, `rxrate`, `ssid` ; format du SSID (Base64 ou clair). |
| 5 | `225` | Associations 5 GHz | Idem pour la bande 5 GHz. |

### 7.2 Points d'observation prioritaires

1. **Le format réel de `expires`** — est-ce un nombre de secondes (`"3600"`), une durée (`"24h"`), un timestamp absolu, ou une chaîne vide ? **Sans cette réponse, la classe `STALE` ne peut pas être produite.**
2. **Les valeurs de `interface`** — seules `wlan` et `lan` sont attendues ; toute autre valeur rendrait la règle `CABLED` inapplicable telle quelle.
3. **La présence de `hostname` = `"NULL"`** et d'autres pseudo-valeurs.
4. **Le type des valeurs** (chaînes ou nombres) pour `rssi`, `txrate`, `rxrate`.
5. **L'encodage du SSID** dans `cmd 224` / `cmd 225`.
6. **Les MAC vides** dans la table DHCP et les doublons MAC/IP.

### 7.3 Engagement

> **Aucune modification de configuration ne sera effectuée.** Les seules requêtes envoyées seront les commandes 232, 100, 223, 224, 225, toutes en lecture. Aucune commande de redémarrage, de changement de DNS, de filtrage MAC, de bascule Wi-Fi, de verrouillage de bande ou de cellule ne sera envoyée. Aucun journal ne contiendra le mot de passe, le jeton de défi ni le `sessionId`.

### 7.4 Non-divulgation des secrets

Un routeur peut renvoyer un message d'erreur qui **répète** une valeur sensible.
Le client applique donc une **redaction** systematique avant de produire le
message d'une exception :

| Valeur | Comment elle est neutralisée |
|---|---|
| `sessionId` | Retiré explicitement : son format n'est pas contraint par le protocole, il ne peut pas être reconnu par un motif. |
| Jeton de défi (`cmd 232`) | Retiré explicitement, pour la même raison. Le format du jeton **n'est pas** contraint par le protocole : il peut être non hexadécimal. |
| Hash du mot de passe (`SHA-256`) | Retiré par motif : c'est toujours une chaîne hexadécimale d'au moins 16 caractères. |
| Mot de passe en clair | Jamais présent : seul le hash est transmis, et le hash n'est jamais journalisé. |

Les tests `an exception message never carries the session id or a token` et
`an exception message never carries a non hex challenge token echoed by the
router` couvrent ces deux cas, y compris un jeton **non hexadécimal** renvoyé
par le routeur dans son message d'erreur.

### 7.5 Résultats du test réel (phase 1)

> **Statut** : le test a été **exécuté avec succès** par l'utilisateur sur son
> routeur (firmware 6.07.0, `192.168.1.1`), avec le client Kotlin/JVM de la
> phase 1. Les commandes utilisées ont été **uniquement** `232`, `100`, `223`,
> `224`, `225` — toutes en lecture.

#### 7.5.1 Ce qui est confirmé

> Les chiffres ci-dessous proviennent du **diagnostic anonymisé** exécuté par
> l'utilisateur sur son routeur (commande `admin`, sortie `--format
> diagnostic`). Ce format ne contient aucune adresse MAC, IP, hostname, SSID
> ni valeur brute d'`expires` : seuls des compteurs et des catégories.

| Point | Résultat | Conséquence |
|---|---|---|
| Accessibilité réseau | `192.168.1.1:80` joignable (ping ~3 ms, TCP 80 ouvert) | — |
| Interface web du routeur | Vue.js 2.6.10 + Element UI, **page en français** (`Routeur haut débit sans fil 5G/4G`), build du **17 avril 2025** | Le routeur est bien un ZLT/ZTE de la même famille |
| Point d'entrée CGI | `/cgi-bin/http.cgi` confirmé par le code du routeur lui-même (`main.js:317`) | Notre client utilise la bonne URL |
| `cmd 232` (défi) | **Fonctionnel** — un jeton est renvoyé | Le handshake de la phase 1 est correct |
| `cmd 100` (authentification) | **Fonctionnel** — le client a obtenu une session et a pu enchaîner les lectures | `SHA-256(token + password)` + `sessionId` sont acceptés par le firmware |
| `cmd 223` (DHCP) | **Compatible** — une table DHCP exploitable a été renvoyée | Les champs `mac`, `ip`, `hostname`, `interface`, `expires`, `ipv6` sont présents |
| `cmd 224` (2,4 GHz) | **Compatible** — 4 associations 2,4 GHz remontées | Bande 2,4 GHz exploitée |
| `cmd 225` (5 GHz) | **Compatible** — 4 associations 5 GHz remontées | Bande 5 GHz exploitée |
| Lecture des appareils | **Fonctionnelle** — **9 appareils** détectés sur le réseau réel | Le POC de la phase 1 est validé de bout en bout |
| Sécurité du client | Aucun mot de passe, jeton ni `sessionId` dans les sorties | Confirmé par les tests unitaires et l'inspection des sorties |

**Commandes effectivement envoyées lors de ce test** (relevé par le
diagnostic) : `232`, `100`, `223`, `224`, `225`. **Aucune autre.** Le POC n'a
aucun point d'entrée générique `query(cmd)` : la liste est une allowlist
explicite.

#### 7.5.1bis Résultats chiffrés (réel, firmware 6.07.0)

| Mesure | Valeur |
|---|---|
| Appareils détectés | **9** |
| `ACTIVE` | **8** |
| `CABLED` | **1** |
| `STALE` | **0** |
| `UNKNOWN` | **0** |
| Appareils en 2,4 GHz | **4** |
| Appareils en 5 GHz | **4** |
| Appareils sans bande | **1** |
| Appareils avec RSSI | **8** |
| Appareils avec SSID | **8** |
| Appareils multi-bandes | **0** |
| Hostnames renseignés | **9** |
| `expires` | **9 valeurs entières à 10 chiffres** — sémantique non confirmée |

**Lecture de ces chiffres** :

- **8 `ACTIVE` + 1 `CABLED` = 9** : la somme est exacte, aucun appareil n'a
  été classé `UNKNOWN`. Cela signifie que les 8 appareils Wi-Fi figurent
  effectivement dans les tables d'associations `224`/`225` (présence radio
  constatée), et que le 9ᵉ est sur une interface filaire reconnue. La
  classification n'a donc **produit aucune conclusion forcée** : chaque état
  repose sur une donnée explicite du routeur.
- **4 + 4 = 8, sans bande = 1** : les 8 appareils Wi-Fi se répartissent
  **exactement** entre les deux bandes, **aucun n'est multi-bande** et le
  9ᵉ est bien l'appareil filaire. La cohérence DHCP ↔ Wi-Fi est donc
  **parfaite** sur ce réseau : 9 baux DHCP pour 8 associations Wi-Fi + 1
  interface filaire. Aucun appareil Wi-Fi n'est « actif » sans bail DHCP, et
  inversement.
- **8 RSSI / 8 SSID** : les 8 appareils Wi-Fi exposent un signal ; le seul
  appareil filaire n'en expose pas (comportement attendu).
- **9 hostnames renseignés** : aucun `hostname` vide ou `NULL` n'a été
  rencontré sur ce réseau. La normalisation `"NULL"` → `"Unknown Device"`
  reste donc **non observée en conditions réelles** (elle est couverte par les
  tests unitaires).

#### 7.5.2 Ce qui reste à confirmer

| Point | Statut | Comment le confirmer |
|---|---|---|
| **Format réel de `expires`** | **Forme observée** : entier à 10 chiffres sur les 9 baux. **Sémantique non confirmée** : 10 chiffres est compatible avec un timestamp epoch en secondes (≈ 2001-2039) *ou* avec un nombre d'unités dans une autre base. La classe `STALE` reste donc **inerte** : tant que la sémantique n'est pas établie, le client produit `UNKNOWN` (il ne lit un entier comme « expiré » que s'il est ≥ 1 000 000 000 **et** déjà dépassé). | Comparer la valeur brute avec l'horodatage courant : si `expires ≈ maintenant`, c'est un timestamp ; si `expires ≈ 86400` ou une durée de bail constante, c'est une durée. Le POC peut exposer l'écart `expires − maintenant` **sans révéler la valeur** si nécessaire. |
| **Classification `STALE`** | **Non validée sur un cas réel** : 0 appareil `STALE` observé. La branche est couverte par tests unitaires mais n'a pas été confrontée à des données réelles. | Se produira naturellement quand un appareil quittera le Wi-Fi et que son bail expirera — ou lors d'un test contrôlé avec un appareil secondaire. |
| **Présence de `hostname` = `"NULL"`** | Non observée sur ce réseau (9/9 renseignés). | La normalisation reste active par précaution ; cas non rencontré en conditions réelles. |
| **Encodage du SSID** | À confirmer : 8 SSID présents, mais leur **forme** (Base64 ou clair) n'a pas été relevée. Le code de référence documente un Base64 pour les *commandes radio* (`cmd 2`/`211`), pas pour les listes d'associations. | `--format diagnostic` ne donne que la **présence** d'un SSID ; pour la forme, `--format json` (à ne pas diffuser). |
| **Comportement en cas de session expirée** | Le re-login automatique est testé unitairement, mais pas observé en conditions réelles (la session n'a pas expiré pendant le test). | Le client détecte `NO_AUTH` / `LOGIN_TIMEOUT` et ré-authentifie seul ; à observer lors d'une session longue. |
| **Baux DHCP « orphelins »** | Aucun cas observé : les 9 appareils se recoupent parfaitement entre DHCP et Wi-Fi. | Un cas apparaîtra quand un appareil quittera le Wi-Fi en gardant son bail. |

#### 7.5.3 Conclusion

> Les **cinq commandes de la phase 1 sont confirmées fonctionnelles** sur le
> firmware 6.07.0 de l'appareil cible. Le client de la phase 1 est validé, sur
> un réseau réel de 9 appareils, avec une classification **sans aucune
> conclusion forcée** (8 `ACTIVE` + 1 `CABLED`, 0 `UNKNOWN`).
>
> Deux inconnues subsistent :
>
> 1. **La sémantique d'`expires`** — sa *forme* est observée (entier à 10
>    chiffres), sa *signification* ne l'est pas. C'est pourquoi la classe
>    `STALE` reste délibérément inerte : le client préfère `UNKNOWN` à une
>    conclusion non fondée. C'est un choix conservateur assumé, couvert par les
>    tests.
> 2. **La classe `STALE` elle-même**, jamais rencontrée sur un cas réel.
>
> Aucune écriture n'a été effectuée sur le routeur pendant cette validation.

---

## 8. Tableau des preuves

| Affirmation | Fichier | Lignes |
|---|---|---|
| Point d'entrée `POST /cgi-bin/http.cgi`, URL construite depuis l'hôte | `reference-zlt/desktop/router_client.py` | 19, 65-76 |
| Point d'entrée, port Kotlin | `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt` | 101-102, 118-125 |
| `Content-Type: application/json; charset=UTF-8` | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 71 · 46, 52 |
| Le `method` est dans le corps JSON | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 82, 114 · 132, 185-187 |
|(cmd 232 : défi, `sessionId` vide) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 81-85 · 130-138 |
| `cmd 100` : corps d'authentification complet | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 92-100 · 145-154 |
| Hash `SHA-256(token + password)`, hex minuscule, sans sel | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` · `.../ZltRouterClientTest.kt` | 87-88 · 140-141, 1127-1131 · 77-84 |
| `sessionId` = 2 UUID sans tirets (64 hex) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 89 · 142-143 |
| Détection du succès (`success` / `message == "success"` / présence de `sessionId`) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 102-104 · 157-164 |
| Conserver le `sessionId` client si le routeur n'en renvoie pas | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 103 · 162-163 |
| Expiration détectée par `NO_AUTH` / `LOGIN_TIMEOUT` | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 119 · 200-201 |
| Lecture rejouée une fois après reconnexion ; écriture jamais rejouée à l'aveugle | `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt` | 190-213 |
| Comportement de rejeur couvert par des tests | `reference-zlt/app/src/test/java/com/airtel/monitor/data/remote/ZltRouterClientTest.kt` | 86-140 |
| Aucune commande de liste (223/224/225) n'est mutante | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 205-207 · 362-364 |
| Indexation Wi-Fi par MAC **et** IP dans une seule table (défaut D1) | `reference-zlt/desktop/router_client.py` | 218-234 |
| Même défaut, port Kotlin | `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt` | 376-396 |
| Même défaut, port iOS | `reference-zlt/iosApp/AirtelMonitor/Services/ZltRouterClient.swift` | 360-384 |
| La bande 2,4 GHz est traitée **après** la 5 GHz (cause de l'écrasement) | `reference-zlt/desktop/router_client.py` | 220-234 |
| Jointure `wifi_map.get(mac) or wifi_map.get(ip)` (défaut D3) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` · `.../ZltRouterClient.swift` | 252 · 419 · 395 |
| Aucune dé-duplication de la table DHCP (défaut D2) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 241-291 · 408-460 |
| Champs DHCP : `mac`, `ip`, `hostname`, `interface`, `expires`, `ipv6` | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` · `.../data/model/RouterModels.kt` | 274-291 · 441-459 · 87-103 |
| `hostname` = chaîne littérale `"NULL"` | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` · `.../ZltRouterClientTest.kt` | 246-250 · 412-417 · 292 |
| `interface` peut valoir `wlan` ou `lan` | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClientTest.kt` | 253, 286 · 291-292 |
| Champs Wi-Fi : `mac`, `ip`, `rssi`, `txrate`, `rxrate`, `ssid` | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` · `.../ZltRouterClientTest.kt` | 220-234 · 378-396 · 294-297 |
| Valeurs typées en chaînes | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 254-257, 274-291 · 421-424, 441-459 |
| `dhcp_list_info` aussi présent dans `cmd 401` (F3) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 216 · 371-373 |
| Pas de débit par appareil (F4) | `reference-zlt/README.md` | 48 |
| `cmd 25` bloqué / `LIMITED_ACCESS` (F5) | `reference-zlt/README.md` · `desktop/router_client.py` | 52 · 35-37, 180 |
| `cmd 401` : vitesses toujours vides (F6) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 26-27, 237 · 252-257, 398-402 |
| `cmd 3` en écriture : renvoyer tout l'enregistrement LAN (F7) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 392-399, 444 · 563-567, 616-623 |
| SSID en Base64 dans les commandes radio (F8) | `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt` · `.../ZltRouterClientTest.kt` | 863-871, 885-886, 890-891 · 384 |
| Risque de rejeu sur POST (F9) | `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt` | 41-43, 190-213 |
| `token` de `cmd 233` porté par la session (F10) | `reference-zlt/desktop/router_client.py` · `.../ZltRouterClient.kt` | 449 · 207-210, 627-628 |
| `retryOnConnectionFailure(false)` sur le transport OkHttp | `reference-zlt/app/src/main/java/com/airtel/monitor/data/remote/ZltRouterClient.kt` | 41-43 |
| Architecture réseau et descriptions des couches | `reference-zlt/docs/ANDROID_NOTES.md` | 15-22 |
| Matériel cible et périmètre fonctionnel | `reference-zlt/README.md` | 4-7, 41-52 |
