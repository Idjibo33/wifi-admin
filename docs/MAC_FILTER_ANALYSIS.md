# Analyse du filtrage MAC — ZLT W304VA PRO / X17M (firmware 6.07.0)

> Analyse du protocole de filtrage MAC du routeur, obtenue **sans aucune
> modification du routeur**. Toutes les commandes identifiées proviennent du
> **code source de l'interface web du routeur lui-même**, servi par le routeur.
> Aucune commande n'a été devinée.

---

## 1. Résumé exécutif

| Question | Réponse | Niveau de confiance |
|---|---|---|
| Le projet `reference-zlt` documente-t-il le filtrage MAC ? | **Non, absolument pas.** Aucune commande, aucun champ, aucun code. | **Confirmé** — recherche exhaustive, arbre de travail + 24 commits d'historique |
| Le protocole est-il documenté ailleurs ? | **Oui**, dans le JavaScript de l'interface web du routeur (`/js/main.js`, `/js/firewall/macFilter.js`) | **Confirmé** — source de première partie, lue sur le routeur |
| Commandes identifiées — filtre MAC du **pare-feu** | `23` (règles), `30` (règle par défaut), `28` (défaut lisible), `20` (application) | **Confirmé** — table `RequestCmd` du routeur + code appelant |
| Commandes identifiées — filtre MAC **Wi-Fi** | `278` (`subcmd` `'1'` = 2,4 GHz / `'0'` = 5 GHz) | **Confirmé** — `js/config/wifiList24g.js` |
| A-t-on exécuté une commande d'écriture sur le routeur ? | **Non.** Aucune. | **Confirmé** |

> ### ⚠ Point de vigilance majeur : **deux fonctionnalités de filtrage MAC distinctes**
>
> Le routeur implémente **deux** mécanismes de filtrage MAC **indépendants**,
> avec des commandes, des données et des effets **différents** :
>
> | | **Filtrage MAC du pare-feu** | **Filtrage MAC Wi-Fi** |
> |---|---|---|
> | Menu | Pare-Feu → Règles de filtrage → Filtrage MAC | Wi-Fi → Liste de filtrage MAC |
> | Commandes | `23`, `28`, `30`, `20` | `278` |
> | Portée | IPv4 / IPv6 / les deux, par règle | **Par bande Wi-Fi** (2,4 GHz ou 5 GHz) |
> | Mode | liste noire / blanche globale (`acceptAll`) | `close` / `allow` / `deny` par bande |
> | Effet | filtre le **trafic** via le pare-feu | empêche (ou non) l'**association Wi-Fi** |
> | Limite | 32 règles | 1024 entrées par bande |
>
> **Le menu « Filtrage MAC » du pare-feu est le mécanisme que vous avez décrit.**
> C'est celui que la suite de ce document analyse. Le filtre Wi-Fi (`278`) est
> mentionné ici pour éviter toute confusion : il agit sur l'**association**, ce
> qui n'est **pas** la même chose (voir §6.1).

**Recommandation** : ne pas implémenter d'écriture avant la procédure de test
sécurisée de la §7 (appareil secondaire, export/restauration des règles).

---

## 2. Méthode d'analyse

### 2.1 Ce qui a été fait

1. **Recherche exhaustive dans `reference-zlt`** (§3) — résultat **négatif**.
2. **Lecture des ressources statiques de l'interface web du routeur** —_pages
   HTML et bundles JavaScript servis par le routeur, récupérés en `GET` seul.
   C'est du code **de première partie** : l'implémentation de référence du
   protocole, écrite par le fabricant.
3. **Déduction du protocole** à partir de ce code (§5).

### 2.2 Ce qui n'a pas été fait

- Aucune commande d'écriture (`POST`) n'a été envoyée au routeur.
- Aucun formulaire n'a été soumis.
- Aucune règle n'a été créée, modifiée, activée, désactivée ou supprimée.
- Aucune capture de trafic n'a été réalisée sur les autres machines du LAN.

### 2.3 Artefacts récupérés

Tous en lecture seule, via `GET` :

| URL | Taille | Contenu |
|---|---|---|
| `/` | 5 858 o | Page d'accueil, charge `main.js` |
| `/js/main.js` | 105 109 o | **Table `RequestCmd`**, enveloppe des requêtes, `Page.postJSON`, `Page.applyFilter`, table des menus |
| `/js/index.js` | 18 061 o | Amorçage de la coquille |
| `/js/home.js` | 39 475 o | Construction de l'arborescence de menus (.visible) |
| `/js/firewall/macIndex.js` | 325 o | Menu « Filtrage MAC » → 2 onglets |
| `/html/firewall/ipFilterIndex.js` | 491 o | Menu « Règles de filtrage » → 4 onglets |
| `/html/firewall/macFilter.html` | 8 451 o | Formulaire de règles MAC |
| `/js/firewall/macFilter.js` | 14 229 o | **Logique complète du filtrage MAC** |
| `/html/firewall/defaultMac.html` | 1 705 o | Formulaire « liste noire / blanche » par défaut |
| `/js/firewall/defaultMac.js` | 1 902 o | **Logique de la règle par défaut** |
| `/js/config/wifiList24g.js` | 7 108 o | **Filtrage MAC Wi-Fi** (le second mécanisme) |

### 2.4 Chemin de menu confirmé

Le chemin décrit dans la mission — **Pare-Feu → Règles de filtrage → Filtrage
MAC** — est confirmé par le code :

```javascript
// js/firewall/ipFilterIndex.js  (menu « Règles de filtrage », id FW_RULE)
items.push({ title: TAB.fw.defaultSetting,       url: 'html/firewall/default.html'    });
items.push({ title: DOC.title.addPortFiltering,  url: 'html/firewall/portFilter.html' });
items.push({ title: TAB.fw.ipPortFiltering,       url: 'html/firewall/ipFilter.html'   });
items.push({ title: TAB.fw.macFiltering,          url: "html/firewall/macFilter.html"  });
```

et, dans `js/main.js:566-569` :

```javascript
FW_RULE: {
    cmd: RequestCmd.PORT_FILTER,
    url: "html/firewall/ipFilterIndex.html"
},
```

**Une nuance à signaler** : dans le menu *Pare-Feu* construit par `js/home.js`
(lignes 258-300), l'entrée `FW_MAC_FILTER` **n'apparaît pas** — elle n'est
jamais poussée dans `fwMenu`. La page est néanmoins **entièrement implémentée
et servie** (`HTTP 200`), et le dictionnaire contient bien
`MENU.fw.macFilter = "Filtrage MAC"`. Sur votre routeur, l'onglet « Filtrage
MAC » est donc accessible depuis « Règles de filtrage », comme vous l'avez
observé ; le code disponible ici peut correspondre à un build d'interface web
légèrement différent de celui de votre firmware, ou la visibilité dépendre
d'un drapeau serveur (`Page.pageHideCheck*`) non observable sans session.

### 2.5 Environnement web du routeur

| Élément | Valeur |
|---|---|
| Framework | Vue.js **2.6.10** + Element UI + jQuery 3.7.1 |
| Architecture | coquille + `<iframe>` (pages HTML fragmentaires) |
| Point d'entrée CGI | `/cgi-bin/http.cgi` (`main.js:317`) |
| Langue active | **Français** (`Routeur haut débit sans fil 5G/4G`) |
| Date de build de l'interface web | **17 avril 2025** (`Last-Modified`) |
| UUID de build | `dd625c35-8026-41df-a8ff-425debfbae8f` (paramètre `?t=`) |

### 2.6 Sonde de sécurité effectuée

Une seule requête `POST` non authentifiée a été envoyée, pour vérifier que le
routeur refuse les requêtes sans session :

```json
{"cmd": 113, "method": "GET", "language": "EN"}
```

Réponse :

```json
{"success": false, "cmd": -1, "message": "SessionId parameter is missing"}
```

**Aucune donnée n'a été renvoyée** et la requête n'a pas atteint le handler.
Cela confirme que toute lecture de `cmd 23` exige une session valide.

---

## 3. Résultat de l'analyse de `reference-zlt`

### 3.1 Conclusion

**Le protocole de filtrage MAC est totalement absent du dépôt de référence.**

Recherches effectuées sur l'arbre de travail **et** sur les 24 commits
d'historique : `mac_filter`, `macFilter`, `blacklist`, `whitelist`,
`blocklist`, `allowlist`, `firewall`, `pare-feu`, `filtrage`, `ACL`,
`access control`, `macRule`, `addMac`, `delMac`, `blockMac`, `allowMac`.

**Zéro résultat** lié au filtrage MAC. Les seules occurrences de « filter »
concernent le **filtrage d'affichage par bande** (`DeviceFilter.ALL /
WIFI_5G / WIFI_24G / LAN`) ; « black » et « white » n'apparaissent que comme
couleurs CSS ou `white-space`.

### 3.2 Inventaire complet des commandes du dépôt de référence

| `cmd` | Usage | `cmd` | Usage |
|---|---|---|---|
| `2` | Wi-Fi 2,4 GHz (lecture/écriture) | `204` | Lecture de fichier de diagnostic |
| `3` | LAN/DNS (lecture + écriture) | `211` | Wi-Fi 5 GHz |
| `6` | Redémarrage | `218` | Mode réseau (lecture) |
| `18` | Compteurs WAN | `222` | Données mobiles (écriture) |
| `25` | Limites de débit — **bloqué** (`LIMITED_ACCESS`) | `223` | Liste DHCP |
| `80` | Mode réseau | `224` | Clients Wi-Fi 2,4 GHz |
| `100` | Authentification | `225` | Clients Wi-Fi 5 GHz |
| `113` | État système | `226` | Mode avion (écriture) |
| `160` | Verrouillage de cellule | `232` | Défi de connexion |
| `161` | Verrouillage de bande | `233` | Jeton d'écriture |
| `168` | Ping / traceroute | `288` | Reseau cellulaire |
| | | `401` | Tableau de bord |
| | | `1018` | Télémétrie matérielle |

**Aucune commande de pare-feu dans cette liste.** Le filtrage MAC est donc un
terrain entièrement inexploré par le projet de référence.

---

## 4. Source de vérité : la table `RequestCmd` du routeur

La table des commandes est déclarée dans `/js/main.js`, ligne 30 :

```javascript
var RequestCmd = {
    SYS_INFO: 0,
    WIRELESS_CONFIG: 2,
    NETWORK_CONFIG: 3,
    ...
    APPLY_FILTER: 20,
    PORT_FILTER: 21,
    IP_FILTER: 22,
    MAC_FILTER: 23,
    IP_MAC_BINDING: 24,
    SPEED_LIMIT: 25,
    URL_FILTER: 26,
    OTHER_FILTER: 27,
    DEFAULT_FILTER: 28,
    URL_DEFAULT_FILTER: 29,
    MAC_DEFAULT_FILTER: 30,
    FIRWALL_ACL_FILTER: 31,
    IP_PORT_FILTER: 32,
    ...
    GET_NEXT_LOGIN_TIME: 232,
    RESET_LOGIN_TIME: 233,
```

C'est une **preuve de première partie** : ces valeurs sont celles que le
routeur attend réellement, extraites de son propre code.

> Les commandes `20` à `32` forment un bloc cohérent : ce sont les **filtres du
> pare-feu**. `MAC_FILTER = 23` est le filtrage MAC ; `MAC_DEFAULT_FILTER = 30`
> est la règle par défaut (liste noire / blanche) ; `APPLY_FILTER = 20` est la
> commande d'application.

---

## 5. Matrice des opérations

Légende des niveaux de confiance :

- **Confirmé** — déduit du code du routeur, avec la ligne exacte.
- **À confirmer** — déduit du code mais non vérifié par une exécution réelle.

| # | Opération | `cmd` | `method` | Charge utile | Confiance | Preuve |
|---|---|---|---|---|---|---|
| **M1** | **Lire les règles MAC** | `23` | `GET` | *(enveloppe seule)*, `getfun: true` | **Confirmé** | `macFilter.js:52-66` |
| **M2** | **Écrire les règles MAC** | `23` | `POST` | `datas: [ …règles… ]`, `success: true`, `token` | **Confirmé** | `macFilter.js:67-87` |
| **M3** | **Appliquer les changements** | `20` | `POST` | *(enveloppe seule)*, `token` | **Confirmé** | `main.js:2273-2287` |
| **M4** | **Lire la règle par défaut** | `28` | `GET` | *(enveloppe seule)* | **Confirmé** | `macFilter.js:341-353` |
| **M5** | **Écrire la règle par défaut** | `30` | `POST` | `datas: [ …2 entrées… ]`, `success: true`, `token` | **Confirmé** | `defaultMac.js:39-50` |
| **M6** | **Lire la règle MAC par défaut** | `30` | `GET` | *(enveloppe seule)* | **Confirmé** | `defaultMac.js:16-27` |
| **M7** | **Lire le filtre MAC Wi-Fi** | `278` | `GET` | `subcmd: '1'` (2,4 GHz) / `'0'` (5 GHz) | **Confirmé** | `js/config/wifiList24g.js:66-87` |
| **M8** | **Écrire le filtre MAC Wi-Fi** | `278` | `POST` | `subcmd`, `datas: { macfilter, maclist }`, `success`, `token` | **Confirmé** | `js/config/wifiList24g.js:18-60` |
| **M9** | **Vérifier que le Wi-Fi est initialisé** (précondition d'écriture) | `417` | `GET` | *(enveloppe)* → `wifiStatus` | **Confirmé** | `js/config/wifiList24g.js:21-27` |
| **M10** | **Lire l'état WPS** (garde-fou) | `132` | `GET` | `subcmd: 0` (2,4 GHz) / `1` (5 GHz) → `wlan2g_wps_switch` / `wlan5g_wps_switch` | **Confirmé** | `js/config/wifiList24g.js:149-163` |

> **Note importante** : l'interface web n'utilise **jamais** `cmd 28` pour
> l'écriture de la règle par défaut — elle écrit via `cmd 30` (`MAC_DEFAULT_FILTER`).
> `cmd 28` (`DEFAULT_FILTER`) n'est lu que pour connaître l'état « tout
> accepter » afin de pré-cocher la case *Rejeter* d'une nouvelle règle.

> **M1–M6 = filtre MAC du pare-feu. M7–M10 = filtre MAC Wi-Fi.** Ce sont deux
> mécanismes disjoints (voir §5bis). Les M1–M6 sont prioritaires : c'est le
> menu décrit dans la mission.

### 5.1 Structure d'une règle MAC (`cmd 23`)

D'après `macFilter.js:152-157` (création) et `:245-249` (ajout multiple) :

```javascript
var arr = {
    enableLink: this.radioModelValue,   // false = liste noire, true = liste blanche
    remark:    this.commentValue,       // remarque libre
    ippro:     this.ipproValue,         // "IPV4" | "IPV6" | "IPV4V6"
    mac:       "AA:BB:CC:DD:EE:FF"      // format séparé par ":", MAJUSCULES
}
arr.enableRule = true                    // activée par défaut à la création
```

| Champ | Type | Valeurs | Rôle |
|---|---|---|---|
| `enableRule` | booléen | `true` / `false` | **Règle active** (case à cocher de la table) |
| `enableLink` | booléen | `false` / `true` | **Liste noire** (`false`) ou **liste blanche** (`true`) |
| `ippro` | chaîne | `IPV4`, `IPV6`, `IPV4V6` | Protocole ciblé |
| `mac` | chaîne | `AA:BB:CC:DD:EE:FF` | Adresse MAC, **MAJUSCULES**, séparée par `:` || `remark` | chaîne | texte libre | Remarque (vide à la création) |

**Cinq champs, ni plus ni moins.** Le tableau n'affiche que 5 colonnes :
activé, priorité, protocole, MAC, remarque.

### 5.2 Le champ « priorité » est un leurre

La table affiche une colonne « priorité », mais le code de la colonne est :

```javascript
<td>{{index+1}}</td>
```

C'est **l'index dans le tableau**, pas un champ transmis. Il n'existe **aucun**
champ `priority` dans l'objet règle. L'**ordre du tableau `datas` est donc
significatif** : c'est lui qui définit la priorité.

### 5.3 La sémantique liste noire / liste blanche

Le lien entre `enableLink` et le mode liste est établi par `macFilter.js:341-353` :

```javascript
Page.postJSON({
    json: { cmd: RequestCmd.DEFAULT_FILTER, method: JSONMethod.GET },
    success: function (data) {
        if (!!data.datas) {
            vm.radioModelValue = !data.datas[0].acceptAll;
        }
    }
});
```

et par `defaultMac.js:23-24` :

```javascript
vm.acceptAllIPv4 = data.datas[0].acceptAll == "true" ? "true" : "false";
vm.acceptAllIPv6 = data.datas[1].acceptAll == "true" ? "true" : "false";
```

Interprétation :

- `acceptAll = "true"` → **tout est accepté** → une nouvelle règle doit être
  une règle de **rejet** → `enableLink = !acceptAll = false` → **liste noire**.
- `acceptAll = "false"` → tout est refusé sauf exceptions → une nouvelle règle
  doit être une règle d'**acceptation** → `enableLink = true` → **liste blanche**.

Le tableau « par défaut » de `cmd 30` contient **exactement deux entrées**,
l'une pour IPv4 et l'autre pour IPv6 :

```javascript
var datas = [{
    "enableRule": true,
    "acceptAll": this.acceptAllIPv4,
    "ippro": "IPV4"
}, {
    "enableRule": true,
    "acceptAll": this.acceptAllIPv6,
    "ippro": "IPV6"
}]
```

### 5.4 Enveloppe de requête

Reprise de `Page.postJSON` (`main.js:1786-1810`) :

```javascript
if (json.method == "POST") {
    json.token = sessionStorage["token"];   // ← jeton CSRF, POST uniquement
}
if (!json.language) { json.language = Page.language; }
if (!json.sessionId) { json.sessionId = sessionStorage['sessionId'] || ""; }
$.ajax({ url: settings.url, type: 'POST', data: JSON.stringify(json), ... });
```

Donc, pour une **lecture** :

```json
{ "cmd": 23, "method": "GET", "sessionId": "<session>", "language": "en" }
```

et pour une **écriture** :

```json
{
  "cmd": 23, "method": "POST", "sessionId": "<session>",
  "language": "en",
  "token": "<jeton cmd 233>",
  "success": true,
  "datas": [ { "enableRule": true, "enableLink": false,
                "remark": "", "ippro": "IPV4",
                "mac": "AA:BB:CC:DD:EE:FF" } ]
}
```

Points à noter :

- L'URL est **la même** que pour nos lectures : `/cgi-bin/http.cgi`
  (`main.js:317` : `DEFAULT_CGI: '/cgi-bin/http.cgi'`).
- Le verbe HTTP est **toujours `POST`**, comme pour les lectures.
- Le champ `token` n'est ajouté que pour `method: "POST"`. C'est le jeton
  **`cmd 233`** (`RESET_LOGIN_TIME`) — exactement le mécanisme déjà utilisé par
  le projet de référence pour `cmd 3`, `cmd 161`, `cmd 160`, `cmd 2`, `cmd 211`,
  `cmd 222`, `cmd 226`.
- Le champ `success: true` est ajouté par le client web dans le corps. Il
  semble ignoré par le serveur (c'est un artefact du code JS), mais il figure
  dans toutes les écritures observées : **il faut l'inclure pour rester
  fidèle au protocole observé**.

### 5.5 Format de réponse

`macFilter.js:60` lit `data.datas` ; `defaultMac.js:23-24` lit
`data.datas[0].acceptAll` et `data.datas[1].acceptAll`.

Le tableau `datas` de `cmd 23` contient donc des objets de la forme :

```json
{
  "enableRule": true,
  "enableLink": false,
  "remark": "Android phone",
  "ippro": "IPV4",
  "mac": "AA:BB:CC:DD:EE:FF"
}
```

> **À confirmer sur le routeur réel** : le nom exact de la clé de réponse
> (`datas`), et la présence éventuelle de champs supplémentaires. Le code du
> routeur utilise `datas` de façon cohérente pour `cmd 23`, `cmd 28` et
> `cmd 30` — c'est le nom le plus probable, mais c'est le seul élément qui
> n'a pas été observé en conditions réelles.

### 5.6 Validation de la MAC côté routeur

`CheckUtil.checkMac` (`main.js:2351-2354`) :

```javascript
var reg = /^([0-9a-f]{2}[\:\-]{0,1}){5}[0-9a-f]{2}$/i;
```

`CheckUtil.checkInvalidMac` (`main.js:2360-2365`) rejette :
`FF:FF:FF:FF:FF:FF`, `00:00:00:00:00:00`, `01:00:00:00:00:00`, et toute MAC
dont le premier octet est impair (adresse multicast).

**Limite de 32 règles** : `macFilter.js:140` et `:265` refusent d'ajouter au-delà
de 32 règles.

**Normalisation** : `macFilter.js:156` —
`this.ipAddressValue.match(/[0-9a-f]{2}/ig).join(":").toUpperCase()`.
Le routeur attend donc des MAC **séparées par `:` et en MAJUSCULES**.

---

## 5bis. Le filtre MAC Wi-Fi (`cmd 278`) — analyse complète

### 5bis.1 Rôle exact de la commande

`WIRELESS_MACFILTER = 278` (`main.js:191`) implémente le menu Wi-Fi
**« Liste noire et blanche Wi-Fi »** (`wirelessconfightml.tab_menu.wifiList`,
`fr.js`). Ce menu est **présent et visible** dans l'interface (`js/home.js:149`),
contrairement au filtre du pare-feu.

Son rôle est de **contrôler l'association Wi-Fi elle-même**, par bande :

- une MAC en mode `deny` (liste noire) **ne peut plus s'associer** à la radio ;
- une MAC en mode `allow` (liste blanche) est **autorisée** ;
- le mode `close` (fermeture) désactive complètement le filtrage.

**Différence de portée avec le pare-feu** :

| | `cmd 23` — Pare-Feu | `cmd 278` — Wi-Fi |
|---|---|---|
| Agit sur | le **trafic** (ACL du pare-feu) | l'**association radio** |
| Portée | IPv4 / IPv6 / les deux | **par bande** Wi-Fi |
| L'appareil reste-il visible ? | **Oui**, il reste associé et listé | **Non** : il ne s'associe plus, donc il disparaît de `cmd 224`/`225` |
| Mode | `acceptAll` global (`true`/`false`) | `close` / `allow` / `deny` par bande |
| Limite | 32 règles | 1024 entrées par bande |
| Écriture | `cmd 23` puis **`cmd 20`** (application) | `cmd 278` **seul** (appliqué immédiatement) |

> C'est exactement la distinction que la mission demandait de ne pas confondre.
> Le filtre du pare-feu **ne bloque pas** l'association Wi-Fi ; le filtre Wi-Fi
> **la** bloque.

### 5bis.2 Méthodes de lecture et d'écriture

| Opération | `cmd` | `method` | `subcmd` | Charge utile |
|---|---|---|---|---|
| Lire 2,4 GHz | `278` | `GET` | `'1'` | *(enveloppe)* |
| Lire 5 GHz | `278` | `GET` | `'0'` | *(enveloppe)* |
| Écrire 2,4 GHz | `278` | `POST` | `'1'` | `datas: { macfilter, maclist }`, `success: true`, `token` |
| Écrire 5 GHz | `278` | `POST` | `'0'` | idem |

Preuves : `js/config/wifiList24g.js:66-87` (lecture 2,4 GHz), `:168-185`
(lecture 5 GHz au montage), `:18-60` (écriture). Le fichier 5 GHz est
**identique** au 2,4 GHz à un seul caractère près : seuls `subcmd` et quelques
noms de variables diffèrent (`diff` vérifié).

**Point de sécurité important** : le `GET cmd 278` est **purement en lecture**.
Le handler ne fait que peupler l'état local Vue (`vm.macfilter`,
`vm.maclist`) ; il n'envoie **aucune** requête d'écriture. La forme
`method: "GET"` dans le corps JSON correspond bien à une lecture, comme pour
`cmd 223`/`224`/`225`.

**Précondition** : avant toute écriture, l'interface vérifie `cmd 417`
(`WIFI_INIT_STATUS`) et refuse d'écrire si `wifiStatus !== '1'`
(« Le système Wi-Fi actuel est en cours d'initialisation »). C'est une
**protection du routeur**, pas du client : elle empêche d'écrire pendant que
le Wi-Fi se réinitialise.

### 5bis.3 Format des requêtes

**Lecture :**

```json
{ "cmd": 278, "method": "GET", "subcmd": "1", "sessionId": "<session>", "language": "fr" }
```

**Écriture :**

```json
{
  "cmd": 278, "method": "POST", "subcmd": "1",
  "sessionId": "<session>", "language": "fr",
  "token": "<jeton cmd 233>",
  "success": true,
  "datas": {
    "macfilter": "close" | "allow" | "deny",
    "maclist": [ { "mac": "AA:BB:CC:DD:EE:FF", "remarks": "..." } ]
  }
}
```

À noter :

- la clé `datas` contient un **objet** (pas un tableau, contrairement à
  `cmd 23`) ;
- `macfilter` et `maclist` sont **au même niveau** dans `datas` ;
- le jeton `cmd 233` est ajouté automatiquement par `Page.postJSON` pour toute
  écriture ;
- **il n'y a pas de commande d'application distincte** : l'écriture `cmd 278`
  est appliquée immédiatement par le routeur.

### 5bis.4 Format des réponses

**Lecture** (`js/config/wifiList24g.js:73-84`) :

```json
{
  "success": true,
  "datas": {
    "macfilter": "close",
    "maclist": [
      { "mac": "AA:BB:CC:DD:EE:FF", "remarks": "..." }
    ]
  }
}
```

Si la clé `datas` est **absente**, l'interface applique des valeurs par
défaut : `macfilter = 'close'`, `maclist = []`. Le client Kotlin doit faire de
même — et non supposer que `datas` existe.

La MAC est renvoyée **normalisée en majuscules** par le client web
(`:78`), mais la forme brute du routeur n'est pas documentée : il faut la
normaliser à la réception.

### 5bis.5 Mécanismes liste noire / liste blanche

Les trois modes sont des **valeurs de chaîne** dans le `el-select`
(`html/config/wifiList24g.html:33-37`) :

| Valeur | Libellé FR | Libellé EN | Effet |
|---|---|---|---|
| `close` | « fermeture » | *(close)* | Filtre **désactivé** : aucune restriction |
| `allow` | « liste blanche » | whitelist | Seules les MAC listées **peuvent** s'associer |
| `deny` | « liste noire » | blacklist | Les MAC listées **ne peuvent pas** s'associer |

Les libellés du dictionnaire français le confirment sans ambiguïté :

```javascript
DOC.lbl.accept          = "liste blanche";
DOC.status.accept       = "Autoriser la connexion";
DOC.lbl.reject          = "liste noire";
DOC.status.reject       = "bloquer la connexion";
DOC.lbl.dataRoamingStop = "fermeture";
```

> **Attention à l'inversion par rapport à `cmd 23`** : là, `acceptAll = "true"`
> signifiait « tout accepter » et une nouvelle règle héritait de
> `enableLink = !acceptAll`. Ici, `allow` / `deny` sont **directement** le mode
> de la liste. Deux vocabulaires différents pour deux mécanismes différents :
> c'est une source de bug classique, à isoler dans deux types distincts.

**Sémantique combinée** (comme pour toute liste blanche/noire) :

- `allow` + une liste non vide ⇒ seules les MAC de la liste sont autorisées ;
- `allow` + une liste vide ⇒ **plus personne** ne peut s'associer ;
- `deny` + une liste ⇒ les MAC de la liste sont exclues, les autres passent ;
- `close` ⇒ la liste est ignorée.

> Le cas `allow` + liste vide est un **verrouillage total du Wi-Fi**. C'est
> l'erreur la plus coûteuse possible sur ce mécanisme : elle coupe l'accès de
> tous les appareils sans fil, y compris de l'appareil de travail. Le client
> doit **refuser** cette combinaison, ou au minimum exiger une confirmation
> explicite et un avertissement clair.

### 5bis.6 Application des règles

Contrairement au filtre du pare-feu, il n'y a **pas de commande
d'application séparée** :

```text
cmd 278 POST  →  le routeur applique immédiatement
```

L'interface enchaîne simplement : `GET` (charger) → modification locale du
tableau → `POST` (tout réécrire). La confirmation est purement visuelle
(`fyAlertMsgSuccess` / `fyAlertMsgFail`).

Il en découle une propriété importante : **l'écriture remplace l'intégralité
du couple `(macfilter, maclist)` de la bande concernée.** Écrire avec une
liste tronquée **efface** les règles manquantes.

### 5bis.7 Suppression et déblocage

Il n'existe **aucune** commande de suppression unitaire. Comme pour `cmd 23`,
tout passe par une réécriture complète :

| Action | Manœuvre |
|---|---|
| **Supprimer une règle** | Retirer l'entrée du tableau `maclist`, puis `POST cmd 278` avec le tableau restant. |
| **Effacer toutes les règles** | `maclist = []`, puis `POST cmd 278`. Le bouton « Effacer tout » ne fait d'ailleurs que vider le tableau **en local** (`clearAll`, `:88-92`) — l'envoi n'a lieu qu'au clic sur « Enregistrer ». |
| **Débloquer un appareil** | Retirer sa MAC de `maclist` (en mode `deny`), ou passer `macfilter` à `close` ou `allow` selon le cas, puis `POST cmd 278`. |
| **Modifier une règle** | Remplacer l'entrée dans le tableau (même MAC, nouvelle remarque), puis `POST cmd 278`. |

**Le mode `close` est le déblocage global** : il désactive le mécanisme sans
perdre la liste (qui reste dans `datas.maclist`). C'est le geste de
**première ligne en cas de blocage accidentel**.

### 5bis.8 Fonction de synchronisation inter-bandes

Le bouton « Synchroniser les données 5G » (`DOC.btn.same5g`) copie la liste
d'une bande vers l'autre. L'interface le signale explicitement :

```javascript
PROMPT.confirm.SyncData = "Cette opération écrasera les données d'origine. Voulez-vous continuer ?";
```

Concrètement, depuis la page 2,4 GHz, `Sync5g` appelle `btnSave(vm.maclist5g)`,
c'est-à-dire `POST cmd 278` avec `subcmd: '1'` et le **contenu 5 GHz** lu
préalablement. C'est une **écriture croisée** : elle écrase la liste 2,4 GHz
avec celle de 5 GHz.

> Cette fonction est un **piège** : elle écrase sans proposer de fusion. À
> reproduire dans le client Kotlin, il faut la traiter comme une écriture
> destructive classique (lecture préalable + confirmation), jamais comme une
> simple copie.

### 5bis.9 Garde-fous du routeur

| Garde-fou | Condition | Effet |
|---|---|---|
| Wi-Fi en initialisation | `cmd 417` → `wifiStatus !== '1'` | L'écriture est refusée par l'interface |
| Mesh activé | `Page.meshEnable == '1'` | Le filtre est **désactivé** dans l'interface (`wifiList24g.html:33`) |
| WPS activé | `wpsEnable` (via `cmd 132`, `subcmd 0` ou `1`) | Le filtre est **désactivé** dans l'interface |
| MAC invalide | `checkMac` / `checkInvalidMac` | Refus : format invalide, diffusion (`FF:FF:FF:FF:FF:FF`), tout-zéro, multicast |
| Doublon | `sameMac` | Refus d'ajouter une MAC déjà présente |
| Limite | `maclist.length >= 1024` | Refus d'ajouter |

**Conséquence opérationnelle majeure** : si le **WPS** est activé, le filtre
Wi-Fi est **ignoré** par le routeur. Une règle « deny » posée alors que le WPS
est actif ne produirait **aucun** effet — un faux négatif de sécurité.
Le client doit donc **lire l'état WPS** (`cmd 132`) avant d'afficher un état
« bloqué », et avertir si le filtre ne sera pas appliqué.

### 5bis.10 L'interaction WPS / filtre MAC est **mutuellement exclusive**

L'analyse du code du routeur montre que WPS et filtre MAC Wi-Fi s'excluent
**dans les deux sens**. Ce n'est pas une simple précaution de l'interface :
c'est le comportement du firmware.

**Sens 1 — activer le WPS désactive le filtre.**

`js/config/wirelessWpsSetting.js:77-87` (page WPS 2,4 GHz) :

```javascript
btnClick: function () {
    if(Page.pageHideCheck(56) && this.list_sw_24 && this.wpsEnable){
        fyConfirmMsg(String.format(DOC.lbl.closeBlackOrWhiteList, "2.4G"), function() {
            fyAlertMsgLoading();
            vm.postListSwData()      // ← POST cmd 278 avec macfilter: 'close'
        })
    }else {
        fyAlertMsgLoading();
        this.postWpsData()
    }
}
```

Si un filtre est actif (`list_sw_24 = macfilter != 'close'`) et que l'on
active le WPS, le routeur **remplace le filtre par `close`** — la liste est
conservée, mais le mécanisme est désactivé. Le message est explicite :

```javascript
DOC.lbl.closeBlackOrWhiteList =
    "L'activation de ce commutateur fermera la liste noire et blanche du Wi-Fi {0}. Voulez-vous continuer ?";
```

**Sens 2 — activer le filtre désactive le WPS.**

`DOC.lbl.disableBlackOrWhiteList` =
« La fonction WPS ou la fonction MESH a été activée, cette fonction sera
désactivée ».

**Conséquences pour le client :**

| Règle | Mise en œuvre |
|---|---|
| Ne jamais modifier le WPS | Le client **lit** `cmd 132` (`wlan2g_wps_switch` / `wlan5g_wps_switch`) et **n'écrit jamais** dessus. |
| Refuser une écriture de filtre si le WPS est actif | Une règle posée sous WPS actif serait ignorée : c'est un faux négatif de sécurité. Le client refuse (cf. `checkWritable`). |
| Signaler à l'utilisateur que l'activation du WPS désactivera le filtre | L'interface web le fait par une confirmation ; l'application doit faire de même. |
| Noter que la page WPS **écrit** `cmd 278` avec `macfilter: 'close'` | Un utilisateur qui active le WPS depuis l'interface web **modifie le filtre** sans le voir. L'application doit le signaler si elle constate `macfilter == close` alors qu'une liste existe. |

> **Point de vigilance pour la lecture** : `cmd 132` utilise la convention
> **inverse** de `cmd 278` — `subcmd '0'` pour le WPS 2,4 GHz et `'1'` pour le
> WPS 5 GHz, alors que `cmd 278` utilise `'1'` pour 2,4 GHz et `'0'` pour
> 5 GHz. La confusion des deux conventions enverrait le client sur la mauvaise
> bande. C'est couvert par le test
> `the wps subcmd uses the opposite convention of the mac filter`.

---

## 6. Séquence d'opérations reconstituée

L'interface web procède ainsi (reconstitué depuis `macFilter.js`) :

```
1. Chargement de la page
   GET  cmd 23   -> data.datas = [règles existantes]
   GET  cmd 28   -> data.datas[0].acceptAll  (état « tout accepter »)

2. Ajout / modification d'une règle
   → manipulation LOCALE du tableau `vm.value`
   → aucune requête

3. Clic sur « Appliquer »
   POST cmd 23   { datas: vm.value, success: true, token }
                 → si data.success == true :
   POST cmd 20   { } + token
                 → applique effectivement les règles au pare-feu

4. « Effacer toutes les règles »
   → vm.value = []  (local)
   POST cmd 23   { datas: [], success: true, token }
   POST cmd 20   { } + token

5. Onglet « Règle par défaut »
   GET  cmd 30   → data.datas[0].acceptAll (IPv4), [1].acceptAll (IPv6)
   POST cmd 30   { datas: [ {enableRule:true, acceptAll:"...", ippro:"IPV4"},
                            {enableRule:true, acceptAll:"...", ippro:"IPV6"} ],
                   success: true, token }
```

**Conséquence majeure** : `cmd 23` écrit **la liste complète**, pas un delta.
Il n'existe pas de commande « ajouter une règle » ou « supprimer une règle » :
ce sont des opérations de lecture-modification-écriture sur le tableau entier.
Une écriture est donc **destructive si la liste lue n'est pas à jour**.

> `cmd 30` (règle par défaut) s'écrit **sans** appel à `cmd 20`. La règle par
> défaut est appliquée immédiatement par le routeur, ce qui n'est pas le cas des
> règles de filtrage MAC.

### 6.1 Périmètre d'action : filtrage MAC ≠ blocage d'association Wi-Fi

C'est une distinction que le firmware rend explicite, et qu'il faut énoncer
clairement :

- `enableRule` / `enableLink` agissent sur le **filtrage du trafic** au niveau
  du pare-feu du routeur.
- Le routeur continue d'**associer** les appareils Wi-Fi : ils apparaissent
  toujours dans `cmd 223` / `cmd 224` / `cmd 225`.
- Un appareil bloqué reste **visible dans la liste des appareils connectés**,
  avec un état `ACTIVE` inchangé.

**Conséquence pour l'UI** : l'état `ACTIVE` / `CABLED` / `STALE` / `UNKNOWN` de
la phase 1 décrit la **connectivité**, pas l'**autorisation d'accès au réseau**.
Ces deux notions sont **orthogonales** et doivent être présentées séparément
dans l'application. Il faudra ajouter un axe distinct « accès autorisé /
bloqué ».

---

## 7. Procédure de test sécurisée

### 7.1 Prérequis absolus

| # | Exigence |
|---|---|
| **P1** | **Un appareil de test secondaire**, distinct de l'appareil de travail et de tout appareil dont vous avez besoin. Idealement un vieux téléphone ou une tablette en Wi-Fi. |
| **P2** | **Un accès administrateur indépendant** : conserver un second moyen de vous connecter au routeur (par exemple la connexion **filaire**, ou un autre appareil autorisé) pour ne pas être verrouillé dehors. |
| **P3** | **L'export complet des règles existantes avant toute écriture**, afin de pouvoir les restaurer à l'identique. |
| **P4** | Ne **jamais** appliquer de règle à un MAC d'un appareil dont vous dépendez pour la connexion. |

### 7.2 Procédure

**Étape 0 — Préparation**

1. Identifier le MAC de l'appareil de test secondaire.
2. Vérifier la **connectivité de secours** (P2).
3. **Exporter l'état initial** : `cmd 23` GET et `cmd 30` GET. Enregistrer les
   deux réponses **en local, chiffrées ou dans un répertoire hors dépôt**.

**Étape 1 — Lecture seule (aucun risque)**

4. `GET cmd 23` → vérifier la structure de `datas` (confirme M1).
5. `GET cmd 28` → vérifier `datas[0].acceptAll` (confirme M4).
6. `GET cmd 30` → vérifier les deux entrées IPv4/IPv6 (confirme M6).

> Cette étape est **sans risque** : elle ne modifie rien. C'est elle qui
> confirme le protocole avant toute écriture.

**Étape 2 — Écriture sur l'appareil de test secondaire**

7. `GET cmd 233` → récupérer le jeton d'écriture.
8. `POST cmd 23` avec `datas` = liste actuelle + **une** règle pour le MAC de
   l'appareil de test, `enableLink: false` (liste noire), `ippro: "IPV4"`.
   → **ne pas** appeler `cmd 20` à ce stade.
9. `GET cmd 23` → vérifier que la règle est **stockée** mais pas encore appliquée.
10. Vérifier que l'appareil de test **continue de fonctionner** (la règle n'est
    pas encore appliquée).
11. `POST cmd 20` → **appliquer**.
12. Vérifier le comportement de l'appareil de test : accès Internet coupé,
    mais **toujours associé** au Wi-Fi et toujours visible dans la liste.

**Étape 3 — Validation de la sémantique liste blanche**

13. `GET cmd 28` → noter `acceptAll`.
14. Basculer la règle par défaut via `POST cmd 30` avec `acceptAll: "false"`
    (mode liste blanche) — **uniquement si l'appareil de test est le seul
    appareil Wi-Fi connecté**, sinon votre propre machine se verrouille.
15. Vérifier que l'appareil de test est **autorisé** et qu'un autre appareil est
    **bloqué**.

**Étape 4 — Désactivation et suppression**

16. `POST cmd 23` avec la règle et `enableRule: false` → `POST cmd 20`
    → vérifier que l'appareil de test est de nouveau accessible.
17. `POST cmd 23` avec la règle retirée du tableau → `POST cmd 20`
    → vérifier que la règle a disparu et que l'appareil de test fonctionne.

**Étape 5 — Restauration**

18. `POST cmd 23` avec le tableau **exactement** tel qu'il a été exporté à
    l'étape 0 → `POST cmd 20`.
19. `GET cmd 23` et `GET cmd 30` → **comparer** avec l'export initial.
20. Vérifier que tous vos appareils sont de nouveau fonctionnels.

### 7.3 Règles de sécurité pendant le test

- **Jamais** de `POST cmd 23` / `cmd 20` avant d'avoir lu `cmd 23` avec succès
  (une écriture avec un tableau vide ou périmé **effacerait** les règles
  existantes).
- Utiliser le jeton `cmd 233` **frais** pour chaque écriture.
- **Une seule** règle modifiée à la fois.
- Conserver l'export initial jusqu'à la **restauration vérifiée**.
- Arrêter immédiatement si une réponse est inattendue ou si un appareil
  nécessaire à la connexion est touché.

---

## 8. Procédure de restauration

L'état du filtrage MAC du **pare-feu** est intégralement décrit par deux
lectures :

```text
état = { règles MAC (cmd 23 GET), règle par défaut (cmd 30 GET) }
```

La restauration consiste à rejouer cet état :

1. `GET cmd 233` (jeton frais)
2. `POST cmd 23` avec le tableau exporté (`datas`), `success: true`, `token`
3. `POST cmd 20` pour appliquer
4. `GET cmd 30` puis `POST cmd 30` avec les deux entrées exportées si la règle
   par défaut a été modifiée (pas d'appel à `cmd 20` : elle s'applique seule)
5. `GET cmd 23` → **comparer** avec l'export

Pour le filtre **Wi-Fi** (`cmd 278`), l'état est porté par une seule lecture et
une seule écriture, par bande :

```text
état = { macfilter, maclist }   // pour subcmd '1' (2,4 GHz) et '0' (5 GHz)
```

1. `GET cmd 233`
2. `POST cmd 278` avec `subcmd`, `macfilter` et `maclist` exportés
3. `GET cmd 278` → **comparer** avec l'export

**Limite à connaître** : la restauration est fidèle **au niveau des règles**,
mais pas nécessairement **de l'état interne du pare-feu** (par exemple un cache
d'ACL qui ne serait pas purgé). Une sauvegarde de la configuration complète du
routeur (si l'interface le propose) est une garantie plus forte.

> **Recommandation forte** : lors du test, **préférer la restauration par
> l'interface web** (remettre manuellement les mêmes cases et cases à cocher)
> plutôt que par le client, au moins pour la première fois. Cela valide que les
> deux chemins produisent le même état.

---

## 9. Proposition d'extension de `router-core`

### 9.1 Principe

`router-core` reste une bibliothèque **pure Kotlin/JVM**, sans dépendance
Android, réutilisable telle quelle par l'application Compose. L'extension
s'ajoute comme un **module sibling** (`router-core` reste agnostique), ou
comme un sous-paquetage `dev.wifiadmin.router.mac` si vous préférez ne pas
multiplier les modules.

### 9.2 API proposée

```kotlin
// dev.wifiadmin.router.mac

/** Une règle de filtrage MAC, telle que stockée par le routeur. */
data class MacFilterRule(
    val mac: String,                 // "AA:BB:CC:DD:EE:FF", MAJUSCULES
    val protocol: MacProtocol,       // IPV4 | IPV6 | IPV4V6
    val list: MacList,               // BLACKLIST | WHITELIST
    val enabled: Boolean,            // enableRule
    val remark: String,              // remark
)

enum class MacProtocol { IPV4, IPV6, IPV4V6 }
enum class MacList { BLACKLIST, WHITELIST }

/** L'état complet du filtrage MAC — la seule chose à sauvegarder/restaurer. */
data class MacFilterState(
    val rules: List<MacFilterRule>,
    val defaultIpv4: Boolean,        // acceptAll IPv4
    val defaultIpv6: Boolean,        // acceptAll IPv6
)

/** Opérations de lecture seule. */
interface MacFilterReader {
    fun readRules(): List<MacFilterRule>
    fun readDefaults(): MacFilterState
}

/** Opérations d'écriture. */
interface MacFilterWriter {
    fun writeRules(rules: List<MacFilterRule>)
    fun apply()
    fun writeDefaults(defaultIpv4: Boolean, defaultIpv6: Boolean)
}
```

### 9.3 Le filtre MAC Wi-Fi — une API **séparée**

Le mécanisme `cmd 278` ne doit **pas** partager les types du filtre du
pare-feu : les concepts ne se recouvrent pas (par bande, vs global ; association,
vs trafic).

```kotlin
// dev.wifiadmin.router.mac.wifi

enum class WifiBandFilter { BAND_2_4_GHZ, BAND_5_GHZ }   // subcmd '1' / '0'
enum class WifiMacMode { CLOSE, ALLOW, DENY }              // close / allow / deny

data class WifiMacEntry(
    val mac: String,          // "AA:BB:CC:DD:EE:FF", MAJUSCULES
    val remark: String,       // remarks
)

data class WifiMacFilterState(
    val band: WifiBandFilter,
    val mode: WifiMacMode,
    val entries: List<WifiMacEntry>,
)

interface WifiMacFilterClient {
    fun read(band: WifiBandFilter): WifiMacFilterState
    fun write(state: WifiMacFilterState)
}
```

> **Recommandation** : implémenter le filtre du **pare-feu** en premier
> (c'est la demande), et traiter le filtre Wi-Fi comme une **phase
> ultérieure**. Les superposer rend le diagnostic d'un blocage impossible :
> il faut savoir lequel des deux mécanismes a agi.

### 9.4 API détaillée du filtre Wi-Fi

```kotlin
// dev.wifiadmin.router.mac.wifi

/** La bande Wi-Fi concernée par une règle. */
enum class WifiBandFilter(val subcmd: String) {
    BAND_2_4_GHZ("1"),   // subcmd '1'
    BAND_5_GHZ("0"),     // subcmd '0'
}

/** Le mode du filtre : fermeture, liste blanche ou liste noire. */
enum class WifiMacMode(val wire: String) {
    CLOSE("close"),      // filtrage désactivé
    ALLOW("allow"),      // liste blanche : seules les MAC listées s'associent
    DENY("deny"),        // liste noire : les MAC listées ne s'associent plus
}

/** Une entrée de la liste. */
data class WifiMacEntry(
    val mac: String,        // normalisée : "AA:BB:CC:DD:EE:FF"
    val remark: String,     // ≤ 50 caractères
)

/** L'état complet du filtre d'une bande. C'est la seule chose à sauvegarder. */
data class WifiMacFilterState(
    val band: WifiBandFilter,
    val mode: WifiMacMode,
    val entries: List<WifiMacEntry>,
) {
    /**
     * Vrai si ce mode + cette liste produiraient un verrouillage total.
     *
     * `allow` avec une liste vide exclut tout le monde de la bande : c'est la
     * seule combinaison qui coupe l'accès de tous les appareils sans fil.
     */
    val locksOutEveryDevice: Boolean
        get() = mode == WifiMacMode.ALLOW && entries.isEmpty()
}

/** Erreurs propres au filtre Wi-Fi. */
sealed class WifiMacFilterError {
    /** Le Wi-Fi n'est pas initialisé (`cmd 417` → `wifiStatus != '1'`). */
    object WifiNotReady : WifiMacFilterError()

    /** Le WPS est actif : le routeur ignore le filtre. */
    object WpsActive : WifiMacFilterError()

    /** La liste blanche vide verrouillerait tous les appareils. */
    object WouldLockOutEveryDevice : WifiMacFilterError()

    /** L'état lu ne correspond plus à celui que l'appelant veut modifier. */
    data class StaleState(val current: WifiMacFilterState) : WifiMacFilterError()
}
```

**Interface publique** :

```kotlin
class WifiMacFilterClient(
    private val transport: RouterTransport,
    private val settings: RouterSettings,
) {
    /** Lecture seule. Ne modifie rien sur le routeur. */
    fun read(band: WifiBandFilter): WifiMacFilterState

    /** Vérifie les préconditions (cmd 417, cmd 132) sans rien écrire. */
    fun checkWritable(band: WifiBandFilter): Result<Unit>

    /**
     * Écrit l'état d'une bande.
     *
     * Refuse si [expected] ne correspond plus à l'état courant, si le Wi-Fi
     * n'est pas prêt, si le WPS est actif, ou si l'opération verrouillerait
     * tous les appareils.
     */
    fun write(expected: WifiMacFilterState, newState: WifiMacFilterState): Result<Unit>
}
```

### 9.5 Plan de tests

| # | Test | Ce qu'il vérifie |
|---|---|---|
| **W1** | `read parses the macfilter mode and the maclist` | La réponse `datas: { macfilter, maclist }` est correctement analysée |
| **W2** | `a missing datas key yields the close mode and an empty list` | L'absence de `datas` ⇒ `CLOSE` + liste vide (comme l'interface web) |
| **W3** | `the read uses the right subcmd for each band` | `subcmd '1'` pour 2,4 GHz, `'0'` pour 5 GHz |
| **W4** | `the read never sends a write command` | **Test d'allowlist** : seule la commande `278` en `GET` est émise |
| **W5** | `mac addresses are normalized to upper case colon form` | `aabbccddeeff` → `AA:BB:CC:DD:EE:FF` |
| **W6** | `the write posts the datas object with the mode and the list` | La charge utile est `{ macfilter, maclist }`, et non un tableau |
| **W7** | `the write carries a fresh token` | Le jeton `cmd 233` est présent et frais |
| **W8** | `writing allow with an empty list is refused` | Le verrouillage total est bloqué **avant** toute requête |
| **W9** | `writing while the wifi is not ready is refused` | `cmd 417` → `wifiStatus != '1'` ⇒ refus, rien n'est écrit |
| **W10** | `writing while wps is active is refused` | `cmd 132` ⇒ WPS actif ⇒ refus, rien n'est écrit |
| **W11** | `a stale expected state refuses the write` | L'état relu diffère ⇒ refus, **aucune** requête d'écriture |
| **W12** | `the state is read back after the write` | Relecture de confirmation après écriture |
| **W13** | `a network failure during the write raises a network exception` | Les erreurs typées sont respectées |
| **W14** | `the sync between bands writes to the target band only` | La synchronisation inter-bandes n'écrit que sur la bande cible |

**Faux routeur** : le `FakeRouter` existant (`router-core/src/test/.../FakeRouter.kt`)
suffit — il dispatche déjà par `(cmd, method)`. Il faudra ajouter un handler
pour `cmd 278` (GET et POST) et pour `cmd 417` / `cmd 132`.

**Aucun test n'atteint le routeur réel.**

### 9.6 Stratégie de restauration en cas de blocage accidentel

C'est le risque principal de ce mécanisme : un `allow` malencontreux peut
couper l'accès de tous les appareils sans fil.

**Hiérarchie de restauration, de la plus légère à la plus lourde :**

| Rang | Action | Effet | Risque |
|---|---|---|---|
| **R1** | Mode `close` sur la bande concernée | Désactive le filtre, **conserve** la liste | Aucun. C'est le geste de secours principal. |
| **R2** | Retirer la MAC fautive de `maclist`, mode `deny` | Débloque un seul appareil | Aucun si c'est le seul bloqué |
| **R3** | Rejouer l'export de l'état initial | Restaure exactement l'état de départ | Faible si l'export est à jour |

**Préparation obligatoire avant tout test :**

1. `GET cmd 278` pour **les deux bandes** (`subcmd '1'` et `'0'`), et **conserver
   les réponses** hors du dépôt (fichier local ou note chiffrée).
2. Vérifier qu'un **accès de secours** existe :
   - un appareil **filaire** si le routeur a un port Ethernet, **ou**
   - une seconde radio (2,4 GHz depuis un appareil en 5 GHz, ou l'inverse),
     **ou**
   - un câble de récupération / une procédure de réinitialisation du
     routeur que vous avez déjà testée.
3. Ne jamais commencer par un mode `allow` : la première écriture de test doit
   être en `deny` sur un **appareil secondaire**.

**Détection d'un blocage :**

- L'appareil de travail perd le Wi-Fi alors que le routeur est joignable.
- Vérifier depuis un accès de secours : `GET cmd 278` sur les deux bandes.
- Appliquer R1 puis R2.

**Limite à connaître** : la restauration par le client restaure l'état **des
règles**, pas nécessairement un état interne du pilote Wi-Fi. En cas de
blocage persistant, un **redémarrage du routeur** (`cmd 6`, hors périmètre de
cette mission et interdit sans autorisation explicite) peut être nécessaire.
C'est pourquoi la procédure exige un accès de secours **filaire** si possible.

### 9.7 Design de sécurité à retenir

L'écriture est **destructive** : elle remplace la totalité de la liste. Le
client doit donc **refuser** une écriture qui ne repose pas sur une lecture
fraîche.

```kotlin
class MacFilterClient(
    private val transport: RouterTransport,
    private val settings: RouterSettings,
    private val clock: () -> Long = System::currentTimeMillis,
) {
    /**
     * Écrit un ensemble de règles.
     *
     * Refuse l'écriture si [expected] ne correspond plus à l'état courant du
     * routeur : une écriture fondée sur un état périmé effacerait des règles
     * ajoutées entre-temps par l'interface web.
     */
    fun replaceRules(expected: MacFilterState, newRules: List<MacFilterRule>): Result<Unit>
}
```

Règles à implémenter :

1. **Lecture préalable obligatoire** avant toute écriture.
2. **Vérification d'optimisme** : l'état lu doit correspondre à celui que
   l'appelant pense modifier, sinon `Result.failure`.
3. **Lecture de confirmation** après écriture, comme le fait déjà le projet de
   référence pour `cmd 3` (relecture après sauvegarde).
4. **Jeton frais** (`cmd 233`) pour chaque écriture.
5. **Ordre du tableau préservé** : il définit la priorité.
6. **Aucune écriture automatique au premier lancement** : l'utilisateur doit
   confirmer explicitement, car l'opération touche le pare-feu.
7. **Écriture en deux temps** : `cmd 23` (préparation) puis `cmd 20`
   (application). Ces deux appels doivent être **séparés** dans l'API, pour
   que l'utilisateur puisse vérifier l'état *préparé* avant qu'il ne soit
   *appliqué*. Un appel unique « écrire et appliquer » est plus simple, mais
   il ne laisse aucun point de contrôle.

### 9.8 Ce qui n'est pas proposé

- **Aucune écriture implicite.** Aucune méthode ne déclenche de `POST` sans
  appel explicite de l'appelant.
- **Aucune logique de « blocage intelligent »** : le module ne décide pas
  quelles MAC bloquer. C'est l'UI (et l'utilisateur) qui décide.
- **Aucune action de masse** non confirmée : un ajout multiple (l'interface web
  le propose) doit rester une opération explicite et vérifiable.
- **Aucune gestion du filtre Wi-Fi (`cmd 278`)** dans la même version : la
  superposition des deux mécanismes obscurcit le diagnostic (voir §5bis).
- **Aucune abstraction « bloquer/débloquer un appareil »** qui fusionnerait les
  deux notions. Un appareil peut être *connecté* et *bloqué* ; il faut
  pouvoir exprimer les deux états séparément.

---

## 9bis. État de l'implémentation (phase 2)

### 9bis.1 Ce qui est implémenté et testé

| Élément | Fichier | État |
|---|---|---|
| `WifiBandFilter` (`subcmd` ↔ bande) | `router-core/.../wifi/WifiMacFilterModels.kt` | ✅ testé |
| `WifiMacMode` (`close`/`allow`/`deny`) | `router-core/.../wifi/WifiMacFilterModels.kt` | ✅ testé |
| `WifiMacFilterState` + validation MAC | `router-core/.../wifi/WifiMacFilterState.kt` | ✅ testé |
| Exceptions typées (4 catégories) | `router-core/.../wifi/WifiMacFilterExceptions.kt` | ✅ testé |
| `WifiMacFilterClient.read` | `router-core/.../wifi/WifiMacFilterClient.kt` | ✅ testé |
| `WifiMacFilterClient.readWithStructure` | idem | ✅ testé |
| `WifiMacFilterClient.checkWritable` | idem | ✅ testé |
| `WifiMacFilterClient.write` | idem | ✅ testé (faux routeur **uniquement**) |
| `WifiMacFilterClient.restore` | idem | ✅ testé (faux routeur **uniquement**) |
| `WifiMacFilterClient.disable` | idem | ✅ testé (faux routeur **uniquement**) |
| Diagnostic anonymisé `cmd 278` | `poc-cli/.../WifiFilterRenderer.kt` | ✅ testé |
| Option `--wifi-filter` (lecture seule) | `poc-cli/.../Main.kt` | ✅ testé |

**103 tests**, 0 échec. Aucun test n'atteint le routeur réel : le transport
est soit factice (`FakeRouter`), soit un serveur HTTP local sur `127.0.0.1`.

### 9bis.2 La frontière lecture-seule est verrouillée par un test

Le POC expose `--wifi-filter`, qui n'appelle que `read` / `readWithStructure`.
Les méthodes `write`, `restore` et `disable` existent dans `router-core` mais
**aucun chemin du CLI ne les atteint**.

Le test `WifiFilterReadOnlyBoundaryTest` verrouille cette propriété :

| Test | Ce qu'il garantit |
|---|---|
| `reading the filter never sends a POST to command 278` | Un `GET 278` n'entraîne **jamais** de `POST 278` |
| `reading the filter with the structure never sends a POST to command 278` | Idem pour `readWithStructure` |
| `the read commands are limited to the documented allowlist` | Seules les commandes `232`, `100`, `233`, `278` sont émises |
| `the restore path is available for the recovery procedure` | Le filet de sécurité existe et fonctionne (contre le faux routeur) |

> **Point important** : `cmd 233` (jeton d'écriture) est bien envoyé lors d'un
> `read`. C'est **sans danger** : la commande `233` ne fait que *rafraîchir un
> jeton*, elle ne modifie aucune règle. Le jeton n'est de toute façon inutile
> pour une lecture.

### 9bis.3 Correspondance `subcmd` ↔ bande : trois confirmations indépendantes

La correspondance a été vérifiée **trois fois** dans le code du routeur, ce qui
permet de la documenter sans ambiguïté :

| Source | Ce qu'elle montre |
|---|---|
| `js/config/wifiList24g.js:32,71` | Le fichier de la page **2,4 GHz** écrit avec `subcmd: '1'` et lit sa propre bande avec `subcmd: '1'` |
| `js/config/wifiList5g.js:32,71` | Le fichier de la page **5 GHz** écrit avec `subcmd: '0'` et lit sa propre bande avec `subcmd: '0'` |
| `js/config/wifiList24g.js:172` / `wifiList5g.js:172` | Chaque fichier lit **l'autre** bande avec la valeur opposée |
| `js/config/wirelessWpsSetting.js:310` | La page WPS lit le filtre **2,4 GHz** avec `subcmd: 1` |
| `js/config/wireless5gWpsSetting.js:311` | La page WPS lit le filtre **5 GHz** avec `subcmd: 0` |

**Conclusion** : `subcmd '1'` = **2,4 GHz**, `subcmd '0'` = **5 GHz** pour
`cmd 278`.

Et, pour `cmd 132` (WPS), la convention est **inverse** : `subcmd '0'` =
2,4 GHz, `subcmd '1'` = 5 GHz. Confirmé par les champs de réponse lus
(`wlan2g_wps_switch` pour `subcmd 0`, `wlan5g_wps_switch` pour `subcmd 1`).

---

## 9quater. Résultat de la lecture réelle : `cmd 278` est indisponible

> **Statut : DISPONIBLE (firmware Airtel 6.07.0).**

Le test réel, exécuté par l'utilisateur sur son routeur, a renvoyé sur les
deux bandes :

```json
{"success": false, "message": "LIMITED_ACCESS"}
```

C'est **exactement** la réponse que le projet de référence documente pour les
commandes refusées par le firmware Airtel (`reference-zlt/desktop/router_client.py:36-37`
et `:454-455`, pour `cmd 25` — les limites de débit).

### 9quater.1 Ce que cela signifie

| Aspect | Conclusion |
|---|---|
| Le code `cmd 278` existe bien dans le firmware | ✅ confirmé — le binaire du routeur contient la table de commandes |
| La commande est **exécutée** par le firmware | ❌ refusée avant exécution |
| Le filtre Wi-Fi est-il utilisable ? | **Non**, sur ce firmware et ce compte |
| L'interface web le propose-t-elle ? | **Non** — aucun menu de contrôle d'accès Wi-Fi n'est présenté |
| Le filtrage MAC **pare-feu** (`23`) est-il disponible ? | **Indéterminé** — le menu est visible, mais le comportement n'est pas validé |

### 9quater.2 Décisions prises

1. **Le filtrage Wi-Fi (`cmd 278`) est considéré comme indisponible** sur le
   firmware actuel, jusqu'à preuve contraire. Le client et ses tests sont
   **conservés** pour une compatibilité future (autre firmware, autre compte,
   autre opérateur).
2. **Aucun contournement n'est tenté.** `LIMITED_ACCESS` est une décision du
   firmware ; la contourner serait une intrusion non demandée.
3. **Aucune écriture sur le routeur réel.** Seul `GET 278` a été envoyé, et il
   est refusé par le firmware.
4. **Aucun bouton de blocage Wi-Fi** ne doit être exposé dans l'application
   tant que cette capacité n'est pas validée.
5. **Ne jamais présenter le filtrage MAC du pare-feu comme un blocage
   Wi-Fi** : ce sont deux mécanismes distincts (§5bis.1), et seul le second
   empêche une association.

### 9quater.3 Étude distincte à mener : le filtrage pare-feu (`cmd 23`)

Le menu **Pare-Feu → Règles de filtrage → Filtrage MAC** est visible sur le
routeur. C'est le seul mécanisme de restriction accessible. Une étude **en
lecture seule** est donc nécessaire pour déterminer :

- si `GET 23` est autorisé (ou s'il renvoie aussi `LIMITED_ACCESS`) ;
- si les règles existantes sont lisibles ;
- si l'écriture est acceptée ou refusée ;
- **surtout** : si ce mécanisme filtre le **trafic Internet** d'un appareil
  sans empêcher son **association** Wi-Fi.

> Cette étude doit rester **en lecture seule** tant que le comportement n'est
> pas validé sur un **appareil secondaire**. Le client `MacFilterClient` du
> pare-feu (`cmd 23`) n'est d'ailleurs pas encore implémenté — seule la
> lecture du filtre Wi-Fi l'a été.

### 9quater.4 Conséquence pour l'application

L'application doit :

- présenter le filtrage MAC **pare-feu** et le filtrage MAC **Wi-Fi** comme
  deux fonctionnalités **distinctes**, avec leur disponibilité réelle ;
- n'exposer **aucune** action de blocage si la capacité n'est pas validée ;
- afficher un message **explicite** expliquant *ce qui* est indisponible et
  *pourquoi* — pas un simple « non supporté ».

---

## 9ter. Procédure de blocage et de déblocage d'un appareil secondaire

> **Aucune de ces étapes ne doit être exécutée avant validation du protocole
> de blocage.** La procédure est écrite pour être appliquée plus tard, avec
> votre accord.

### 9ter.1 Préparation (à faire AVANT toute écriture)

| # | Étape | Vérification |
|---|---|---|
| P1 | Identifier le MAC de l'**appareil secondaire** | Il doit être **différent** de celui de l'appareil de travail |
| P2 | Vérifier le mode actuel des deux bandes | `GET 278 subcmd '1'` et `GET 278 subcmd '0'` — noter mode + nombre d'entrées |
| P3 | **Exporter l'état complet** des deux bandes | Fichier local hors dépôt, lisible par vous seul |
| P4 | Vérifier que le **WPS est désactivé** sur les deux bandes | `GET 132 subcmd '0'` et `GET 132 subcmd '1'` → `wlan2g_wps_switch` / `wlan5g_wps_switch` |
| P5 | Vérifier que le **Wi-Fi est prêt** | `GET 417` → `wifiStatus == '1'` |
| P6 | **Établir un accès de secours** | Port Ethernet du routeur, **ou** seconde radio, **ou** procédure de récupération déjà testée |
| P7 | Vérifier que l'appareil secondaire est **connecté** à la bande visée | Il doit figurer dans `cmd 224` / `cmd 225` |

### 9ter.2 Blocage

| # | Étape | Ce qui se passe |
|---|---|---|
| B1 | `GET 278 subcmd '<bande>'` | Lecture de l'état courant |
| B2 | Construire le nouvel état : mode `deny` + la MAC du secondaire | Les règles existantes sont **conservées** (`withEntry`) |
| B3 | `POST 278 subcmd '<bande>'` avec `datas: { macfilter, maclist }` | Le routeur applique **immédiatement** |
| B4 | `GET 278 subcmd '<bande>'` | **Lecture de vérification** obligatoire |
| B5 | Vérifier que l'appareil secondaire **n'est plus** dans `cmd 224` / `cmd 225` | C'est le signe que l'association a bien été coupée |

**Point d'attention** : l'effet sur un appareil **déjà associé** n'est pas
documenté (§10). Si la session du secondaire ne tombe pas, il faudra soit
attendre une réassociation, soit le désactiver/réactiver côté Wi-Fi.

### 9ter.3 Débloquage

| Rang | Action | Effet | Risque |
|---|---|---|---|
| **D1** | Mode `close` sur la bande | Désactive le filtre, **conserve** la liste | Aucun — geste de secours principal |
| **D2** | Retirer la MAC du `deny` (mode `deny` conservé) | Débloque un seul appareil | Aucun |
| **D3** | Rejouer l'export de l'étape P3 | Restaure l'état exact de départ | Faible si l'export est à jour |

**Si l'appareil de travail perd le Wi-Fi pendant le test** :

1. Depuis l'accès de secours (P6), faire un `GET 278` sur les deux bandes.
2. Appliquer **D1** (`macfilter: close`) sur la bande concernée.
3. Reconnecter l'appareil de travail.
4. Vérifier par `GET 278` que le mode est bien `close`.
5. Ne reprendre le test qu'après avoir rétabli un accès de secours fiable.

> **Ne jamais tester `ALLOW` avec une liste vide** : cela coupe l'accès de
> tous les appareils sans fil, y compris de l'appareil de travail. Le client
> refuse cette combinaison par construction (`WouldLockOutEveryDevice`), mais
> l'interface web du routeur ne le refuse pas.

### 9ter.4 Ce qui n'a pas été fait

- Aucun `POST 278` n'a été envoyé au routeur réel.
- Aucune règle n'a été créée, modifiée ou supprimée.
- Aucun appareil n'a été bloqué.
- Le WPS n'a pas été modifié.

---

## 10. Écarts et inconnues

| Élément | Statut | Action |
|---|---|---|
| Noms des clés de réponse (`datas`) | Déduit du code du routeur, **non observé** | Confirmer par `GET cmd 23` (étape 1 de la §7.2) |
| Présence de `token` obligatoire sur `cmd 23` POST | Déduit du mécanisme `Page.postJSON` | Confirmer par lecture du code + essai contrôlé |
| Comportement de `cmd 28` en écriture | **Inconnu** — l'interface écrit via `cmd 30` | Ne pas utiliser `cmd 28` en écriture |
| Signification du champ `getfun: true` | **Inconnu** — présent uniquement dans le `GET cmd 23` | L'ignorer ; le serveur l'accepte sans lui |
| Effet réel du filtrage sur le Wi-Fi | **À confirmer** (§6.1) | Observer l'appareil de test : associé mais sans accès |
| Portée IPv6 de `ippro` | **À confirmer** | Un appareil secondaire en IPv6 pur serait nécessaire |
| Comportement si `acceptAll = false` (liste blanche) | **À confirmer** | Test risqué : ne le faire qu'avec un seul appareil Wi-Fi connecté |
| Champs additionnels dans `datas` (pare-feu) | Inconnu | Inspecter la réponse brute lors de la lecture |
| Relation entre `cmd 23` et `cmd 278` | **Inconnue** — deux stockages distincts | Ne pas activer les deux mécanismes simultanément |
| **Clés de réponse de `cmd 278`** | **Confirmé par le code** : `datas.macfilter` (string) et `datas.maclist` (tableau d'objets `{mac, remarks}`) | Lecture réelle autorisée — en attente |
| **Forme brute de la MAC renvoyée par `cmd 278`** | Le routeur peut renvoyer n'importe quelle casse / séparateur ; **la normalisation en `AA:BB:CC:DD:EE:FF` est implémentée côté client** | Confirmée par le test `mac addresses are normalised to upper case colon form` |
| **`cmd 278` avec `subcmd` absent** | **Inconnu** — le client web envoie toujours `'0'` ou `'1'` | Le client n'envoie jamais de `subcmd` absent |
| **Effet de `cmd 278` sur un appareil déjà associé** | **À confirmer** : le filtre est-il appliqué immédiatement, ou à la prochaine association ? | Appareil secondaire déjà associé, en mode `deny`, puis vérifier si la session est coupée |
| **Effet du mode `allow` + liste vide** | **À confirmer** — Theory: verrouillage total | **Ne jamais tester** sans accès de secours filaire |
| **Précondition `cmd 417`** | **Implémentée** : le client refuse d'écrire si `wifiStatus != '1'` | Comportement réel de `wifiStatus` à observer en lecture |
| **Garde-fou WPS** | **Implémenté** : le client refuse d'écrire si le WPS est actif, et ne modifie jamais le WPS | L'effet réel du WPS sur le filtre est déduit, non observé |
| **Synchronisation inter-bandes** | Déduit du code ; son effet réel (écrase bien la bande cible) n'est pas observé | Test contrôlé, appareil secondaire, après export |
| **Correspondance `subcmd` ↔ bande** | **Confirmée 3 fois** dans le code du routeur (§9bis.3) | — |
| **Convention `subcmd` de `cmd 132` (WPS)** | **Confirmée** : inverse de `cmd 278` | — |
| **Exclusion mutuelle WPS / filtre MAC** | **Confirmée par le code**, dans les deux sens (§5bis.10) | Comportement firmware réel à observer lors du test |

---

## 11. Ce qui reste hors périmètre

- ❌ Implémentation Android / Jetpack Compose.
- ❌ Exécution de toute écriture de filtrage MAC.
- ❌ Test de blocage sur un appareil actuellement connecté et utilisé.
- ❌ Analyse REA : **inutile ici**. Le protocole a été obtenu dans le code
  JavaScript du routeur lui-même, ce qui est une source plus fiable et plus
  complète qu'une analyse binaire du firmware.

---

## 12. Annexe — Extraits de code du routeur

### 12.1 `main.js:2273-2287` — la commande d'application

```javascript
applyFilter: function () {
    Page.postJSON({
        json: {
            cmd: RequestCmd.APPLY_FILTER,
            method: JSONMethod.POST
        },
        success: function (data) {
            if (data.success == true) { fyAlertMsgSuccess(); }
            else { fyAlertMsgFail(); }
        }
    });
}
```

### 12.2 `main.js:1790-1805` — l'injection automatique du jeton et de la session

```javascript
if(json.method == "POST") {
    getMethod="POST";
    json.token = sessionStorage["token"];
} else {
    getMethod="GET";
}
if (!json.language) { json.language = Page.language; }
if (!json.sessionId) { json.sessionId = sessionStorage['sessionId'] || ""; }
```

### 12.3 `macFilter.js:139-189` — création et modification d'une règle

```javascript
btnSave: function () {
    if(this.flag === "" && this.value.length >=32){
        AlertUtil.alertMsg(CHECK.required.ruleNum); return false;
    }
    if (this.ipAddressValue == "") { AlertUtil.alertMsg(CHECK.required.mac); return false; }
    if (!CheckUtil.checkMac(this.ipAddressValue)) { AlertUtil.alertMsg(CHECK.format.mac); return false; }
    var arr = {
        enableLink: this.radioModelValue,
        remark: this.commentValue,
        ippro: this.ipproValue,
        mac: this.ipAddressValue.match(/[0-9a-f]{2}/ig).join(":").toUpperCase()
    }
    if (!CheckUtil.checkInvalidMac(arr.mac)) { AlertUtil.alertMsg(CHECK.tip.filterMacAddress); return false; }
    // ... contrôle de doublon ...
    if(typeof this.flag === "number") {
        arr.enableRule = this.value[this.flag].enableRule;   // édition : on conserve l'activation
        this.value[this.flag] = arr;
    } else {
        arr.enableRule = true;                                // création : activée
        this.value.push(arr);
    }
}
```

### 12.4 `defaultMac.js:28-51` — écriture de la règle par défaut

```javascript
btnSave: function () {
    this.disabled = true;
    var datas = [{
        "enableRule": true,
        "acceptAll": this.acceptAllIPv4,
        "ippro": "IPV4"
    }, {
        "enableRule": true,
        "acceptAll": this.acceptAllIPv6,
        "ippro": "IPV6"
    }]
    Page.postJSON({
        json: {
            cmd: RequestCmd.MAC_DEFAULT_FILTER,
            method: JSONMethod.POST,
            success: true,
            datas: datas
        },
        success: function (data) {
            vm.disabled = false;
            AlertUtil.alertMsg(PROMPT.saving.success);
        }
    });
}
```

### 12.5 `js/config/wifiList24g.js:18-60` — écriture du filtre Wi-Fi

```javascript
btnSave: function (list) {
    Page.postJSON({
        json: { cmd: RequestCmd.WIFI_INIT_STATUS },      // cmd 417 : précondition
        success: function (data) {
            if(data.wifiStatus !== '1'){
                AlertUtil.alertMsg(CHECK.required.wifiStatus);
                return false;
            }
            var json = {};
            fyAlertMsgLoading();
            json.method = JSONMethod.POST;
            json.cmd = RequestCmd.WIRELESS_MACFILTER;      // cmd 278
            json.subcmd = '1';                             // '1' = 2,4 GHz, '0' = 5 GHz
            json.success = true;
            var datas = {};
            if (list) {
                datas = list                              // synchronisation inter-bandes
            } else {
                datas = {
                    macfilter: vm.macfilter,               // "close" | "allow" | "deny"
                    maclist: vm.maclist                    // [ { mac, remarks } ]
                }
            }
            json.datas = datas;
            Page.postJSON({
                json: json,
                success: function (data) {
                    if (data.success == true) {
                        fyAlertMsgSuccess();
                        vm.getData();                     // relecture de confirmation
                    } else {
                        fyAlertMsgFail();
                    }
                }
            });
        }
    })
}
```

### 12.6 `js/config/wifiList24g.js:66-87` — lecture du filtre Wi-Fi

```javascript
getData: function () {
    Page.postJSON({
        json: {
            cmd: RequestCmd.WIRELESS_MACFILTER,           // cmd 278
            method: JSONMethod.GET,
            subcmd: '1'
        },
        success: function (data) {
            if (data.hasOwnProperty("datas")) {
                var data = data.datas;
                vm.macfilter = data.macfilter;
                for(var i = 0; i < data.maclist.length; i++) {
                    data.maclist[i].mac = data.maclist[i].mac
                        .match(/[0-9a-f]{2}/ig).join(":").toUpperCase();
                }
                vm.maclist = data.maclist || [];
            } else {
                vm.macfilter = 'close';
                vm.maclist = [];
            }
        }
    });
}
```

### 12.7 `js/config/wifiList24g.js:149-163` — garde-fou WPS

```javascript
getWpsData: function () {
    Page.postJSON({
        json: {
            cmd: RequestCmd.WPS_CONFIG,                   // cmd 132
            subcmd: 0                                     // '0' = 2,4 GHz, '1' = 5 GHz
        },
        success: function (data) {
            if(data.wifiOpen == "1"){
                vm.wpsEnable = data.wlan2g_wps_switch == "1" ? true : false;
            } else {
                vm.wpsEnable = false;
            }
        }
    })
}
```

et, dans `html/config/wifiList24g.html:33`, le filtre est désactivé si :

```html
:disabled="Page.meshDisabled || Page.meshEnable == '1' || wpsEnable"
```

### 12.8 `html/config/wifiList24g.html:33-37` — les trois modes

```html
<el-select v-model="macfilter" ...>
   <el-option key="0" :label="DOC.lbl.dataRoamingStop" value="close"></el-option>
   <el-option key="1" :label="DOC.lbl.accept"          value="allow"></el-option>
   <el-option key="2" :label="DOC.lbl.reject"          value="deny"></el-option>
</el-select>
```

avec, dans `js/language/fr.js` :

```javascript
DOC.lbl.accept          = "liste blanche";
DOC.status.accept       = "Autoriser la connexion";
DOC.lbl.reject          = "liste noire";
DOC.status.reject       = "bloquer la connexion";
DOC.lbl.dataRoamingStop = "fermeture";
```
