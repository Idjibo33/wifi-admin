# Analyse du pare-feu ZLT — filtrage MAC (`cmd 23`)

> **Statut : `UNAVAILABLE` — investigation close le 30/10/2026.**
>
> Routeur : ZLT W304VA PRO, Airtel Niger, firmware **6.07.0**, interface web
> du **17 avril 2025**.
>
> **Aucune requête d'écriture n'a jamais été envoyée.** Le filtrage MAC n'est
> pas une fonctionnalité de l'application Android et ne doit pas le devenir
> sur ce firmware.
>
> **Preuve de clôture :** l'interface web **officielle** du routeur, observée
> dans Chrome DevTools (*Pare-Feu → Règles de filtrage → Filtrage MAC*),
> reçoit **exactement la même réponse creuse** que notre client :
> `{"success": true, "cmd": 23, "message": ""}`, HTTP 200, **sans `datas`**.
> Deux clients indépendants obtiennent la même réponse : la requête n'est pas
> en cause, le firmware ne sert pas la commande. Voir **§13ter**.
>
> Ce document conserve l'historique des missions 04 à 07. Les sections §0,
> §0bis, §10, §14 et §15 sont **historiques** : elles décrivent l'état de la
> connaissance *avant* l'observation du 30/10/2026 et sont **périmées** comme
> recommandations.

---

## 0. Historique — lecture réelle (mission 05)

> **Section historique.** Elle décrit ce que l'on savait après la lecture du
> 29/10/2026, avant l'observation de l'interface officielle. La conclusion
> définitive est en **§13ter** : la capacité est `UNAVAILABLE`.

Le 29/10/2026, l'utilisateur a exécuté le POC avec `--firewall-filter`. Le
routeur a répondu, pour `GET 23` **et** `GET 30` :

```text
clés de réponse   cmd, message, success
anomalies         DATAS_MISSING=1
```

**Ce que cela établit :**

| Question | Réponse | Preuve |
|---|---|---|
| Le firmware **refuse-t-il** la commande ? | **Non.** Un refus Airtel est toujours `{"success": false, "message": "LIMITED_ACCESS"}`. Le client lève une exception dans ce cas — or aucune n'a été levée : `success` était donc **vrai**. | P1 : comportement `cmd 278` relevé ; `ZltRouterClient.isSuccess` |
| La session était-elle invalide ? | **Non.** Une session expirée répond `NO_AUTH` / `LOGIN_TIMEOUT` ; le client aurait reloggué et rejoué la lecture. | Comportement `read()` |
| La réponse est-elle une **liste vide légitime** ? | **Non.** `datas` est **absent** de la réponse. Une liste vide confirmée est `datas: []`. | Observation directe |
| Les commandes sont-elles correctes ? | **Oui** pour `cmd` et `method`. Mais le client **n'envoie pas `language`**, contrairement à l'interface web du routeur qui l'envoie **toujours**. | P1 : `main.js:1799-1801` |
| Le champ `message` est-il un statut ? | **Non.** C'est un canal polymorphe. Sur `success: true`, l'interface web du routeur ne le lit **jamais**. | P1 : `main.js:1853-1854` |

**Conclusion :** le routeur a **accepté** les deux commandes et n'a livré
**aucune donnée**. C'est un **succès sans contenu**, pas un refus, pas une
liste vide.

### 0.1 Hypothèses restantes, classées

> **Clôture :** ces hypothèses sont **tranchées** depuis le 30/10/2026. L'interface
> officielle reçoit la même réponse creuse ; aucune variante de payload ne peut
> donc être la condition manquante. Voir §13ter.

| Rang | Hypothèse | Statut |
|---|---|---|
| 1 | **Paramètre manquant.** `GET 23` attend `getfun: true` (mentionné par la transcription de `macFilter.js`, source non vérifiable) ; `GET 30` pourrait attendre `subcmd`. Le framework CGI remplit alors `message` et met `success: true`. | **P2/P3** — `getfun` est **absent de tous les JS capturés** (5 600 lignes) ; `macFilter.js` n'est pas en cache |
| 2 | **`language` manquant.** L'interface web envoie toujours `language` ; notre client ne l'envoie jamais. | **P1 pour l'écart**, P3 pour la causalité |
| 3 | **Handler non câblé sur ce build.** Le dispatcher accepte la commande, n'a pas de cas particulier, renvoie un succès creux. | P3 |
| 4 | **Feature flag firmware.** Le menu existe, le handler est neutralisé (`webPageFlag`, `Page.level`). | P3 |
| 5 | **La valeur de `message` est le levier.** Elle distingue les hypothèses 1–4. **Jamais observée** : le diagnostic ne montrait que les clés. | **P3 — c'est l'objet de la mission 05** |

> **Le contenu de `message` n'a jamais été observé.** Le diagnostic actuel le
> catégorise sans jamais publier sa valeur : c'est délibéré (sécurité), mais
> cela empêche de trancher. La catégorie seule (`confirmation`,
> `paramètre manquant`, `valeur non reconnue`) est le prochain levier.

---


## 0bis. Mission 07 — conclusions consolidées

### 0bis.1 L'observation la plus récente

Le 30/10/2026, avec `language` transmis :

```text
GET 23 : success=true, message vide, datas absent
GET 30 : success=true, message vide, datas absent
```

### 0bis.2 Deux corrections à la mission 05

**Le « message vide » n'est pas un changement de comportement.** L'hypothèse
« `language` a changé la réponse du firmware » **n'est pas soutenue** :

| Ce que dit l'hypothèse | Ce que montrent les faits |
|---|---|
| « le `message` est passé de non-vide à vide » | Le diagnostic n'affiche **jamais** la valeur du `message`, seulement sa **catégorie**. Le rapport de la mission 05 disait `clés de réponse : cmd, message, success` — cela établit la **présence** de la clé, **pas** sa valeur. Le `message` pouvait déjà être vide. |
| « `language` a provoqué le changement » | `language` a été ajouté le **30/10 à 10:00** (`mtime` de `ZltRouterClient.kt`). L'observation de la mission 05 est du **29/10**. La comparaison « avant / après `language` » **n'a jamais été faite** sur le routeur. |

> **Conclusion défendable** : le `message` est **présent et vide**. C'est un
> **témoin discriminant** entre les variantes, **pas** une preuve de changement
> de firmware.

**Une vraie erreur de type a été trouvée et corrigée.** Notre variante
`getfun` envoyait `getfun: true` — un **booléen JSON**. Or ce firmware
encode **tous** ses drapeaux en **chaîne** : `acceptAll: "true"`,
`isAutoUpgrade: "0"`, `subcmd: '1'`. Un parseur strict qui attend
`strcmp(..., "true")` **ignore** un booléen. La variante est désormais
`getfun: "true"`, vérifié sur le câble.

### 0bis.3 Ce que le JavaScript officiel prouve

| Fait | Conséquence |
|---|---|
| `cmd 23` est appelé avec `getfun: true` (`macFilter.js:57`) | Le drapeau existe **dans la source de la page elle-même**. C'est la seule divergence connue avec notre client. |
| `cmd 23` fait `data.datas.length` **sans garde-fou** | **L'interface officielle planterait** si `datas` était absent. Elle **ne** considère donc **pas** une réponse sans `datas` comme une liste vide. |
| `cmd 30` fait `data.datas[0]` **sans garde-fou** | Idem : `TypeError`. |
| `cmd 28` fait `if (!!data.datas) … else …` | Seul `cmd 28` est protégé — et c'est le **seul** qui ne planterait pas. |
| `cmd 278` (Wi-Fi) fait `hasOwnProperty("datas")` | Le mécanisme Wi-Fi est protégé ; **pas** transposable au pare-feu. |
| `Page.postJSON` n'ajoute que `token` (POST), `language`, `sessionId` | Aucun hash, aucun `subcmd` automatique, aucun `getfun`. |

> **Cela valide la prémisse de la mission** : notre client ne présente
> **jamais** une réponse sans `datas` comme une liste vide, et l'interface
> officielle non plus — elle **échoue** visiblement.

### 0bis.4 Le discriminant le plus utile : le `message` vide

| Commande | Réponse | Forme |
|---|---|---|
| `223`, `224`, `225` | fonctionnent | `success` + **clé de données**, **jamais** de `message` |
| `23`, `30` | échec | `success` + `message` **vide** + **aucune** clé de données |

Les commandes qui marchent **n'ont pas** de champ `message`. Celles qui
échouent **en ont un, vide**. C'est la trace d'un **chemin de code
différent** : le dispatcher remplit un gabarit `{cmd, message, success}`
quand le handler ne livre rien.

### 0bis.5 Recommandation — exécutée et conclusive

La recommandation était : **observer l'interface officielle avant toute
nouvelle variante de payload**. Elle a été suivie le 30/10/2026.

**Résultat :** l'interface officielle a reçu la **même** réponse creuse
(`success: true`, `message: ""`, pas de `datas`). Le juge de paix a parlé :
la capacité est `UNAVAILABLE`. Les pistes `getfun` et `cmd 28` sont donc
**fermées**, et non simplement « non testées ». Voir §13ter.

---

## 1. Résumé exécutif

Le filtrage MAC du **pare-feu** (`cmd 23`) est un mécanisme distinct du
filtrage d'**association Wi-Fi** (`cmd 278`), qui renvoie `LIMITED_ACCESS` sur
ce firmware.

Ce que l'on sait avec assez de confiance pour **lire** :

| Point | État |
|---|---|
| `cmd 23` = `MAC_FILTER`, `cmd 30` = `MAC_DEFAULT_FILTER`, `cmd 20` = `APPLY_FILTER` | Identifiés dans la table `RequestCmd` du routeur |
| Une règle porte `enableRule`, `enableLink`, `ippro`, `mac`, `remark` | Cité depuis `macFilter.js` |
| La lecture se fait en `GET`, l'écriture en `POST` | Cité depuis le code de l'interface |
| 32 règles maximum, l'ordre du tableau **est** la priorité | Cité depuis `macFilter.js` |
| `cmd 30` porte deux entrées, IPv4 et IPv6, avec `acceptAll` | Cité depuis `defaultMac.js` |

Ce qui restait **non prouvé** au moment de l'étude (tranché le 30/10/2026,
voir §13ter) :

| Point | Risque |
|---|---|
| `GET 23` est-il **autorisé** sur ce firmware ? | Il peut répondre `LIMITED_ACCESS`, comme `cmd 278` |
| Le nom de la clé de réponse est-il bien `datas` ? | Le parser accepte l'absence et le signale, mais la lecture réelle seule tranche |
| `enableLink = false` signifie-t-il « liste noire » ? | Déduit de `enableLink = !acceptAll` ; jamais observé |
| Le filtre coupe-t-il Internet **en gardant** le LAN et l'administration ? | **Aucune preuve.** Il peut couper les trois |
| `ippro = "IPV4V6"` existe-t-il et couvre-t-il réellement IPv6 ? | Mentionné, jamais observé |

**Statut final : `UNAVAILABLE`.** Le firmware **exécute** la commande
(`success: true`, HTTP 200) mais ne livre **aucune donnée** — et l'interface
web officielle reçoit la même réponse creuse (§13ter). La lecture seule est
implémentée et testée, mais **aucune** opération de blocage ou de déblocage
n'est exposée, ni dans l'application ni dans le CLI.

---

## 2. Méthode et niveaux de preuve

| Niveau | Définition |
|---|---|
| **P1** | Extrait du **JavaScript original du routeur**, cité avec fichier et ligne |
| **P2** | Transcription ou affirmation secondaire, sans extrait reproductible |
| **P3** | Déduction, hypothèse ou analogie avec un autre mécanisme |

> **Mise à jour mission 05.** Les **JS originaux du routeur** ont été
> retrouvés en cache local et vérifiés : `main.js` (105 109 o,
> md5 `63f385e849f3b1430656859a914517e7`), `login.js` (16 171 o),
> `helper.js` (57 895 o), `zlt-ui/wifiList24g.js` (7 108 o). Les
> affirmations qui en sont tirées passent de **P2 à P1**.
>
> `macFilter.js` et `defaultMac.js` **ne sont toujours pas disponibles** :
> tout ce qui les concerne reste **P2**.

Les JS capturés n'ont pas été ajoutés au dépôt : ils sont volumineux et leur
provenance (date de capture, checksum) devrait être consignée avant de devenir
opposables.

Le dépôt `reference-zlt` (client tiers) a été fouillé : **aucune** occurrence
de `firewall`, `macFilter`, `blacklist`, `whitelist`, ni des commandes
`20/23/28/30`. Il ne peut donc servir de preuve pour ce mécanisme.

### 2.1 Ce que le JavaScript original prouve (P1)

| Fait | Source |
|---|---|
| `RequestCmd` contient `APPLY_FILTER: 20` … `MAC_FILTER: 23` … `MAC_DEFAULT_FILTER: 30` | `main.js:50-62` |
| `Page.postJSON` injecte **`language` toujours**, et `token` **uniquement pour `POST`** | `main.js:1792-1806` |
| `message` n'est lu **que** sur la branche `success == false` | `main.js:1853-1893` |
| `message` est polymorphe : `"0"` = succès sur `cmd 132`, payload sérialisé sur `cmd 82` | `wirelessWpsSetting.js:131`, `main.js:3185` |
| `"data error"` et `"parameter error"` **n'existent pas** dans le firmware | grep négatif sur 5 600 lignes |
| `getfun` **n'apparaît nulle part** | grep négatif |
| `cmd 31` n'est utilisé par aucune page de ce build (`FW_ACL_FILTER` → `ACL_FILTER` = 217) | `main.js:591-594`, `:133` |
| `cmd 24` = liaison IP ↔ MAC, deux pages UI | `main.js:404-407`, `:579-582` |
| `cmd 28` est **commenté** dans la table des menus | `main.js:565` |

---

## 3. Conclusions des sous-agents

### 3.1 Sous-agent 1 — Analyse du protocole

- Les quatre commandes du pare-feu sont identifiées dans la table
  `RequestCmd` : `APPLY_FILTER=20`, `MAC_FILTER=23`, `DEFAULT_FILTER=28`,
  `MAC_DEFAULT_FILTER=30` (**P1**).
- Toute requête part en HTTP `POST` vers `/cgi-bin/http.cgi` ; le verbe
  fonctionnel est le champ `method` du corps JSON. Le `token` (`cmd 233`)
  n'est ajouté que pour `method == "POST"` (**P1**).
- La forme exacte de `GET 23` n'est **pas** reproduite dans le dépôt : la
  matrice l'affirme, mais aucun extrait de `macFilter.js:52-66` ne la montre
  (**P2/P3**). C'est le point que le test réel doit lever en premier.
- Seule la clé de réponse `datas` est citée (`data.datas`) ; le contenu, les
  champs supplémentaires et les types ne sont pas observés (**P1** pour la
  clé, **P3** pour le reste).
- Une règle porte exactement `enableLink`, `remark`, `ippro`, `mac`, plus
  `enableRule` (**P1**).
- `enableRule` est l'activation de la règle ; il vaut `true` à la création
  (**P1**).
- `enableLink` reçoit `!acceptAll` : `false` serait la liste noire, `true` la
  liste blanche (**P1** pour l'initialisation, **P3** pour la sémantique).
- `ippro` vaut `"IPV4"` / `"IPV6"` de façon certaine dans `cmd 30` ;
  `"IPV4V6"` n'apparaît dans aucun extrait (**P1/P3**).
- La MAC est normalisée en majuscules séparées par `:` (**P1**).
- Il n'existe **aucun** champ `priority` : la colonne affiche `index + 1`,
  donc l'ordre du tableau est la priorité (**P1**).
- Limite de **32 règles** (**P1** pour le premier contrôle, **P2** pour le
  second).
- `cmd 28` ne sert qu'à lire l'état « tout accepter » ; l'écriture de la
  règle par défaut passe par `cmd 30`, sans `cmd 20` (**P1**).
- `cmd 20` applique les règles préparées par `POST 23` ; il est **global** au
  pare-feu (**P1**).

**Questions ouvertes** : `GET 23` est-il autorisé ? Les booléens sont-ils
renvoyés en booléens, en `"0"`/`"1"` ou en `1`/`0` ? `enableLink` est-il
global ou par règle ? `"IPV4V6"` existe-t-il ? Le filtre préserve-t-il
l'association Wi-Fi et l'accès au LAN ?

### 3.2 Sous-agent 2 — Architecture Kotlin

- **Recommandation initiale** : extraire `internal class ZltSession` pour
  partager authentification, session, transport et erreurs, puis exposer
  `FirewallMacFilterClient`.
- **Repli accepté** : méthodes additives sur `ZltRouterClient`, avec **une
  seule session**, aucune duplication du login. Une troisième copie du login
  (comme celle de `WifiMacFilterClient`) est écartée.
- Modèles proposés : règle avec MAC, verdict, protocole, activation,
  remarque ; état = règles + politique par défaut ; pas de champ
  `priority`.
- Vocabulaire **séparé** de `WifiMacMode` : `acceptAll = true` implique
  « liste noire », soit l'inverse de `allow`/`deny`. Réutiliser le vocabulaire
  Wi-Fi serait un piège.
- Allowlist : `{232, 100, 23, 30}`. Ni `20`, ni `233`, ni `278`. `cmd 28`
  omis : `cmd 30` donne la même information pour IPv4 **et** IPv6.
- `LIMITED_ACCESS` doit être une erreur distincte, pas un échec générique.
- Impact sur l'app Android : **nul**.

**Décision retenue par l'agent principal** : repli additif. `ZltRouterClient`
garde la session et expose une porte `internal readOnly(cmd)` avec allowlist.
`FirewallMacFilterClient` réutilise cette session. L'extraction de
`ZltSession` est reportée : elle déplacerait ~200 lignes du fichier le plus
critique du module pour un bénéfice nul tant que la lecture n'est pas
validée sur le matériel. Le compromis est documenté : `ZltRouterClient`
devient le point d'entrée de deux domaines, et son KDoc a été mis à jour.

### 3.3 Sous-agent 3 — Tests et qualité

- Réutiliser `FakeRouter.onJson(23, "GET")` — pas `onSubcmd`, la commande est
  globale.
- Ajouter des fixtures `firewallRulesReply`, `firewallRule`,
  `firewallDefaultsReply`.
- Couvrir : forme de la requête, liste vide, une règle, plusieurs règles,
  champs absents, types `true`/`"1"`/`1`, MAC invalide, `ippro` inconnu,
  doublons, 32 et 33 règles, JSON malformé, `success: false`,
  `LIMITED_ACCESS`, expiration de session, panne réseau.
- **Test-frontière** : un transport qui lève `AssertionError` **avant** de
  déléguer si `cmd == 20`, `cmd == 233`, `POST 23`, `POST 30`, ou tout `POST`
  autre que `100`. `AssertionError` et non `Exception`, car le client
  convertit les `RuntimeException` en erreur réseau mais pas les `Error`.
- Vérifier le **multiset exact** des couples `(cmd, method)` : une allowlist
  par `cmd` seul laisserait passer un `POST 23`.
- Le faux routeur doit refuser les écritures **sans muter** son état.

### 3.4 Sous-agent 4 — Sécurité et audit

- **Aucun client `cmd 23` n'existait** ; tout le code d'écriture livré
  concernait `cmd 278`.
- Défauts à ne pas recopier depuis `WifiMacFilterClient` : rejeu aveugle
  après reconnexion, jeton `cmd 233` mis en cache, gel d'authentification
  ignoré, messages non rédigés.
- **`cmd 30` est l'opération la plus dangereuse** : bascule immédiate en
  liste blanche, globale IPv4/IPv6, sans `cmd 20`. Une erreur verrouille tous
  les appareils.
- Une coupure entre `POST 23` et `POST 20` laisse des règles **stockées mais
  non appliquées**, qu'un « Appliquer » ultérieur dans l'interface web
  activera.
- Aucun garde-fou « ma propre MAC » : `locksOutEveryDevice` ne couvre que
  `ALLOW` + liste vide.
- Les remarques sont des données personnelles (souvent un prénom) ; les MAC
  aussi. Les diagnostics doivent les masquer.
- Transport en HTTP clair ; les redirections sont suivies par défaut.
- **Verdict** : écriture **non défendable en l'état**, envisageable un jour
  comme expérience bornée, sous conditions cumulatives.

---

## 4. Commandes identifiées et preuves

| `cmd` | Nom dans `RequestCmd` | Lecture | Écriture | Preuve |
|---|---|---|---|---|
| `23` | `MAC_FILTER` | `GET` | `POST datas` | P1 (table), P2/P3 (forme exacte) |
| `28` | `DEFAULT_FILTER` | `GET` | — (l'UI n'écrit pas avec) | P1 |
| `30` | `MAC_DEFAULT_FILTER` | `GET` | `POST datas` | P1 |
| `20` | `APPLY_FILTER` | — | `POST` | P1 |
| `233` | `RESET_LOGIN_TIME` | jeton d'écriture | — | P1 |

### 4.1 Enveloppe de lecture

```json
{ "cmd": 23, "method": "GET", "sessionId": "<session>", "language": "fr" }
```

- L'URL est `/cgi-bin/http.cgi`, en HTTP `POST` au niveau transport.
- Aucun `token`, aucun `datas`, aucun `subcmd`.
- Le drapeau `getfun` est mentionné par l'analyse mais **jamais observé**
  dans les JS capturés. Le client ne l'envoie pas par défaut : inventer un
  champ serait pire que de constater son absence.
  > **Clôture :** l'hypothèse a été testée (variante `getfun`, envoyée comme
  > chaîne) et **écartée** — l'interface officielle reçoit la même réponse
  > creuse. Voir §13ter.

### 4.2 Forme d'une règle

```json
{
  "enableRule": true,
  "enableLink": false,
  "ippro": "IPV4",
  "mac": "AA:BB:CC:DD:EE:FF",
  "remark": ""
}
```

| Champ | Type attendu | Sens | Preuve |
|---|---|---|---|
| `enableRule` | booléen | règle active | P1 |
| `enableLink` | booléen | `false` = liste noire, `true` = liste blanche | P1 init., P3 sens |
| `ippro` | chaîne | `IPV4`, `IPV6`, `IPV4V6` | P1/P3 |
| `mac` | chaîne | `AA:BB:CC:DD:EE:FF` | P1 |
| `remark` | chaîne | texte libre | P1 |

L'ordre du tableau `datas` est la priorité. Il n'y a pas de champ
`priority`.

### 4.3 Règle par défaut (`cmd 30`)

```json
{
  "datas": [
    { "enableRule": true, "acceptAll": "true", "ippro": "IPV4" },
    { "enableRule": true, "acceptAll": "true", "ippro": "IPV6" }
  ]
}
```

- `acceptAll = "true"` : tout est accepté → une règle agit en **liste
  noire**.
- `acceptAll = "false"` : tout est refusé sauf exceptions → **liste
  blanche**.
- `cmd 30` s'applique **immédiatement**, sans `cmd 20`.

### 4.4 Application (`cmd 20`)

`POST 23` **stocke** la liste complète ; `POST 20` **applique**. `cmd 20`
est global : il applique tout le pare-feu en attente, pas seulement les
règles MAC.

---

## 5. Ce que le client de lecture seule implémente

Package `dev.wifiadmin.router.firewall`.

| Élément | Rôle |
|---|---|
| `FirewallMacFilterClient` | `readRules()` (`GET 23`), `readDefaults()` (`GET 30`), `readState()` |
| `FirewallMacRule` | une règle, avec valeur brute et valeur interprétée |
| `FirewallMacRules` | liste + clés observées + anomalies + limite 32 |
| `FirewallDefaults` | politique IPv4 / IPv6 |
| `FirewallAnomaly` | ce qui n'a pas pu être lu, **sans contenu sensible** |
| `RouterFeatureUnavailableException` | refus firmware (`LIMITED_ACCESS`) |

Choix de conception :

- **Une seule session** : le client reçoit le `ZltRouterClient` existant.
- **Allowlist** : `ZltRouterClient.readOnly(cmd)` refuse tout `cmd` hors
  `{23, 30, 223, 224, 225}` **avant** de construire la requête.
- **Aucun paramètre `cmd`/`method`/`token`** dans l'API publique.
- **Lecture honnête** : un champ absent ou incompréhensible devient `null`
  ou `UNRECOGNIZED` + anomalie ; il n'est jamais remplacé par une valeur
  plausible. Une MAC invalide est conservée telle quelle et signalée.
- **Aucune troncature** : 33 règles sont rendues et signalées, pas coupées.
- **Aucune écriture** : ni `POST 23`, ni `POST 30`, ni `cmd 20`, ni
  `cmd 233`.

Le diagnostic CLI `--firewall-filter` imprime uniquement des compteurs, les
clés observées et les anomalies. **Aucune MAC, aucune remarque, aucune IP.**

---

## 6. Tests effectués et résultats

> **Chiffres de la mission 05** (état historique). L'état courant est en §13.

| Suite | Tests | Résultat |
|---|---|---|
| `router-core` | **131** (dont 38 nouveaux pare-feu) | ✅ 0 échec |
| `poc-cli` | **24** (dont 5 rendu + 3 allowlist CLI) | ✅ 0 échec |
| `app` (Android) | **26** | ✅ 0 échec |
| Total | **181** | ✅ |

Couverture du pare-feu :

- forme exacte de `GET 23` / `GET 30` (session, pas de `token`, pas de
  `datas`, pas de `subcmd`) ;
- liste vide, `datas` absent, `datas` nul, `datas` non tableau ;
- une règle, plusieurs règles, ordre conservé, champs inconnus conservés ;
- `enableRule` / `enableLink` en booléen, `"0"`/`"1"`, `0`/`1` ;
- `enableLink` illisible → `null`, jamais deviné ;
- `ippro` minuscule accepté, inconnu → `UNRECOGNIZED` ;
- MAC invalide conservée et signalée, MAC minuscule sans séparateur
  normalisée ;
- doublons conservés, entrée non-objet ignorée sans masquer les autres ;
- 32 règles = limite atteinte, 33 = dépassement signalé sans troncature ;
- `LIMITED_ACCESS` → `RouterFeatureUnavailableException` ;
- `success: false`, JSON malformé, panne réseau (un seul réessai),
  expiration de session (une seule reconnexion, une seule relecture) ;
- **frontière lecture seule** : transport tripwire, handlers pièges,
  multiset exact des requêtes, rejet de `readOnly(20)` et `readOnly(233)`,
  méta-test prouvant que le tripwire échoue bien sur une écriture injectée.

Test de bout en bout contre `tools/fake_router.py` :

```text
=== Pare-feu MAC (cmd 23 / 30, lecture seule) ===
  règles lues              2
  clés de la réponse       cmd, datas, success
  ...
  anomalies                (aucune)
```

Aucune requête d'écriture n'a été émise.

---

## 7. Limitations du firmware

1. **Disponibilité inconnue.** `GET 23` peut renvoyer `LIMITED_ACCESS`,
   exactement comme `cmd 278`. Le client le signale proprement ; il ne
   contourne rien.
2. **Forme de réponse non observée.** `datas` est le nom le plus probable,
   pas une certitude.
3. **Sémantique liste noire / blanche déduite**, pas observée.
4. **Portée réseau inconnue.** On ne sait pas si une règle coupe Internet,
   le LAN, l'administration, ou les trois. Aucune source ne prouve le
   contraire.
5. **`cmd 20` est global.** Il peut appliquer d'autres configurations en
   attente.
6. **Pas de suppression unitaire.** `cmd 23` réécrit la liste complète.
7. **Pas de champ de priorité explicite.** L'ordre fait foi.
8. **IPv6 non prouvé.** `ippro = "IPV4V6"` n'est pas observé.
9. **Randomisation MAC.** Aucune source ne traite le cas d'un appareil qui
   change de MAC ; une règle par MAC est contournable par l'appareil
   lui-même.

---

## 8. Risques et conditions de sécurité

### 8.1 Risques principaux

| # | Risque | Effet |
|---|---|---|
| R1 | Règle sur la MAC de l'appareil qui administre | Perte de l'interface d'admin |
| R2 | Bascule `cmd 30` en liste blanche | Tous les appareils non listés perdent l'accès, immédiatement |
| R3 | `POST 23` avec une liste périmée ou vide | Écrasement des règles existantes |
| R4 | Coupure entre `POST 23` et `POST 20` | Règles stockées non appliquées, activables plus tard |
| R5 | `cmd 20` global | Application d'autres configurations en attente |
| R6 | Fuite de données | MAC, remarques, IP dans les logs |
| R7 | Confusion avec `cmd 278` | Mauvais mécanisme, mauvais mode |

### 8.2 Conditions cumulatives avant toute écriture réelle — G0 à G11

| # | Condition |
|---|---|
| **G0** | `GET 23`, `28`, `30` répétés à quelques minutes d'intervalle : contenu identique, `datas` présent, aucune `LIMITED_ACCESS` |
| **G1** | Recoupement champ par champ avec la page « Filtrage MAC » de l'interface web |
| **G2** | Sauvegarde brute complète `23`/`28`/`30` (+ `278`), horodatée, checksummée, hors dépôt, chiffrée ; restauration déjà répétée contre le faux routeur |
| **G3** | Aucune règle préexistante sur la MAC de test, aucun doublon ; refus si l'état a changé entre lecture et écriture |
| **G4** | Appareil cible strictement secondaire, distinct de tout appareil dont dépend l'administration |
| **G5** | Chemin de secours **prouvé** : port Ethernet testé, ou second SSID, ou réinitialisation dont le coût est accepté |
| **G6** | Périmètre de `cmd 20` élucidé ; toutes les autres sessions d'admin fermées |
| **G7** | Première écriture **neutre** : rejouer exactement la liste lue, puis relire et comparer |
| **G8** | Une règle, un appareil, un mode : `deny` uniquement, jamais `allow`, jamais `cmd 30`, jamais la MAC admin |
| **G9** | Séparation structurelle `POST 23` / `POST 20` dans l'API, avec relecture entre les deux et journal de l'état « écrit, non appliqué » |
| **G10** | Confirmation explicite par action : MAC, règle, mode, effet attendu, annulation. Pas de `--yes` |
| **G11** | Critère d'arrêt et observateur : fenêtre horaire, possibilité de couper l'alimentation, vérification depuis un **troisième** point de vue (Internet, une autre machine du LAN, la page d'admin) |

Tant que G0–G11 ne sont pas satisfaites, **aucune écriture**.

---

## 9. Ce que l'app Android ne fait pas

- Aucun bouton « Bloquer » / « Débloquer ».
- Aucun import de `dev.wifiadmin.router.firewall` depuis `app`.
- Aucune modification de `RouterAdapter`, `ZltRouterAdapter`,
  `RouterRepository` ni d'un écran.

L'application continue de présenter le filtrage MAC comme indisponible. Un
test d'architecture (`ArchitectureBoundaryTest`) le garantit : l'app n'importe
jamais `dev.wifiadmin.router.firewall` ni `dev.wifiadmin.router.wifi`, et
`RouterAdapter` n'expose aucune opération mutante.

---

## 10. Recommandation (historique — remplacée par §13ter)

> **Cette section est conservée pour l'historique.** Elle recommandait de
> poursuivre l'étude. L'observation du 30/10/2026 a tranché : **abandonner**
> l'intégration du pare-feu sur ce firmware.

- La lecture seule était justifiée : sans effet, réversible par nature, seul
  moyen de lever les incertitudes. Elle a été faite.
- L'écriture ne l'a jamais été : trois inconnues majeures subsistaient
  (disponibilité, portée réelle, préservation de l'accès admin). Elles n'ont
  pas été levées, et **ne le seront pas** : sans lecture fiable, il n'existe
  aucun moyen de vérifier l'effet d'une écriture.
- L'issue anticipée s'est réalisée : le firmware **ne sert pas** la commande
  à ce compte, comme pour `cmd 278`. Conclusion : **abandon**.

---

## 12. Mission 05 — corrections apportées

### 12.1 Le bug de présentation

**Avant :** le diagnostic affichait

```text
règles lues        0
détail             (aucune règle)
anomalies          DATAS_MISSING=1
```

« Aucune règle » est une **affirmation fausse** sur l'état du routeur. En
mode liste blanche, « aucune règle » signifie « **personne n'est
autorisé** » : l'inverse de ce que l'utilisateur croyait.

**Cause :** `read()` ne testait que `success`. La présence de la charge
utile n'était jamais vérifiée, et le parseur transformait l'absence de
`datas` en `emptyList()`.

**Après :** quatre états explicitement distincts.

| État | Signification | Affichage |
|---|---|---|
| `DATA_RECEIVED` | `datas` présent et tableau | `DONNÉES REÇUES` + nombre de règles |
| `RESPONSE_INVALID` | `datas` absent, `null` ou non-tableau | `RÉPONSE INVALIDE (datas absent)` |
| `FEATURE_UNAVAILABLE` | refus firmware | `FONCTION INDISPONIBLE` |
| `UNKNOWN` | réseau, session, protocole | `INDÉTERMINÉ (…)` |

**Invariant :** un nombre de règles n'est affiché **que** dans
`DATA_RECEIVED`. `datas: null` et `datas: []` sont désormais
**distingués** (le test qui affirmait la confusion a été inversé).

### 12.2 Le bug `278`

Le diagnostic affichait « Commandes envoyées : … 278 … » alors que `278`
n'était **jamais** envoyé sans `--wifi-filter`. La liste était une
**constante de commandes possibles**, étiquetée comme un **historique**.

**Correction :** un journal réel, au niveau du transport
(`CommandJournal` + `RecordingTransport`). Il ne conserve que `cmd` et
`method` — **jamais** le corps de la requête, qui porte le hash du mot de
passe, le jeton de défi et le `sessionId`.

Vérifié hors ligne contre le faux routeur :

| Exécution | Commandes réellement envoyées |
|---|---|
| `--format diagnostic` | `232, 100, 223, 224, 225` |
| `--format diagnostic --firewall-filter` | `232, 100, 223, 224, 225, 23, 30` |
| `--format diagnostic --wifi-filter` | `232, 100, 223, 224, 225, 278` |

Le module Wi-Fi et le module pare-feu **ne s'activent pas mutuellement** :
`--wifi-filter` n'interroge que `278`, `--firewall-filter` que `23` et `30`.

### 12.3 Les autres corrections

| # | Correction | Emplacement |
|---|---|---|
| 1 | `LIMITED_ACCESS` traité comme un refus **même quand `success: true`** — bug actif : le refus firmware était ignoré et la réponsePresented comme une liste vide. | `ZltRouterClient.read()` |
| 2 | Un champ `error` **explicitement nul** n'est plus une erreur. | `ZltRouterClient.isSuccess()` |
| 3 | **Liste blanche stricte** pour `message` et `error` : seule une valeur enumérée est publiée, tout le reste devient `<message>`. L'ancien `redact()` était une liste noire : un routeur hostile pouvait sortir un mot de passe en clair ou un base64. | `RouterMessageSanitizer` |
| 4 | **Enveloppe d'observation** : `success` (valeur + présence), catégorie de `message`, présence et **type** de `datas`, présence d'erreur, clés de niveau supérieur, `cmd` renvoyé. Aucun contenu brut conservé. | `RouterReplyObservation` |
| 5 | Les **noms de clés** affichés passent par une liste blanche : un nom de clé peut être nommé d'après un secret. | `FirewallFilterRenderer.safeKeys()` |
| 6 | Le rendu d'erreur ne reproduit plus le message de l'exception. | `FirewallFilterRenderer.describeError()` |
| 7 | Une lecture qui échoue **n'annule plus** l'autre : `readRules()` et `readDefaults()` sont indépendants. | `printFirewallFilter()` |
| 8 | Le diagnostic est rendu **après** les sections optionnelles, pour que la liste des commandes soit complète. | `Main.kt` |
| 9 | Test-frontière **exhaustif** : les 401 numéros de commande hors allowlist sont rejetés, sans qu'aucune requête ne soit construite. | `FirewallMacFilterClientTest` |
| 10 | Le faux routeur refuse `POST 23`, `POST 30`, `cmd 20` **et compte** les tentatives d'écriture ; un endpoint `__stats` les expose. | `tools/fake_router.py` |

### 12.4 Tests

> **Chiffres de la mission 06** (état historique). L'état courant est en §13.

| Suite | Total |
|---|---|
| `router-core` | **176** |
| `poc-cli` | **56** |
| `app` | **28** |
| **Total** | **260** |

Scénarios de bout en bout hors ligne (`tools/fake_router_scenarios.py`, faux
routeur local, aucun contact avec le routeur réel) :

| Scénario | État attendu | Résultat |
|---|---|---|
| `fw-no-datas` (la réponse réelle) | `RÉPONSE INVALIDE` | ✅ |
| `fw-null-datas` | `RÉPONSE INVALIDE` | ✅ |
| `fw-empty-datas` | `DONNÉES REÇUES` (liste vide **confirmée**) | ✅ |
| `fw-datas-object` | `RÉPONSE INVALIDE` | ✅ |
| `fw-limited-access` | `INDISPONIBLE` | ✅ |
| `fw-limited-access-success-true` | `INDISPONIBLE` | ✅ |
| `fw-unknown-message` | `INDÉTERMINÉ` (message non publié) | ✅ |
| `fw-no-success-flag` | `INDÉTERMINÉ` | ✅ |

**Aucune tentative d'écriture n'a été enregistrée** par le faux routeur sur
l'ensemble des scénarios.

---

## 13. Mission 06 — validation de l'interopérabilité

### 15.1 Ce que la mission 05 avait laissé ouvert

La lecture réelle (avant l'ajout de `language`) avait répondu
`{cmd, message, success}` pour `GET 23` et `GET 30`. Deux questions restaient :

1. `language` était-il la cause ?
2. sinon, quelle est la forme de requête que ce firmware attend ?

### 15.2 Contrôle de conformité (P1, JS original)

Les **JS originaux du routeur** ont été retrouvés en cache local et vérifiés
(`main.js`, `login.js`, `helper.js`). L'enveloppe produite par notre client
est **conforme** à `Page.postJSON` :

| Champ | Routeur (`main.js`) | Notre client | Verdict |
|---|---|---|---|
| `cmd` | fourni par la page | 23 / 30 / 28 | ✅ |
| `method` | `GET` par défaut (`:1789-1791`) | `GET` | ✅ |
| `sessionId` | injecté (`:1803-1805`) | session courante | ✅ |
| `language` | **toujours** injecté (`:1800-1802`) | `fr` | ✅ *(ajouté en mission 05)* |
| `token` | **uniquement** pour `POST` (`:1792-1796`) | absent | ✅ |
| `subcmd` | seulement si la page en demande | absent | ✅ |
| `datas` | charge utile d'écriture | absent | ✅ |

**Réserve sur la valeur :** `Page.language = DOC.language || "CN"`
(`main.js:1319`). `CN` est un **défaut**, pas la langue effective. `fr` est
dans la liste interne (`:1352-1354`) et l'interface observée était en
français — mais la casse n'est pas prouvée.

**Vérification critique — le hash n'est pas affecté :** `login.js:238` calcule
`passwd: sha256_digest(token + passwd)`, et `postJSON` ajoute `language`
**après** (`:1800`), juste avant la sérialisation (`:1818`). Notre client
calcule `sha256Hex(token + settings.password)` (`:80`) **hors** du payload.
L'authentification n'est pas perturbée — un test le vérifie explicitement.

### 15.3 Les défauts corrigés par les audits

Les sous-agents ont trouvé des défauts **réels**, qui rendaient l'instrument
de preuve inutilisable. Tous sont corrigés, avec tests d'abord (RED observé
à chaque fois).

| # | Défaut | Impact | Correction |
|---|---|---|---|
| **B1** | Les noms de clés de la réponse étaient affichés **bruts**. Un routeur peut nommer un champ d'après un secret, ou y mettre un **saut de ligne** et **forger une ligne du rapport**. | Un rapport falsifiable n'est pas une preuve. | `SafeKeyNames` : liste blanche exacte, **troncature** au préfixe (`macadmin_password` → `mac…`), suppression des caractères de contrôle. Partagé par les deux renderers. Vérifié sur 65 536 codepoints : aucun saut de ligne, aucun caractère bidirectionnel ou zero-width ne passe. |
| **B2** | `cmd 223/224/225` sans charge utile → **« 0 appareil »** ; `cmd 278` sans `datas` → **« filtre désactivé »**. | La même confusion que la mission 05, sur deux autres chemins. | `DeviceSnapshot.payloadAbsent` ; `WifiMacFilterState.mode` devient **nullable** (`null` = inconnu) ; le rendu affiche `INCONNU` ou `inconnu (le routeur n'a rien renvoyé)`. L'écriture d'un état inconnu est **refusée**. **Propagé jusqu'à l'app Android** (autorisation explicite de l'utilisateur). |
| **B3** | Le `message` brut du routeur entrait dans une exception, puis était **affiché** par le CLI. Trois chemins : `message`, `macfilter`, `wifiStatus`. | Fuite de contenu arbitraire sur stdout. | `WifiFilterRenderer` prend un **type fermé** (`WifiFilterError`) ; `describeReply` ne reproduit plus le message ; `UnknownMode` ne porte plus la valeur brute ; `wifiStatus` n'est plus interpolé. |
| **O-1** | **OkHttp suivait les redirections.** Un `307` rejouait le corps du `POST 100` — **hash du mot de passe et `sessionId`** — vers l'hôte désigné par la réponse. | **Seule fuite de secret exploitable restante.** Un routeur compromis, ou un attaquant sur le LAN, récupérait un hash rejouable. | `followRedirects(false)` et `followSslRedirects(false)`. Test de loopback : un `307` n'est **pas** suivi et le corps **n'est pas rejoué**. |

> **O-1 est la correction la plus importante de la mission.** Sans elle, un
> rapport pouvait fuiter le mot de passe administrateur — hashé mais
> directement rejouable.

### 15.4 Les autres correctifs

| # | Correction |
|---|---|
| `language` envoyé par le client **Wi-Fi** aussi (il construisait ses propres payloads) |
| `cmd 28` ajouté à l'allowlist **déclarée** du CLI, qui ne le contenait pas alors que le POC pouvait l'envoyer |
| Handler `cmd 28` dans le faux routeur + scénario de bout en bout (la variante n'était **testée nulle part**) |
| Tripwire : assertions `POST 28` et `POST 30` absentes, ajoutées |
| Le journal affiche maintenant **les multiplicités** : le double login du client Wi-Fi est visible (`232 ×2, 100 ×2`) au lieu d'être masqué par un `distinct()` |
| `WifiMacFilterState.payloadPresent` : champ redondant (`mode == null` suffit) — conservé, sans effet |


### 15.4 Les variantes de lecture

> **Piste fermée le 30/10/2026.** L'interface officielle reçoit la même
> réponse creuse : aucune variante n'est la condition manquante. Les variantes
> restent dans le code, **gelées** (§13ter.5). La variante `getfun` envoie
> désormais la chaîne `"true"` (correction de la mission 07, §13bis.2).

Trois formes sont implémentées, chacune changeant **une seule chose** :

| Variante | Commande | Ce qui change |
|---|---|---|
| `default` | `GET 23` + `GET 30` | rien — la forme d'origine |
| `getfun` | `GET 23` + `GET 30` | ajoute `getfun: true` à `GET 23` **seulement** |
| `cmd28` | `GET 23` + `GET 28` | remplace `GET 30` par `GET 28` |

Vérifié sur le câble (faux routeur journalisant les corps) :

```text
variante default : CMD 23 keys=['cmd','language','method','sessionId']
variante getfun  : CMD 23 keys=['cmd','getfun','language','method','sessionId']  getfun=True
                   CMD 30 keys=['cmd','language','method','sessionId']
variante cmd28   : CMD 30 remplacé par CMD 28
```

`getfun` reste **opt-in** : le défaut ne change pas, pour qu'un résultat soit
attribuable à un seul changement.

### 15.5 Grille de décision

| `success` | `message` (catégorie) | `datas` | Conclusion | Action autorisée |
|---|---|---|---|---|
| `true` | absent / confirmation | tableau (0 ou n) | **Données reçues** | lire l'état, ne rien écrire |
| `true` | absent / confirmation | **absent** | **Réponse invalide** | tester une variante, une par une |
| `true` | absent / confirmation | `null` | **Réponse invalide** | idem |
| `true` | refus du firmware | — | **Fonction indisponible** | arrêter le pare-feu |
| `false` | session expirée | — | **Session** | le client relogue et rejoue une fois |
| `false` | message inconnu | — | **Indéterminé** | aucune écriture |
| absent | — | — | **Indéterminé** | aucune écriture |

### 15.6 Chrome DevTools — procédure strictement en lecture seule
Si les variantes échouent toutes, une capture du trafic de l'interface web
officielle permettrait de voir la requête exacte. **Le protocole est dangereux par
défaut** : un `.har` contient le corps de `cmd 100` (nom d'utilisateur +
hash SHA-256 du mot de passe), le `sessionId`, le `token` de défi et le
cookie de session. Le hash est **directement rejouable**.

La procédure sûre, dans cet ordre :

1. **S'authentifier avant d'ouvrir DevTools** — le trafic de `cmd 232` /
   `cmd 100` aura déjà eu lieu et ne sera pas capturé.
2. DevTools → Network → filtre **Fetch/XHR** + `cgi-bin/http.cgi` ;
   `Disable cache` **activé** (sinon la requête ne part pas) ;
   `Preserve log` **désactivé**.
3. `Clear` juste avant l'action, puis **une seule** action de lecture.
4. **Ne pas exporter de HAR.** Copier manuellement le strict nécessaire :
   `cmd`, `method`, la **liste des noms de clés**, la **forme** des valeurs.
5. Si un HAR est indispensable : l'exporter **hors du dépôt**, puis
   **supprimer** les entrées `cmd 100` / `232` / `233`, l'en-tête `Cookie` et
   `Set-Cookie`, remplacer `sessionId` par `<session>`, `token` par
   `<token>`, supprimer `passwd` et `username`. Vérifier par
   `grep -iE "passwd|sessionId|token|cookie|password"`, puis **détruire** le
   brut.
6. Ne pas naviguer dans d'autres pages pendant la capture (elles contiennent
   la configuration Wi-Fi et les comptes).

> **Recommandation** : le POC en mode `diagnostic` donne déjà `success`, la
> catégorie de `message`, la présence et le type de `datas`, les noms de
> clés **assainis** et les commandes réellement envoyées. Le HAR n'apporte
> que le **corps brut** — pour un risque disproportionné.

---

## 13bis. Mission 07 — état courant et recommandation finale

### 13bis.1 Tests

| Suite | Tests | Échecs |
|---|---|---|
| `router-core` | **183** | 0 |
| `poc-cli` | **56** | 0 |
| `app` | **28** | 0 |
| **Total** | **267** | **0** |

Scénarios de bout en bout hors ligne : **10/10** conformes, **aucune**
tentative d'écriture.

### 13bis.2 Corrections apportées en mission 07

| # | Correction | Justification |
|---|---|---|
| 1 | La variante `getfun` envoie **`getfun: "true"`** (chaîne) au lieu d'un booléen JSON. | Ce firmware encode **tous** ses drapeaux en chaîne (`acceptAll: "true"`, `isAutoUpgrade: "0"`, `subcmd: '1'`). Un booléen aurait été ignoré. Vérifié sur le câble. |
| 2 | Le faux routeur reproduit désormais la réponse **réelle** : `message` **vide** (il simulait `"success"`). | Une fixture qui prétend reproduire la réponse réelle mais s'en écarte ne prouve rien. |
| 3 | Nouveau scénario `fw-msg-empty-datas` : `message: ""` **avec** `datas: []` → `VIDE (confirmée)`. | Prouve que le message vide ne dégrade pas la distinction liste vide / absence. |
| 4 | Tests unitaires pour `message` **vide**, **null** et **absent**. | Le code les distinguait déjà ; aucun test ne le prouvait avec la forme exacte de la réponse réelle. |
| 5 | `tools/anonymise_observation.py` : anonymisation **locale** des observations DevTools. | Projection structurelle : seuls `cmd`, `method`, les **noms** de clés et le **type** de `datas` survivent. Filet final : refus de produire un rapport contenant une valeur qui ressemble à un secret. |

### 13bis.3 Recommandation finale

> **Le filtrage MAC du pare-feu n'est pas exploitable sur ce firmware, et
> l'enquête a atteint sa limite utile.**

**Ce qui est établi, solidement :**

1. Le routeur **accepte** `GET 23` et `GET 30` (`success: true`) mais ne
   livre **aucune donnée** — ce n'est **ni** un refus (`LIMITED_ACCESS`),
   **ni** une session expirée, **ni** une liste vide.
2. Les commandes qui **fonctionnent** (`223`, `224`, `225`) ne portent
   **jamais** de champ `message` ; `23` et `30` portent un `message`
   **vide**. Elles empruntent donc un **chemin de code différent**.
3. L'interface officielle **planterait** sur une réponse sans `datas`
   (`data.datas.length` sans garde-fou) : elle **ne** la considère pas comme
   une liste vide, exactement comme notre client.
4. Notre enveloppe de requête est **conforme** à `Page.postJSON` pour un
   `GET` : les sept champs, le chemin, le `Content-Type` et le verbe HTTP
   sont identiques. Une seule divergence subsiste : `getfun`.
5. La lecture seule est **prouvée par construction** : verrou `internal`,
   allowlist, méthode figée à `GET`, tripwire, test exhaustif 0–400,
   `write_attempts` vide sur 10 scénarios.

**Ce qui restait indéterminé à ce stade — et qui est désormais tranché :**

- pourquoi le firmware ne livre rien : **le handler ne sert pas la commande à
  ce compte** (l'interface officielle échoue de la même façon) ;
- `getfun` : **ce n'est pas la condition manquante** — l'interface officielle
  l'envoie et reçoit la même réponse creuse ;
- handler câblé mais désactivé, ou pas câblé du tout : la distinction est
  **sans conséquence pratique** — dans les deux cas, aucune donnée n'est
  disponible.

**Recommandation (remplacée par §13ter) :** l'observation de l'interface
officielle (`DEVTOOLS_PROCEDURE.md`) a été **exécutée le 30/10/2026**. Elle a
montré que l'interface reçoit la **même** réponse creuse. La conséquence
prévue s'applique : **abandonner** l'intégration du pare-feu, comme pour
`cmd 278`.

Multiplier les variantes de payload (`language` en 4 valeurs, `getfun` en
booléen et en chaîne, `cmd 28`, ajout d'en-têtes, réordonnancement des
clés JSON) **n'est pas justifié** : leur valeur informative est
conditionnelle, et l'observation de l'interface officielle répond à la même
question **en une fois**, sans écrire.

**Ce qui n'a pas été fait, et pourquoi :** aucune écriture n'a été tentée,
aucune variante n'a été envoyée au routeur réel pendant cette mission, et
aucun bouton « Bloquer / Débloquer » n'a été introduit.

---

## 13ter. Clôture de l'investigation (30/10/2026)

### 13ter.1 Verdict

> **Capacité `UNAVAILABLE` sur le firmware 6.07.0 d'Airtel Niger.**

Ce n'est **pas** `UNVERIFIED`. Le doute qui justifiait de poursuivre — « notre
requête est-elle fautive ? » — est levé : **deux clients indépendants**
obtiennent la même réponse.

### 13ter.2 L'observation décisive

L'utilisateur a observé la requête émise par l'**interface web officielle**
du routeur, dans Chrome DevTools :

*Pare-Feu → Règles de filtrage → Filtrage MAC*

```json
{
  "success": true,
  "cmd": 23,
  "message": ""
}
```

- **Statut HTTP :** 200.
- **`datas` :** absent.
- **Confrontation :** identique, champ pour champ, à la réponse reçue par
  notre client Kotlin.

### 13ter.3 Ce que cela établit

| Fait | Preuve |
|---|---|
| Le firmware **exécute** la commande | `success: true`, HTTP 200, aucune erreur de dispatch |
| Le firmware **ne livre aucune donnée** | `datas` absent, `message` vide |
| Ce n'est **pas** un refus | un refus est `success: false` + `LIMITED_ACCESS` |
| Ce n'est **pas** une session invalide | une session expirée répond `NO_AUTH` / `LOGIN_TIMEOUT` |
| Ce n'est **pas** une liste vide | une liste vide confirmée est `datas: []` |
| Ce n'est **pas** notre requête | l'interface officielle, avec son propre code, reçoit la même réponse |
| `getfun` n'est **pas** la condition manquante | l'interface officielle envoie son propre drapeau et échoue pareil |
| `cmd 28` n'est **pas** une piste | le handler qui ne sert pas `23`/`30` ne servira pas davantage `28` |

**Interprétation :** le handler de `cmd 23` / `cmd 30` n'est **pas câblé** sur
ce build, ou il est **neutralisé pour ce compte**. La distinction est sans
conséquence : dans les deux cas, aucune donnée n'est accessible en lecture, et
donc aucune écriture ne peut être vérifiée.

### 13ter.4 Inventaire des preuves

| Niveau | Preuve | Source |
|---|---|---|
| **P1** | `MAC_FILTER: 23`, `MAC_DEFAULT_FILTER: 30`, `APPLY_FILTER: 20` | `main.js:50-62` |
| **P1** | `Page.postJSON` envoie `language` toujours, `token` seulement en `POST` | `main.js:1792-1806` |
| **P1** | `message` n'est lu que sur la branche `success == false` | `main.js:1853-1893` |
| **P1** | `cmd 23` fait `data.datas.length` sans garde-fou | `macFilter.js` (transcription) |
| **P1** | `cmd 30` fait `data.datas[0]` sans garde-fou | `defaultMac.js` (transcription) |
| **P1** | `cmd 28` seul est protégé par `if (!!data.datas)` | `macFilter.js` (transcription) |
| **P1** | `cmd 278` teste `hasOwnProperty("datas")` | `wifiList24g.js` |
| **P1** | Réponse réelle `GET 23` : `success: true`, `message: ""`, `datas` absent | notre POC, 29–30/10/2026 |
| **P1** | Réponse réelle de l'interface officielle : **identique** | DevTools, 30/10/2026 |
| **P2** | `cmd 23` attend `getfun: true` | transcription `macFilter.js`, source non archivée |
| **P3** | Le filtre couperait Internet en conservant le LAN | jamais observé |
| **P3** | `enableLink = false` signifie « liste noire » | déduit, jamais observé |
| **P3** | `ippro = "IPV4V6"` couvre réellement IPv6 | jamais observé |

Les preuves P2 et P3 **n'ont plus d'usage** : sans lecture, aucune ne peut
être vérifiée. Elles restent consignées pour un futur firmware.

### 13ter.5 Décisions

| Objet | Décision |
|---|---|
| Code d'analyse (`router-core/.../firewall/`) | **Conservé**, avec ses tests, comme archive. Jamais appelé par l'app. |
| Diagnostic CLI (`--firewall-filter`, `--firewall-variant`) | **Conservé** mais **gelé** : plus aucune exécution contre un routeur sans preuve nouvelle. |
| Fonctionnalité Android | **Non exposée.** Aucun bouton, aucun écran, aucun import. |
| Écriture (`POST 23` / `POST 30` / `cmd 20`) | **Interdite.** Aucune n'a jamais été tentée. |
| Nouvelles variantes (`getfun`, `cmd 28`, en-têtes, `language`) | **Interdites** sans preuve nouvelle. |

### 13ter.6 Ce qui rouvrirait le dossier

Uniquement un **fait nouveau**, pas une hypothèse :

1. un **autre firmware** sur le même modèle, dont l'interface officielle
   renvoie un `datas` non vide ;
2. un **autre opérateur** ou un **autre compte** dont l'interface officielle
   reçoit des données ;
3. une **capture réseau** de l'interface officielle montrant une requête
   différente de la nôtre (méthode, chemin ou paramètre supplémentaire).

Sans l'un de ces trois faits, toute nouvelle tentative est une répétition du
même échec.

---

## 14. Clôture — plus aucune action routeur

**Il n'y a plus de « prochaine action » sur le routeur.**

L'observation de l'interface officielle a été faite ; elle a tranché. Aucune
commande de ce document ne doit être rejouée contre le routeur réel :

- ni `--firewall-filter`, ni `--firewall-variant getfun`, ni `cmd 28` ;
- ni aucune écriture (`POST 23` / `POST 30` / `cmd 20`) ;
- ni aucune modification de configuration.

Les commandes qui subsistent dans les sections historiques (§14 d'origine,
§16) sont conservées comme **traces de méthode**, pas comme instructions.

> **Rappel sécurité, pour toute exécution future du diagnostic :**
> `--format diagnostic` uniquement (jamais `console`, qui affiche MAC, IP et
> noms d'hôtes) ; une seule tentative de mot de passe (le gel du routeur dure
> 180 s).

---

## 15. Faisabilité réelle du filtrage pare-feu

**Verdict : `UNAVAILABLE` — le filtre n'est pas utilisable, et l'étude est
close.**

- La **lecture** est techniquement possible et sans risque, mais ce firmware
  ne livre aucune donnée pour `23` et `30`. L'interface officielle non plus :
  ce n'est donc pas un défaut de notre client.
- L'**écriture** est **interdite** : rien ne prouve que `POST 23` / `POST 30`
  seraient acceptés, rien ne prouve ce que le filtre coupe, et surtout aucune
  lecture ne permettrait de **vérifier** l'effet d'une écriture.
- **Aucune interface** n'expose ce filtrage : l'application affiche
  « Filtrage MAC indisponible ».

La question de la faisabilité n'est plus une question d'interopérabilité : la
réponse est **non**. Le firmware exécute la commande sans livrer de données, et
les trois pistes envisagées (`language`, `getfun`, `cmd 28`) sont **fermées**
par l'observation du 30/10/2026 (§13ter).

**Ce qui a été appris et reste vrai :**

1. Un `success: true` **ne signifie pas** « données disponibles » : il peut
   n'être qu'un accusé de dispatch.
2. Une réponse **sans `datas`** n'est **jamais** une liste vide — ni pour nous,
   ni pour l'interface officielle (qui planterait).
3. La **lecture seule** peut être prouvée par construction (allowlist,
   méthode figée, journal réel) même quand la fonctionnalité est absente.

---

## 15bis. Défauts connus restants

La revue indépendante a clos les défauts bloquants. Voici ce qui reste
**ouvert**, avec la décision prise :

| # | Gravité | Défaut | Décision |
|---|---|---|---|
| **O-2** | Moyen | **Double login** : le client Wi-Fi a sa propre session → 2× `cmd 100`, hash transmis deux fois, **2 pénalités de 180 s** si le mot de passe est faux. | **Documenté**, pas corrigé : il demanderait de refactorer le client Wi-Fi, hors périmètre de la mission 06. Le diagnostic affiche désormais les multiplicités, donc le doublon est **visible**. |
| **O-3** | Moyen | **Rejeu aveugle d'une écriture** après expiration de session dans `WifiMacFilterClient.send` : un `POST 278` peut être appliqué deux fois. | **Documenté**, pas corrigé : le chemin d'écriture est inaccessible depuis le POC et l'app. À corriger avant toute activation d'écriture Wi-Fi. |
| **O-4** | Faible | ~~`ZltRouterClient.redact()` est du **code mort**~~ | **Corrigé en mission 07** : la fonction morte, la constante `HEX_TOKEN_PATTERN` et le champ `challengeToken` qui n'existait que pour elle ont été **supprimés**. La défense repose sur `RouterMessageSanitizer`, testée. |
| **O-10** | Faible | `payloadAbsent` est un drapeau **global** : si une seule des trois sources manque, le diagnostic ne le dit pas. | Accepté pour la mission 06 : le cas « aucune source » (le pire) est traité. Un indicateur par source serait plus précis. |
| — | Faible | Le rapport du pare-feu dégrade certains noms de clés (`datas.mac….ippro` au lieu de `datas.maclist[].ippro`) : l'information est conservée mais la forme est ambiguë. | Accepté : c'est un défaut de **lisibilité**, pas de sécurité. |

---

## 16. Historique — proposition de la mission 04

> **Section historique. Ne plus exécuter.** Les commandes ci-dessous ont été
> conservées comme trace de méthode ; elles ne sont plus des instructions.
> L'investigation est close (§13ter).

La lecture réelle de la mission 04 a été exécutée. La mission 05 a été
conduite **hors ligne**, sans nouvelle communication avec le routeur.

**Proposition qui avait été faite — une lecture, trois variantes :**

```bash
cd wifi-admin-app
./gradlew :poc-cli:installDist
./poc-cli/build/install/poc-cli/bin/poc-cli \
  --host <passerelle> \
  --user <utilisateur> \
  --format diagnostic \
  --firewall-filter \
  --yes
```

Elle devait être rejouée après ajout de `language`, puis avec
`getfun: "true"`, puis sur `cmd 28` — une variante à la fois. **Cela n'a plus
lieu d'être** : l'interface officielle a répondu, et sa réponse ferme les
trois pistes.

> **Aucune de ces commandes ne doit être rejouée contre le routeur réel.**

