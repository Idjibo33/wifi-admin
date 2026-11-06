# Mission 10 — Rapport d'identification et d'organisation des appareils WiFi Admin

> Date : 06/11/2026 · Mission 10 · branche `feature/mission-10`
> Base : `a38b32f` (Mission 09 close + CI)
> Verdict : **ACCEPTÉ AVEC RÉSERVES**

---

## 1. Ce que la mission demande, et ce qu'elle livre

| Fonctionnalité | Livrée | Preuve |
|---|---|---|
| **A. Noms personnalisés** | ✅ | `DeviceMetadataStoreTest` (38), `CustomNameViewModelTest` (10), `DevicesListInstrumentedTest` (14) |
| **B. Recherche, tri, filtres** | ✅ | `DeviceOrderingTest` (14), `DevicesListInstrumentedTest`, `DeviceListPreferencesInstrumentedTest` (4) |
| **C. Dernière apparition** | ✅ | `DeviceObservationTest` (7), `LastSeenPresentationTest` (10) |

Les trois fonctionnalités sont **entièrement locales** : le routeur n'en
sait rien et n'en saura jamais.

## 2. Les décisions de conception

### 2.1 L'identité d'un appareil : la MAC canonique, et rien d'autre

Le routeur n'expose **aucun** identifiant stable. Les champs qu'il
rapporte — MAC, IP, nom d'hôte, interface, expiration du bail — sont
tous instables.

La clé est donc la **MAC canonique** :

- la normalisation accepte `AABBCCDDEE01`, `aa-bb-cc-dd-ee-01` et
  `aabb.ccdd.ee01`, et produit toujours `aa:bb:cc:dd:ee:ff` ;
- une valeur qui n'est pas douze chiffres hexadécimaux ne produit
  **aucune** clé ;
- **l'IP n'est jamais une identité** — elle change à chaque bail et peut
  être réattribuée ;
- **le nom d'hôte n'est jamais une identité** — il est déclaré par
  l'appareil.

**Il n'y a aucune clé de repli.** C'est un choix assumé : une association
par IP attribuerait un nom **faux** à un autre appareil, ce qui est pire
que la perte du nom.

> **Limite assumée.** Un appareil qui randomise sa MAC à chaque
> association est un appareil **nouveau** à chaque fois. L'application ne
> prétend pas le reconnaître : elle crée une nouvelle entrée et ne fusionne
> jamais l'ancienne. Le nom personnalisé **ne suit pas** un tel appareil.

### 2.2 La dernière observation : le timestamp du routeur, pas l'heure courante

C'est le point le plus subtil de la mission, et le choix résout les trois
pièges d'un coup.

La date écrite est **`snapshot.capturedAtMillis`** — l'instant où le
routeur a **réellement** répondu — et **jamais** l'heure courante :

| Situation | `capturedAtMillis` | Effet sur l'observation |
|---|---|---|
| Vraie lecture | l'instant de la lecture | l'observation avance ✅ |
| Lecture **réutilisée** par le coordinateur | l'instant de la **première** lecture | l'observation **ne bouge pas** ✅ |
| Snapshot **restauré du disque** | — | le chemin ne passe pas par `performRead` : **aucune** observation ✅ |
| Réponse **sans liste** (`payloadAbsent`) | — | **aucune** observation ✅ |
| Réponse avec une liste **vide** | l'instant de la lecture | le device absent **garde** son observation ✅ |

> **Note de méthode.** Un sous-agent de revue a modifié cette ligne pour
> utiliser `clock.wallClockMillis()`, ce qui aurait fait avancer
> l'observation lors d'une lecture réutilisée — exactement l'interdit de la
> mission. La modification a été **annulée** et le mécanisme vérifié par
> `DeviceObservationTest`.

Le libellé ne prétend **jamais** connaître l'heure de déconnexion.
« Vu récemment » est une **borne**, pas un instant : l'application sait
qu'un appareil était présent à la dernière lecture, et rien de plus.

### 2.3 L'isolation : un fichier par routeur

Les métadonnées vivent dans `noBackupFilesDir/device-metadata/`, **un
fichier par routeur**, le nom étant dérivé d'un hash de
`(host, account, networkId)` — la même identité que le `SnapshotContext`.

L'isolation est donc **structurelle** : le fichier de l'autre routeur
n'existe pas dans le répertoire. Le contexte complet est **aussi** dans le
document, et la comparaison à la lecture est une égalité exacte.

> **Limite assumée.** Deux routeurs domestiques ont souvent la même
> passerelle et le même compte. Ce qui les distingue est l'identité du
> réseau — interface, plage, passerelle. Deux réseaux qui partagent les
> trois sont **considérés comme le même** : c'est la limite de la Mission
> 09, et le BSSID qui la lèverait exigerait `ACCESS_FINE_LOCATION`.

### 2.4 La validation des noms

Un nom est du texte affiché. Il n'est jamais interprété, mais il ne doit
pas pouvoir **tromper** :

- forme **NFC** : « café » et « café » sont le même nom ;
- **caractères de contrôle** (C0, C1, DEL) retirés ;
- **indicateurs bidirectionnels** (`U+202E` et voisins) retirés : un
  inverseur de sens ferait lire un nom à l'envers ;
- longueur **plafonnée** à 64 caractères ;
- un nom vide **supprime** le nom.

La normalisation est appliquée **au stockage**, pas à l'affichage.

### 2.5 Le tri et les filtres

Les règles sont des **fonctions pures**, testées sur la JVM.

- **Par nom** : le collationneur français.
- **Par adresse IP** : **numérique**, octet par octet. Un tri
  lexicographique placerait `192.168.1.100` avant `192.168.1.2`.
- **Par signal** : RSSI décroissant, et les appareils **sans** signal
  **en dernier** — mélanger un `null` revendiquerait un niveau qui
  n'existe pas, et placerait un filaire avant un Wi-Fi à −85 dBm.
- Chaque comparateur se termine par un **critère de rupture** stable :
  sans lui, la liste clignoterait à chaque rafraîchissement.

Les filtres sont les **bandes** : tous, 2,4 GHz, 5 GHz, filaire. Ils se
**recouvrent** à dessein : un appareil bi-bande apparaît dans les deux.

Le filtrage est **purement local** : le test de source
(`Mission10BoundaryTest`) et le test comportemental le prouvent.

### 2.6 La fiche appareil reste une bottom sheet

`PROTECTED_ROUTES` reste à **trois** routes et le test qui le vérifie
n'est pas touché. Une nouvelle route aurait exigé de modifier un test
accepté de la Mission 08.

## 3. Les tests

### 3.1 JVM

| Suite | Tests |
|---|---:|
| `router-core` | 193 |
| `poc-cli` | 56 |
| `app` (avant Mission 10) | 206 |
| `app` (nouveaux) | **91** |
| `app` (après) | **297** |
| **Total** | **546** — 0 échec |

Les nouveaux : `DeviceMetadataStoreTest` (38), `DeviceObservationTest`
(7), `DeviceOrderingTest` (14), `CustomNameViewModelTest` (10),
`Mission10BoundaryTest` (12), `LastSeenPresentationTest` (10).

### 3.2 Instrumentés, sur un Pixel 7 Pro (Android 17)

**38 tests, 0 échec** — dont 18 nouveaux pour la Mission 10.

```
$ adb shell am instrument -w -r dev.wifiadmin.app.test/androidx.test.runner.AndroidJUnitRunner
OK (38 tests)
```

### 3.3 Les tests de frontière

`Mission10BoundaryTest` (12 tests) lit les **sources**, comme les
frontières des Missions 08 et 09. Il prouve que :

- le `RouterAdapter` expose **exactement** ses quatre membres ;
- le dépôt **n'appelle** aucune opération d'écriture de l'adaptateur ;
- les noms personnalisés ne sont **jamais journalisés** ;
- les métadonnées sont dans `noBackupFilesDir` et **hors** du fichier
  d'instantané ;
- le contexte porte les **trois** parties de l'identité du routeur ;
- `setQuery`, `setFilter` et `setSort` ne touchent **pas** au
  repository — le filtrage est local ;
- le nom personnalisé ne devient **jamais** une clé Compose ;
- la purge n'est **pas** un travail système ;
- l'observation ne vient **pas** du framework Wi-Fi ;
- l'application n'a **aucun** endpoint distant ;
- les noms n'atteignent **ni** une URL, **ni** un Intent, **ni** un
  `WebView`.

### 3.4 Non-régression

| Garantie | Verdict |
|---|---|
| Une erreur réseau n'est jamais une expiration | CONFORME (`PollingInvariantsTest`) |
| Un timeout n'est jamais une expiration | CONFORME |
| Le cooldown garde son état | CONFORME |
| L'expiration vide la mémoire, garde le fichier | CONFORME |
| Le message exact est affiché | CONFORME |
| Le bouton Retour n'atteint pas un écran protégé | CONFORME |
| Le cache n'est pas restauré pour un autre hôte/compte/réseau | CONFORME |
| Une seule boucle de rafraîchissement | CONFORME |
| **Le filtrage ne déclenche aucune lecture** | CONFORME — **nouveau** : test de source + test comportemental |

## 4. L'interface

La carte est **allégée** : le MAC sort de la liste — il identifie, il ne
sert pas à identifier, et il est déjà dans la fiche. La carte montre :

| Élément | Rôle |
|---|---|
| Le **nom** (personnalisé s'il existe, sinon celui du routeur) | le discriminant |
| **Router : …** | le nom d'origine reste visible sous le nom personnalisé |
| **Adresse · bandes** | l'identification et la bande |
| **Signal** (`-47 dBm`) | à droite, quand le routeur en a donné un |
| **Pastille d'état** | la présence |
| **Dernière observation** | pour un appareil qui n'est plus là |

La recherche couvre le nom personnalisé **et** le nom d'origine, plus
l'adresse et la MAC.

Les états vides sont **différenciés** : « Aucun appareil ne correspond à
« x ». », « Aucun appareil en 5 GHz. », « Aucun appareil en 5 GHz ne
correspond à « x ». » — parce que l'action corrective n'est pas la même.

## 5. La recette sur l'appareil

| Point | Résultat |
|---|---|
| Installation et lancement | ✅ aucun crash (`AndroidRuntime:E` vide) |
| 38 tests instrumentés | ✅ 0 échec |
| Application lancée | ✅ `Status: ok`, processus vivant |
| État du téléphone rendu | ✅ Wi-Fi activé et connecté |

Les scénarios avec une **session réelle** ne sont pas automatisés : le
mot de passe administrateur n'est pas accessible, et il ne doit pas être
deviné — un échec déclencherait une pénalité de 180 s **côté routeur**.

## 6. Les limites à assumer

1. **La randomisation des MAC.** Un appareil qui randomise sa MAC est un
   appareil **nouveau** à chaque association : son nom personnalisé ne le
   suit pas. C'est le prix de l'honnêteté : une association par IP
   produirait des fusions **fausses**.
2. **Deux réseaux de même passerelle** ne sont pas distinguables sans le
   BSSID, qui exigerait `ACCESS_FINE_LOCATION`. Le pire cas est un
   changement **non détecté**, qui se manifeste par des lectures en échec —
   **jamais** par une expiration.
3. **La purge à trente jours** est déclenchée par l'application : un
   téléphone jamais ouvert conserve ses données au-delà.
4. **Un nom personnalisé survit à la déconnexion**, contrairement à
   l'instantané : c'est le travail de l'utilisateur. Il est effacé par
   l'expiration de session **en mémoire** uniquement.
5. **L'isolation entre deux routeurs** repose sur
   `(hôte, compte, réseau détecté)`. Deux routeurs partageant les trois
   sont indiscernables.

## 7. Les réserves de la revue indépendante

Voir `MISSION_10_REVIEW.md` pour le rapport complet de la revue de
sécurité.

Les cinq constats de la revue sont **corrigés** et couverts par les tests
JVM :

1. le hash du contexte est **préfixé par la longueur** de chaque partie :
   deux routeurs ne peuvent plus partager un fichier par ambiguïté de
   séparateur ;
2. la purge est appliquée **à l'écriture** d'une observation : elle ne
   dépend plus d'un appel explicite que l'application ne faisait pas ;
3. les caractères Unicode **invisibles** (espaces de largeur nulle, BOM,
   séparateurs de ligne, marque de lettre arabe) sont retirés des noms ;
4. les tests de frontière vérifient désormais une **liste blanche**
   d'appels à l'adaptateur, analysent **toutes** les sources pour les
   journaux et valident le **constructeur** du store ;
5. le fichier de test ne contient plus de caractères de contrôle
   littéraux : Git le traite à nouveau comme du texte.

Les réserves ci-dessus restent des limites **structurelles** : elles ne
sont pas des défauts de la mission.

## 8. Verdict

# **ACCEPTÉ AVEC RÉSERVES**

Les trois fonctionnalités sont livrées, testées sur la JVM **et** sur un
appareil Android réel. Les garanties des Missions 08 et 09 tiennent, et
les tests de frontière prouvent que les noms personnalisés ne quittent
jamais le téléphone.

Les réserves sont les limites **structurelles** ci-dessus : elles ne sont
pas des défauts de la mission, mais des conséquences de ce que le routeur
expose — et elles sont assumées et documentées.
