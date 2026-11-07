# Mission 11 — Audit UI/UX, accessibilité et finition Android

> Date : 06/11/2026 · Mission 11 · branche `feature/mission-11`
> Base audit : `1862ecf` (`develop`, Mission 10 fusionnée, PR #3)
> Méthode : lecture intégrale des écrans Compose, des ViewModels, de la
> navigation, du thème et des tests existants. **Aucune modification a été
> faite pour produire cet audit.**
>
> **Réserve de méthode.** L’orchestration avec sous-agents n’a pas pu être
> exécutée : les sous-agents OpenCode étaient en limite d’utilisation
> gratuite au moment de la mission. L’audit a été conduit en direct selon
> les quatre angles demandés (UI/UX, architecture Compose, accessibilité,
> QA/sécurité). Les quatre angles sont identifiables dans ce document.

---

## 1. Écrans examinés

| Écran | Fichier | Lignes |
|---|---|---:|
| Connexion | `ui/login/LoginScreen.kt` | 309 |
| Tableau de bord | `ui/dashboard/DashboardScreen.kt` | 412 |
| Appareils (liste, détail, renommage) | `ui/devices/DevicesScreen.kt` | 882 |
| Réglages | `ui/settings/SettingsScreen.kt` | 157 |
| Composants partagés | `ui/components/CommonComponents.kt` | 262 |
| Wording et présentation | `ui/components/Presentation.kt` | 290 |
| Présentation des appareils | `ui/devices/DevicePresentation.kt` | 251 |
| Navigation | `navigation/WifiAdminNavHost.kt` | 175 |
| Session/expiration | `navigation/SessionNavigation.kt` | 56 |
| Thème | `ui/theme/Theme.kt` | 119 |
| ViewModels | `ui/{login,dashboard,devices,settings}/*ViewModel.kt` | 981 |

L’app n’a que quatre écrans, aucun écran n’a été inventé pour cet audit.

---

## 2. problèmes critiques

### C1 — Le thème sombre est appliqué, mais sa palette est inégale

`WifiAdminTheme` **est** appelée dans `MainActivity` et le thème sombre
fonctionne. En revanche, la palette sombre est **décalée** par rapport à la
palette claire : en mode sombre, `surfaceVariant` vaut `#41474D`, soit la
même valeur que `outline` et `outlineVariant`.

| Fichier | Ligne |
|---|---:|
| `ui/theme/Theme.kt` | palette sombre `surfaceVariant` |

### C2 — Rôles semi-opaques reconstruits à la main

Les cartes utilisent :

```kotlin
surfaceVariant.copy(alpha = 0.4f)
surfaceVariant.copy(alpha = 0.35f)
```

Or Material 3 fournit des rôles dédiés — `surfaceContainerLow`,
`surfaceContainer`, `surfaceContainerHigh`, `surfaceContainerHighest` —
qui font dans le thème exactement ce que la mission 10 faisait à la main,
sans alpha. L’alpha dépend en outre du fond : en thème sombre
`surfaceVariant` vaut `#41474D`, ce qui n’a rien à voir avec `#DDE3EA` du
thème clair. Deux valeurs différentes (0,4 et 0,35) sont utilisées dans le
code.

### C3 — Les informations clés n’annoncent rien aux lecteurs d’écran

Seul le bandeau cache porte une région live. Tout le reste repose sur
**la déduction visuelle** : la date de la dernière observation n’est pas
accompagnée d’un équivalent complet, les badges de présence n’ont pas de
`contentDescription`, les erreurs de connexion n’annoncent rien, et le
compteur du tableau de bord n’a pas de description de son unité.

### C4 — Les deux bandes du tableau de bord ne sont pas additionnables

Le tableau de bord affiche :

- `Total` = taille de la liste
- `Wi-Fi actif` = `activeCount`
- `Filaire`
- `2,4 GHz`, `5 GHz`
- `Périmés`

`activeCount` n’est pas la même chose que `band24Count + band5Count` :
un appareil bi-bande est compté dans les deux bandes, et inclus dans
`activeCount`. La somme `2,4 GHz + 5 GHz` peut donc dépasser `Total`,
sans qu’aucune indication n’explique pourquoi.

---

## 3. Problèmes importants

### I1 — Les boutons et champs n’ont pas de taille minimale

`LoginScreen` utilise un `OutlinedTextField` sans `heightIn` : une hauteur
standard de Compose est de 56 dp, mais aucun `min` n’est demandé.
`SettingsScreen` n’applique pas d’`imePadding`, donc le clavier peut
recouvrir le champ. Trois `< 48 dp` existent localement :

| Composant | Fichier | Lignes |
|---|---|---:|
| `IconButton` Actualiser | `DashboardScreen.kt` | 83-99 |
| `IconButton` Effacer la recherche | `DevicesScreen.kt` | 253-261 |
| `IconButton` Renommer | `DevicesScreen.kt` | 639-647 |

### I2 — La grille du tableau de bord ne s’adapte pas à la largeur

`DashboardScreen` compose toujours deux colonnes, même sur un écran étroit
ou grand. Sur un petit écran, les cartes peuvent compresser le texte.
L’usage de `WindowSizeClass` exigerait une dépendance qui n’est pas au
catalogue : hors périmètre de la mission, noté comme limite résiduelle.

### I3 — Le contraste d’un badge ne passe pas WCAG

`PresenceBadge` utilise `Color.White` sur des teintes codées en dur.
L’une d’elles, `#EF6C00` (« Périmé »), n’atteint que **3,08:1** avec le
blanc, en dessous des **4,5:1** exigés par WCAG AA pour le texte.

### I4 — Le badge de présence ignore le texte plus grand

Le badge s’aligne à la hauteur d’une ligne. Si la police s’agrandit, le
badge ne s’adapte pas automatiquement.

### I5 — Les descriptions accessibles manquent en places critiques

Cinq icônes décoratives utilisent `contentDescription = null`, ce qui est
correct pour des décorations, et la `StatCard` n’a ni `stateDescription`
ni `semantics` pour le nombre d’appareils.

### I6 — La fiche appareil annonce l’état après tout le détail

`DeviceDetailSheet` place `DetailRow(État)` **après** les informations
techniques, et le signal n’affiche que le nombre : `-58 dBm`. Un lecteur
d’écran ne sait pas l’état tant qu’il n’a pas lu toute la fiche.

### I7 — Spacements arbitraires et incohérents

Les espacements sont dispersés sans token commun (paddings ponctuels,
`spacedBy` avec des valeurs différentes selon les écrans).

### I8 — Le `DetailRow` a une largeur fixe de 120 dp

C’est trop étroit pour certains labels en français, et ne tient pas compte
de la mise à l’échelle du texte.

### I9 — `formatLastRefresh` n’affiche que l’heure

La fiche `Dernière lecture` affiche `HH:mm:ss` sans date : sur une liste de
30 jours, l’utilisateur ne sait pas de quel jour parle l’heure.

### I10 — La navigation n’est pas décrite par son état de route

`Tab` ne portent pas de paramètre d’URL ou d’état de sélection par
lien profond. Le `NavigationBarItem` n’a pas d’état d’accessibilité pour
indiquer l’onglet actif.

---

## 4. Améliorations facultatives

### F1 — Typographie complète

`AppTypography` ne définit que 7 styles sur 15 : le reste retombe par
défaut sur Material 3. Aucun problème réel, mais une opportunité d’uniformisation.

### F2 — Aperçus incomplets

Les previews utilisent l’aperçu de base, sans `showBackground` complet ni
états sombres.

### F3 — `Icons.Default.Wifi` répétée

Trois cartes utilisent la même icône `Wifi` pour des bandes différentes.

### F4 — Absence de « marque » du tableau de bord

Le tableau de bord nomme les cartes `Total`, `Filaire`, etc., sans titre
explicite (« Appareils connus du routeur » n’est qu’un `Text` solitaire).

---

## 5. Corrections recommandées

| Priorité | Correction | Effort |
|---|---|---|
| **C1** | Rééquilibrer la palette sombre (surfaceContainer*) | Faible |
| **C2** | Remplacer `surfaceVariant.copy(alpha)` par des rôles M3 stables (surfaceContainerLow, surfaceContainer, surfaceContainerHigh) | Faible |
| **C3** | Ajouter des `semantics`, `stateDescription` et descripteurs vocaux | Moyen |
| **C4** | Distinguer clairement `Appareils actifs` et `Bandes` ; renommer les cartes ; éviter la double comptabilisation visuelle | Faible |
| **I1** | Ajouter `heightIn(min = 48.dp)` aux cibles | Faible |
| **I2** | Adapter la grille à la largeur (`WindowSizeClass`) | Hors périmètre |
| **I3** | Remplacer les `Color(0xFF…)` par des tokens du `ColorScheme` | Faible |
| **I4** | Aligner le badge sur la ligne hauteur de texte et utiliser des `flowRow` | Faible |
| **I5** | Saisir les `contentDescription` manquantes et les `semantics` | Faible |
| **I6** | Enrichir la fiche avec `semantics` et éviter la ligne vide | Faible |
| **I7** | Créer un fichier `Dimens.kt` avec les tokens d’espacement | Moyen |
| **I8** | Rendre `DetailRow` adaptatif (`Modifier.weight`) | Faible |
| **I9** | `formatLastRefresh` en `dd/MM/yyyy à HH:mm:ss` | Faible |
| **I10** | Ajouter des `semantics` de navigation et des descriptions d’onglets | Faible |

---

## 6. Hors périmètre

- Aucune refonte d’architecture.
- Aucune fonctionnalité nouvelle.
- Aucune dépendance UI décorative.
- Aucune permission Android supplémentaire.
- Aucun changement du protocole ZLT.
- Aucune modification de `main`.

---

## 7. Verdict de l’audit

L’interface de la Mission 10 est **fonctionnelle et sérieuse** : la
wireframe est solide, mais elle dort dans **un blocage structurel** : le
thème sombre n’est pas appliqué et les tokens Material 3 ont divergé vers
des valeurs semi-opaques indépendantes.

Une fois ces deux points corrigés, l’expérience de base (connexion,
lecture, liste, fiche, réglages) est acceptable pour une mission de
**polissage et d’accessibilité**, sans refonte.

| Action | Statut |
|---|---|
| Aucun écran n’a été inventé | Vérifié |
| Problèmes critiques identifiés | 4 |
| Problèmes importants | 10 |
| Améliorations facultatives | 4 |

---

## 8. Complément 07/11/2026 — modernisation profonde (même PR #4)

Le polissage ci-dessus ne suffisait pas : l'interface restait une collection d'écrans
fonctionnels empilés. Quatre sous-agents spécialisés ont été lancés en parallèle en
lecture seule (Product Designer, Android UI Engineer, Design System & accessibilité,
QA/perf/sécurité) et leurs rapports ont été arbitrés par l'agent principal.

**Constat central.** Hiérarchie trop plate (6 `StatCard` du même poids, carte
« Filtrage MAC » plus grande que les compteurs), statut des données traité comme détail
annexe, liste sans groupes, fiche appareil sans identité, navigation sans sous-titre de
fraîcheur, `dynamicColor = true` qui efface l'identité WiFi Admin sur Android 12+,
typographie à 7 styles sur 15, espacements sans échelle, icône `Router` pour « Périmés »
trompeuse.

**Direction arbitrée : « Instrument honnête, sobriété d'atelier ».** Vérité avant
décoration (statut en premier, aucun chiffre sans provenance), gravité hiérarchique à
3 niveaux (Statut tonal / Action / Contenu neutre), densité maîtrisée et scan rapide
(groupes, signal en barres + libellé). Tokens : grille 4 dp (`Space1/2/3/4/6/8`),
`titleLarge/headlineMedium/displayLarge/labelSmall` ajoutés, `dynamicColor = false`
par défaut, icônes `Filled` uniquement, aucune catégorie d'appareil inventée.

Les implémentations et les preuves sont détaillées dans `MISSION_11_REPORT.md` §2 (réécrit)
et `MISSION_11_DEVICE_VALIDATION.md` §6-7. Les captures PNG ne peuvent pas être produites
ici (`adb` introuvable, aucun émulateur) : ce sont les Compose Preview avec fixtures
fictives clairement identifiées qui servent de preuves, exportables depuis Android Studio
(voir `docs/screenshots/mission-11/README.md`). Aucun fichier PNG n'est prétendu existant
sans être déposé.
