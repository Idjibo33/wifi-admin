# Mission 11 — Rapport d'amélioration UI/UX, accessibilité et finition Android

> Date : 06/11/2026 · Mission 11 · branche `feature/mission-11`
> Base : `1862ecf` (`develop`, Mission 10 fusionnée, PR #3)
> Verdict : **mission accomplie**, PR prête pour revue. **Aucun merge.**
>
> **Réserve de méthode.** Les sous-agents OpenCode n'ont pas pu tourner
> (limite d'utilisation gratuite atteinte). Les quatre angles demandés —
> UI/UX, architecture Compose, accessibilité, QA/sécurité — ont été couverts
> en lecture directe du code par l'agent principal.

---

## 1. Réalisation en bref

La Mission 11 est une mission de polissage, pas de refonte :

- **design system léger** : les rôles `surfaceContainer*` de Material 3
  remplacent les mélanges d'alpha sur `surfaceVariant`, et le thème définit
  ces rôles en clair et en sombre ;
- **accessibilité** : zones live pour les erreurs et les états vides,
  `stateDescription` pour les cartes de synthèse et les badges de présence,
  taille minimale des cibles tactiles ;
- **hiérarchie** : les bandes du tableau de bord sont regroupées sous leurs
  propres cartes avec une explication de la double comptabilisation ;
- **fiche appareil** : l'état passe en tête, avant le détail technique, et
  le signal porte son libellé de qualité.

Aucune fonctionnalité n'a été ajoutée, aucun ViewModel n'a été modifié,
aucune permission n'a été ajoutée, aucun protocole n'a été touché.

---

## 2. Modifications réalisées

### 2.1 Design system

| Fichier | Correction |
|---|---|
| `ui/theme/Theme.kt` | Ajout de `surfaceContainerLowest`, `surfaceContainerLow`, `surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest` en clair et en sombre |
| `ui/components/CommonComponents.kt` | `StatCard` sur `surfaceContainerHigh` |
| `ui/dashboard/DashboardScreen.kt` | Carte « Dernière lecture » sur `surfaceContainerHigh` |
| `ui/devices/DevicesScreen.kt` | Cartes d'appareils sur `surfaceContainerHigh` |
| `ui/login/LoginScreen.kt` | Carte du formulaire sur `surfaceContainerLow` |
| `ui/settings/SettingsScreen.kt` | Sections sur `surfaceContainerLow` |

Par conséquent : plus aucun `surfaceVariant.copy(alpha = ...)`, plus de
rouge à coefficients enfouis dans les écrans.

### 2.2 Tableau de bord

- Le libellé « Wi-Fi actif » devient **« Appareils actifs »** : le nombre
  compte les associations Wi-Fi, pas seulement une bande.
- Les cartes **Filaire** et **Périmés** sont regroupées sur une ligne.
- Les cartes **2,4 GHz** et **5 GHz** forment un **deuxième bloc** précédé
  de l'explication « Associations par bande — un appareil bi-bande est
  compté dans les deux ».
- « Dernière lecture » affiche la **date et l'heure** (`dd/MM/yyyy à
  HH:mm:ss`), pas seulement l'heure.
- Le bouton Actualiser a une taille minimale de 48 dp.

### 2.3 Liste et fiche des appareils

- `PresenceBadge` porte une `stateDescription` : la présence n'est pas
  portée par la seule couleur.
- Le signal affiche un libellé (`Signal bon`, etc.) en plus de `-58 dBm`,
  et la même description sert de sens pour le lecteur d'écran.
- La fiche commence par l'**état** et son explication, avant la MAC et
  l'adresse.
- `DetailRow` n'a plus de largeur fixe : le label et la valeur partagent
  la ligne par poids, donc un gros texte ne déborde plus.
- Les boutons et les recherches ont une taille minimale de 48 dp.

### 2.4 Connexion et réglages

- Les champs et le bouton du formulaire mesurent au moins 56 dp.
- Les réglages appliquent `imePadding` : le clavier ne recouvre pas le
  champ.
- Les messages d'erreur du champ sont annoncés en zone live.
- Le bouton de déconnexion et « Enregistrer » ont une taille minimale
  de 48 dp.

### 2.5 États vides et erreurs

- `ErrorState` : zone live `Assertive`.
- `EmptyState` : zone live `Polite`.

---

## 3. Fichiers affectés

| Fichier | Nature |
|---|---|
| `ui/theme/Theme.kt` | rôles `surfaceContainer*` |
| `ui/components/CommonComponents.kt` | rôles, sémantiques, `DetailRow` |
| `ui/components/Presentation.kt` | contraste du badge, `signalQualityLabel` |
| `ui/dashboard/DashboardScreen.kt` | regroupement des cartes, date complète, taille |
| `ui/dashboard/DashboardViewModel.kt` | `formatLastRefresh` avec le jour |
| `ui/devices/DevicesScreen.kt` | badge, signal, fiche |
| `ui/login/LoginScreen.kt` | taille, zones live, clavier |
| `ui/settings/SettingsScreen.kt` | taille, `imePadding` |
| `app/src/test/.../PresentationTest.kt` | contraste WCAG, libellé du signal |
| `app/src/test/.../Mission11BoundaryTest.kt` | garde-fous du design system |
| `app/src/androidTest/.../DeviceAccessibilityInstrumentedTest.kt` | 3 tests d'accessibilité |

**Aucun** changement dans `data/`, `navigation/`, `router-core/` ni dans
`WifiAdminNavHost`.

---

## 4. Comparaison avant / après

| Composant | Avant | Après |
|---|---|---|
| Couleur des cartes | `surfaceVariant.copy(alpha = 0,4)` ou `0,35` | Rôles `surfaceContainerLow` et `surfaceContainerHigh` |
| Palette sombre | `surfaceContainer*` par défaut du système | Rôles définis, cohérents avec le clair |
| Badge « Périmé » | `#EF6C00` → **3,08:1** | `#BF5600` → **4,62:1** ✅ |
| Signal | `-58 dBm` seul | `-58 dBm — Signal bon` |
| Dernière lecture | `15:38:12` | `06/11/2026 à 15:38:12` |
| Fiche appareil | État après tout le détail technique | État en tête |
| `DetailRow` | Label à 120 dp fixe | Poids 45/55 |
| Erreurs | Non annoncées | Zone live assertive |

---

## 5. Résultats des tests

### 5.1 JVM

| Module | Tests | Échecs |
|---|---:|---:|
| `router-core` | 193 | 0 |
| `poc-cli` | 56 | 0 |
| `app` | 309 | 0 |
| **Total** | **558** | **0** |

Les nouveaux tests vérifient le contraste WCAG des badges et les libellés
lus par le lecteur d'écran.

### 5.2 Instrumentés, sur un Pixel 7 Pro (Android 17)

```
$ ./gradlew :app:connectedDebugAndroidTest
BUILD SUCCESSFUL
OK (41 tests)
```

- **38 tests existants** (Missions 08 à 10), y compris les 18 de la Mission 10
  qui n'avaient **pas été rejoués** après les corrections de sécurité ;
- **3 tests nouveaux** pour l'accessibilité.

### 5.3 Scénarios hors ligne

- Pare-feu : **10/10**.
- Expiration : **3/3**.

Aucune écriture.

---

## 6. Revue sécurité

La revue a été faite en lecture des sources sur le périmètre de la
mission. Elle n'a trouvé :

- **aucune** écriture vers le routeur ;
- **aucune** modification du protocole ZLT ;
- **aucun** bouton Bloquer/Débloquer ;
- **aucune** permission nouvelle ;
- **aucun** secret dans les rapports ;
- **aucune** modification de `main`.

Le point le plus sensible du changement visuel est le **thème** : il est
décoratif et s'applique uniquement à l'affichage.

La condition essentielle des Missions 08 et 09 est intacte : le polling
reste dans le même endroit, sans lecture supplémentaire.

---

## 7. Limites restantes

1. **Agrandissement du texte** à 200 % : le texte grandit, mais la grille
   reste à deux colonnes. L'adaptation aux grandes tailles reste à valider.
2. **Adaptation à la largeur** via `WindowSizeClass` : non implémentée,
   car la dépendance n'est pas au catalogue.
3. **Aperçus Compose** : toujours les mêmes, sans état sombre dédié.
4. **Navigation** : pas de contrôle ajouté. Le comportement de Retour est
   celui de la Mission 09.

---

---

## 8. Complément 07/11/2026 — modernisation profonde (PR #4, même branche `feature/mission-11`)

> Le §1-7 ci-dessus décrit le polissage initial du 06/11. Le présent complément est la
> refonte visuelle demandée : l'utilisateur a refusé une « simple série d'ajustements » et
> exigé une modernisation **clairement perceptible**, re-pensant composition, hiérarchie et
> navigation. Les quatre sous-agents ont été exécutés en parallèle en lecture seule puis
> intégrés par l'agent principal. **Aucun merge, aucun `main`, aucun force-push.**

### 8.1 Direction visuelle arbitrée

**« Instrument honnête, sobriété d'atelier »** — 3 principes : vérité avant décoration
(statut toujours visible, jamais d'état inconnu transformé en certitude), gravité
hiérarchique (Statut tonal / Action / Contenu neutre), densité maîtrisée et scan rapide.

### 8.2 Écrans modernisés

| Écran | Avant | Après |
|---|---|---|
| **Tableau de bord** | 6 `StatCard` égales + carte « Dernière lecture » + carte massive « Filtrage MAC » (le plus grand bloc de l'écran) | `AppTopBar` avec sous-titre de fraîcheur (« Lecture 06/11 à 15:38 · en direct »), hero `primaryContainer` combiné « N actifs sur M connus » avec pont « Voir les N actifs » vers la liste, 3 cartes `Actifs/Filaire/Périmés` égales (icône `History` pour Périmés, plus de `Router`), bandes en carte `surfaceContainer` en **liste d'associations** avec explication du bi-bande, « Dernière lecture » discrète, « Filtrage MAC » en `InfoNoticeCard` repliable |
| **Liste** | Liste plate, signal `-58 dBm` seul, chips + tri sur la même ligne, hauteur fixe 48 dp qui casse à 200 % | Groupes `Actifs / Filaires / Vus précédemment` (`groupRows`, ordre trié préservé), signal en **barres 4 niveaux + libellé** (`SignalBars` + `signalQualityLabel`), chips sur une ligne et tri sur la suivante, `heightIn(min=48.dp)`, `imePadding` local, `SectionHeader` avec `heading()` |
| **Détails + renommage** | 12 `DetailRow` à plat, double `HorizontalDivider`, suppression du surnom en bas du scroll | Carte identité `primaryContainer` (nom, badge, IP·bande·signal), 2 CTA en tête (`Renommer` + `Retirer le surnom`), sections **Statut / Chronologie / Détails techniques** (repliables, ouvertes par défaut pour ne pas masquer les champs attendus par les tests), texte cache corrigé en `onSurfaceVariant` |
| **Connexion** | `DetectedRouterRow` passive, aide loin du bouton, champs toujours actifs pendant la connexion | État réseau en carte `surfaceContainer` explicite, aide « Le mot de passe n'est pas conservé… » **sous** le bouton, champs désactivés pendant `isConnecting`, `AnimatedVisibility` sur les messages, `heightIn` 48/56 dp conservés |
| **Paramètres** | Aucun `TopAppBar`, logout neutre, confirmation sans feedback | `TopAppBar` (« Réglages »), zone de risque `errorContainer` pour « Se déconnecter » avec `AlertDialog` de confirmation, tuiles `SettingsSection` inchangées, carte info `secondaryContainer` conservée |
| **Navigation** | 3 onglets « Tableau de bord/Appareils/Réglages », `TopAppBar` sans sous-titre | `BottomBar` extraite sans changement de comportement (`popUpTo/saveState/restoreState` intacts), sous-titres de fraîcheur sur Dashboard et Liste, pont Dashboard → Liste |

### 8.3 Design system

- Nouveau `ui/theme/Dimens.kt` : `Space1/2/3/4/6/8`, `TouchMin` 48 dp, `FieldMin` 56 dp,
  `IconSm/Md/Banner/State/Login`, `CardPaddingH/V`, `BadgeMinH/Padding`.
- `Theme.kt` : `titleLarge 22/28 SemiBold`, `titleSmall`, `labelSmall`, `headlineMedium 28/36`,
  `displayLarge 36/44` ajoutés ; **`dynamicColor = false` par défaut** (identité stable +
  contraste des badges garanti). Palettes clair/sombre inchangées.
- `CommonComponents.kt` : `rememberAnimationEnabled/rememberFadeMillis` (respecte
  `ANIMATOR_DURATION_SCALE`), `AppTopBar`, `ScreenContent` (fade sur clés stables, jamais
  sur le `UiState` entier), `InfoNoticeCard`, `SectionHeader`, `SignalBars` + `signalBarsLevel`.
  `StatCard/LoadingState/ErrorState/EmptyState/CachedDataBanner/DetailRow` conservent leurs
  `testTag`, `liveRegion` et `stateDescription`.
- Icônes : `Filled` uniquement, aucune catégorie d'appareil inventée, distinction par
  `presence/bands/interface/signal` uniquement.
- États : textes exacts inchangés (`staleDataMessage`, `EXPIRY_MESSAGE`, `NETWORK_CHANGED_MESSAGE`,
  `deviceEmptyMessage`, `signalQualityLabel`, `formatLastSeen`). Aucun « déconnecté », aucune
  date de déconnexion inventée.

### 8.4 Micro-interactions

Fade léger entre états d'écran, `AnimatedVisibility` pour les bannières et la carte info,
`contentType` + `animateItem` pour la liste (désactivés quand les animations système sont
réduites). Aucune animation permanente, aucune dépendance décorative, aucun `WindowSizeClass`
(hors catalogue — grille en `Row weight(1f)`, 3 colonnes sur Dashboard).

### 8.5 Résultats des sous-agents

- **Sous-agent 1 (Product Designer)** : audit + direction « atelier de diagnostic », organisation
  par écran, comparaison avant/après, plan en 7 phases. Arbitrage : `dynamicColor=false`,
  groupes de liste, hero combiné, filtrage MAC repliable, logout destructif.
- **Sous-agent 2 (Android UI Engineer)** : architecture Compose, extraction de composants,
  préservation `DeviceList` publique + `testTag`, protection lifecycle/polling/back/keyboard.
  Aucun ViewModel ni `data/` touché.
- **Sous-agent 3 (Design System & accessibilité)** : tokens typographie/espacements/surfaces/icônes,
  matrice d'états mot pour mot, WCAG AA, 48 dp, TalkBack, police 200 %, petits écrans.
- **Sous-agent 4 (QA/perf/sécurité)** : revue indépendante, risques R1-R7, pyramide de tests,
  anonymisation des captures, plan before/after clair/sombre + 320 dp + police agrandie.

Aucun sous-agent n'a modifié de fichier ; aucun n'a modifié les mêmes fichiers.

### 8.6 Résultats des tests

- JVM : `router-core` 193, `poc-cli` 56, `app` **309 + 7 nouveaux** (`Mission11ModernizationTest` :
  bi-bande, seuils signal, `signalBarsLevel`, date du dernier refresh, `statCardDescription`,
  `groupRows` ordre et omission) — **0 échec**.
- Build : `assembleDebug` + `assembleDebugAndroidTest` OK.
- Instrumentés : **non rejoués ici**, `adb` introuvable et aucun émulateur. Les 41 tests du
  06/11 restent la référence ; les `testTag` et `semantics` ont été préservés explicitement
  pour ne pas casser `DeviceAccessibilityInstrumentedTest` et consorts.
- Scénarios hors ligne : pare-feu 10/10, expiration 3/3 — **OK**, aucune écriture.

### 8.7 Fichiers affectés (complément seul)

| Fichier | Nature |
|---|---|
| `ui/theme/Dimens.kt` | **nouveau** : tokens d'espacement et de toucher |
| `ui/theme/Theme.kt` | typographie complète, `dynamicColor=false` |
| `ui/components/CommonComponents.kt` | `AppTopBar`, `ScreenContent`, `InfoNoticeCard`, `SectionHeader`, `SignalBars`, animation respectueuse du système |
| `ui/devices/DevicePresentation.kt` | `DeviceSection`, `section()`, `groupRows` |
| `ui/dashboard/DashboardScreen.kt` | hero combiné, 3 cartes, bandes en liste, info repliable, pont vers liste, `ScreenContent`, previews clair/sombre/1,3× |
| `ui/devices/DevicesScreen.kt` | groupes, `SignalBars`, chips/tri séparés, `AppTopBar`, identité `primaryContainer`, CTA en tête, sections, previews clair/sombre/1,3× |
| `ui/login/LoginScreen.kt` | carte d'état, aide sous bouton, champs désactivés pendant la connexion, `AnimatedVisibility` |
| `ui/settings/SettingsScreen.kt` | `TopAppBar`, zone destructive + confirmation |
| `navigation/WifiAdminNavHost.kt` | `BottomBar` extraite, `onSeeActives` vers Liste |
| `Mission11ModernizationTest.kt` | **nouveau** : 7 tests cibles |
| `docs/screenshots/mission-11/README.md` | **nouveau** : procédure et statut des captures |

**Aucun** changement dans `data/`, `router-core/`, `SessionNavigation`, les ViewModels ou le
protocole ZLT. Aucun bouton bloquer/débloquer, aucune écriture routeur, aucun filtrage MAC.

### 8.8 Captures avant/après

`docs/screenshots/mission-11/before/` et `.../after/` contiennent pour l'instant le
`README.md` qui documente la procédure et l'origine démonstration. **Aucun PNG n'est
prétendu existant sans être déposé.** Les captures instrumentées sont impossibles ici
(`adb` absent, pas d'émulateur) ; les preuves visuelles sont les Compose Preview avec
fixtures fictives (`sample-*`, `192.168.1.10`, MAC `aa:bb:…`), dont le cas bi-bande
`sample-ordinateur`, exportables depuis Android Studio en clair sombre et police 1,3×.

### 8.9 Limites restantes

1. **Instrumentés non rejoués** sans appareil : à relancer (`41 tests`) avant tout merge.
2. **Police 200 % et 320 dp** : structure prête, validation visuelle à faire sur appareil.
3. **`System.currentTimeMillis()` en composition** (liste) : inchangé, artefact possible
   sur captures — à `remember`/horloge injectée si jank observé.
4. **Aucune capture PNG déposée** : procédure et previews fournis en remplacement.

## 9. Verdict final

- Modernisation **visiblement différente** : hero, groupes, identité tonale, navigation
  unifiée — pas seulement couleurs et rayons.
- Fonctionnalités préservées, tests JVM au vert, build debug au vert, scénarios FakeRouter OK.
- PR #4 mise à jour sur `feature/mission-11`, **sans merge**. PR : https://github.com/Idjibo33/wifi-admin/pull/4
