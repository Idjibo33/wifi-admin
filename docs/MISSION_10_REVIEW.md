# Mission 10 — Revue indépendante de sécurité

> Date : 06/11/2026 · Mission 10 · branche `feature/mission-10`
> Base revue : `22330f2` (livraison initiale de la Mission 10)
> Périmètre : code de la Mission 10, frontières des Missions 08 et 09,
> stockage local des métadonnées.
> Verdict : **corrections appliquées**, mission **ACCEPTÉE AVEC RÉSERVES**.

---

## 1. Méthode

La revue a combiné trois angles :

1. **Lecture des sources** de la Mission 10 : identité des appareils,
   validation des noms, stockage des métadonnées, tri et filtres,
   présentation.
2. **Sondes adversariales** exécutées hors ligne, sur la JVM uniquement :
   collision de contexte, purge jamais déclenchée, caractères Unicode
   invisibles, liste d'appareils vide, lecture réutilisée, tri numérique
   des adresses.
3. **Relecture des tests de frontière** des Missions 08 à 10 : un test de
   frontière qui ne peut pas échouer ne prouve rien.

**Aucun contact avec le routeur réel.** Les sondes utilisent
`FakeRouterAdapter` et des répertoires temporaires. Aucun mot de passe,
aucun jeton, aucune adresse MAC ou IP réelle n'apparaît dans ce document.

---

## 2. Constats

| # | Constat | Gravité | État |
|---|---|---|---|
| R1 | Le hash du contexte était ambigu : deux routeurs pouvaient partager un fichier | Moyenne | **Corrigé** |
| R2 | La purge des entrées expirées n'était jamais déclenchée en usage normal | Moyenne | **Corrigé** |
| R3 | La validation des noms laissait passer des caractères Unicode invisibles | Faible | **Corrigé** |
| R4 | Trois tests de frontière étaient trop faibles pour prouver ce qu'ils annonçaient | Faible | **Corrigé** |
| R5 | Le fichier de test contenait des caractères de contrôle littéraux (NUL, ESC), ce qui le faisait traiter comme binaire par Git | Faible | **Corrigé** |

### R1 — Collision du contexte par séparateur

Le nom du fichier était dérivé de `host|account|networkId`. Or
`networkId` contient **lui-même** des séparateurs (`wlan0|192.168.1.0/24|192.168.1.1`).
Deux contextes différents pouvaient donc produire la même chaîne :

```
host="a|b", account="c",  networkId=""   ->  a|b|c|
host="a",   account="b|c", networkId=""  ->  a|b|c|
```

Le correctif **préfixe chaque partie par sa longueur** avant le hash. La
séparation n'est plus ambiguë, même si une partie contient le séparateur.
Deux tests le vérifient :

- `the separator of the context cannot make two routers collide` ;
- `another network on the same router and account is isolated`.

> **Défense en profondeur.** Même si deux contextes tombaient sur le même
> FNV-1a 64 bits, la lecture compare le contexte **complet** stocké dans le
> document et rejette le fichier en cas de différence. Une collision
> provoquerait une perte de fichier, jamais un mélange de données entre
> deux routeurs.

### R2 — Purge jamais déclenchée

`purgeExpired()` existait et était testée, mais **aucun chemin normal ne
l'appelait**. En usage réel, les entrées expirées restaient indéfiniment :
la rétention de trente jours n'était vraie que si quelqu'un appelait la
méthode explicitement.

Le correctif applique la purge **au moment de l'écriture** de
`recordObservation`. Elle ne coûte ni lecture ni écriture supplémentaire,
et elle est déclenchée par l'application — jamais par le système. Le cas
« rien n'a changé mais une entrée a expiré » écrit le fichier ; le cas
« rien n'a changé et rien n'a expiré » reste un `Skipped(NO_CHANGE)`.

La purge retire aussi les entrées qui n'ont **ni nom ni observation** :
elles ne portent rien et ne doivent pas rester indéfiniment. Trois tests
couvrent ces branches :

- `the purge happens on a normal observation` ;
- `a repeated observation does not rewrite the file` ;
- `an entry with neither a name nor an observation is purged`.

### R3 — Caractères Unicode invisibles

La liste des caractères interdits couvrait les contrôles C0/C1, DEL et les
inversions bidirectionnelles, mais pas :

- `U+061C` (marque de lettre arabe) ;
- `U+200B` à `U+200D` (espaces de largeur nulle et liant) ;
- `U+2060` (word joiner) et `U+206A` à `U+206F` (format dépréciés) ;
- `U+FEFF` (BOM) ;
- `U+2028` et `U+2029` (séparateurs de ligne et de paragraphe) ;
- `U+202F` (espace fine insécable).

Ces caractères n'affichent rien ou cassent une ligne unique : ils
permettaient de masquer un nom ou de déformer la liste. Ils sont désormais
retirés au **stockage**, comme les autres. Le test
`the invisible formatting characters are removed` le vérifie.

### R4 — Tests de frontière trop faibles

| Test | Faiblesse | Correction |
|---|---|---|
| Aucune écriture via l'adaptateur | Liste **noire** de noms de méthodes : une méthode ajoutée plus tard passait inaperçue | Liste **blanche** des trois lectures (`connect`, `fetchDevices`, `disconnect`) ; toute autre méthode échoue |
| Les noms ne sont jamais journalisés | Analyse limitée aux fichiers de la fonctionnalité | Analyse de **toutes** les sources de l'application |
| Métadonnées dans `noBackupFilesDir` | Vérifiait la présence de la chaîne quelque part dans le fichier | Vérifie que le constructeur du store reçoit bien `noBackupFilesDir` **et** le sous-répertoire `device-metadata` |

### R5 — Fichier de test traité comme binaire

Le test des caractères de contrôle contenait un NUL et un ESC **littéraux**.
Git considérait le fichier comme binaire : la revue de diff était aveugle
sur ce fichier. Les valeurs sont désormais écrites en échappements Kotlin
(`\u0000`, `\u001B`, `\u202E`, `\u202D`), et le fichier redevient du texte.

---

## 3. Ce que la revue n'a pas trouvé

- **Aucune écriture vers le routeur** : le dépôt n'appelle que les trois
  lectures de l'adaptateur, et le `RouterAdapter` garde ses quatre membres.
- **Aucun secret dans le fichier de métadonnées** : la liste blanche de
  clés ne permet structurellement pas d'y écrire un mot de passe, un jeton
  ou un cookie.
- **Aucune fuite entre routeurs** : un fichier dont le contexte ne
  correspond pas exactement est rejeté et supprimé.
- **Aucune observation inventée** : le timestamp est celui du snapshot,
  jamais l'heure courante ; une lecture réutilisée ne fait pas avancer
  l'observation ; une réponse sans liste n'en crée aucune.
- **Aucune lecture déclenchée par le filtrage** : `setQuery`, `setFilter`
  et `setSort` ne touchent pas au dépôt.

---

## 4. Limites résiduelles

1. **Deux réseaux de même passerelle et de même compte** restent
   indiscernables sans BSSID, qui exigerait `ACCESS_FINE_LOCATION`. Le
   pire cas est un changement **non détecté**, jamais une expiration.
2. **Un appareil qui randomise sa MAC** est un appareil nouveau à chaque
   association : le nom personnalisé ne le suit pas.
3. **La purge** n'a lieu que lorsqu'une observation est enregistrée. Un
   téléphone jamais rouvert conserve ses données au-delà de trente jours :
   c'est le prix de l'absence de travail système.
4. **Le hash FNV-1a n'est pas cryptographique.** Il n'a pas à l'être : il
   sépare des contextes, et l'égalité exacte du contexte reste la garde
   finale.

---

## 5. Vérification

| Vérification | Résultat |
|---|---|
| Tests JVM (`router-core`, `poc-cli`, `app`) | **546 tests, 0 échec** |
| Dont `DeviceMetadataStoreTest` | 38 tests |
| Tests instrumentés (Pixel 7 Pro, Android 17) | 38 tests, 0 échec — exécutés avant les correctifs, qui sont couverts par les tests JVM |
| Contact avec le routeur réel | **Aucun** |
| Écritures vers le routeur | **Aucune** |
