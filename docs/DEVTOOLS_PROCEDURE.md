# Procédure d'observation — interface web officielle du routeur

> **Objectif** : voir ce que l'interface officielle envoie **exactement** pour
> `cmd 23` et `cmd 30`, afin de comparer avec notre client Kotlin.
>
> **Strictement en lecture seule.** Ouvrir une page et lire des requêtes ne
> modifie rien sur le routeur. Ne cliquez sur **aucun** bouton d'écriture
> (« Appliquer », « Enregistrer », « Effacer », « Ajouter », « Supprimer »).

---

## 0. Pourquoi cette procédure

Nos lectures `GET 23` et `GET 30` reviennent avec `success: true` et **sans**
`datas`. Le JS officiel, lui, fait `data.datas.length` sur `cmd 23` et
`data.datas[0]` sur `cmd 30` **sans garde-fou** : il planterait. L'interface
ne peut donc pas nous montrer une réponse exploitable.

Ce qu'elle **peut** nous montrer, c'est la **requête** qu'elle envoie. C'est
la seule divergence connue entre notre client et le firmware.

---

## 1. Règle absolue — ne rien transmettre de brut

Un export HAR contient le corps du `cmd 100` : **nom d'utilisateur + hash
SHA-256 du mot de passe**, le `token` de défi, le `sessionId` et les cookies
de session. Le hash est **directement rejouable** : il suffit de connaître le
token de défi, que `cmd 232` renvoie en clair.

> **Ne jamais m'envoyer :** un `.har`, un `.json` de DevTools brut, une copie
> du panneau *Request Headers*, une capture d'écran du payload, un cookie,
> un `sessionId`, un `token`, un hash.

---

## 2. Préparation

1. **Authentifiez-vous AVANT d'ouvrir DevTools.**
   Ouvrez `http://192.168.1.1/` dans une fenêtre normale, connectez-vous,
   et **laissez la session ouverte**. Le trafic de `cmd 232` et `cmd 100`
   a alors déjà eu lieu : il ne sera **pas** capturé.

2. **Ne recargerez plus la page** ensuite. Si vous devez le faire, la
   session repart et le trafic d'authentification redevient capturable.

---

## 3. Capture

1. `F12` → onglet **Network**.
2. Cochez **Disable cache** (sinon la réponse peut venir du cache et la
   requête ne part pas — on ne verrait pas le trafic réel).
3. **Décochez** *Preserve log* (il accumule les requêtes des pages suivantes).
4. Filtre : **Fetch/XHR**, puisoprimez `cgi-bin/http.cgi` pour ne garder que
   le CGI.
5. Bouton **Clear** (vider le journal).
6. **Une seule** action : dans l'interface, ouvrez le menu **Pare-feu** puis
   l'onglet **Filtrage MAC**. Attendez le chargement.
7. **N'allez dans aucune autre page** pendant ce temps (elles contiennent la
   configuration Wi-Fi et les comptes).

> Si l'onglet **Filtrage MAC** n'existe pas sur votre routeur, notez-le :
> c'est une observation valuable en soi (le menu est masqué par un drapeau
> de niveau de compte).

---

## 4. Ce qu'il faut noter — 4 éléments, et rien d'autre

Pour **chaque** requête `cgi-bin/http.cgi` dont le `cmd` vaut `23`, `30`,
`28` ou `100` :

| # | Élément | Où le lire |
|---|---|---|
| **I1** | le nombre `cmd` | *Request Payload* |
| **I2** | la **liste des noms de clés** du payload (pas les valeurs) | *Request Payload*, en survolant la valeur |
| **I3** | le **type** de `datas` dans la réponse : absent / null / tableau / objet | *Response*, en survolant la valeur |
| **I4** | le **statut HTTP** | *Headers* → *Status Code* |

Ne relevez **jamais** la valeur d'un champ. Pas le `sessionId`, pas le
`token`, pas le `passwd`, pas le `username`.

---

## 5. Anonymisation locale

Transcrivez manuellement les 4 éléments ci-dessus dans un fichier JSON
**local**, hors du dépôt :

```json
{
  "requests": [
    {"cmd": 23, "method": "GET", "keys": ["cmd", "method", "sessionId", "language"]}
  ],
  "responses": [
    {"cmd": 23, "keys": ["cmd", "message", "success"], "datasType": "absent"}
  ]
}
```

Puis, **sur votre machine** :

```bash
python3 wifi-admin-app/tools/anonymise_observation.py observation.json > rapport.json
```

Le script :
- ne garde que `cmd` (entier), `method` (`GET`/`POST`/autre), les **noms**
  de clés filtrés, et le **type** de `datas` ;
- **jette** toute valeur, tout en-tête, tout cookie ;
- **refuse** de produire un rapport s'il détecte une valeur qui ressemble à
  un secret ou une longue chaîne hexadécimale.

### Contrôle de non-fuite — obligatoire

```bash
grep -inE 'passwd|password|sessionid|token|cookie|username|[0-9a-f]{32,}' rapport.json
```

→ **zéro résultat attendu.** C'est ce résultat, et non le fichier, que
vous me transmettez.

---

## 6. Ce que je saurai en recevoir

| Observation | Conclusion |
|---|---|
| L'UI envoie `getfun` et nous ne l'envoyons pas | Le drapeau est requis → variante `getfun` justifiée |
| L'UI envoie un `subcmd` pour `cmd 30` | Il manque un paramètre → variante à créer |
| L'UI n'envoie **rien** de plus que nous | La divergence n'est pas dans le payload → le problème est ailleurs (handler non câblé, ou niveau de compte) |
| L'UI reçoit `datas` et nous non | Le firmware **sait** répondre : notre requête diffère → comparer les deux |
| L'UI reçoit `datas` **vide** et nous non | Le firmware répond, mais pas à notre compte/niveau |
| L'UI reçoit la même réponse creuse que nous | Le firmware ne sert pas cette commande à ce compte → **arrêt de l'investigation** |

---

## 7. Alternative encore plus sûre

Si vous préférez **ne rien transmettre du tout** : comparez la forme
observée à celle de notre POC **localement**, et ne m'écrivez que le verdict,
en une phrase. Par exemple :

> « L'UI envoie `cmd`, `method`, `sessionId`, `language`, `getfun` pour le
> 23, et reçoit une réponse avec `datas`. »

Ou, dans le format attendu :

```json
{"requests":[{"cmd":23,"method":"GET","keys":["cmd","method","sessionId","language","getfun"]}],
 "responses":[{"cmd":23,"keys":["cmd","datas","success"],"datasType":"array"}]}
```

C'est **suffisant** pour trancher, et cela ne contient aucun secret.
