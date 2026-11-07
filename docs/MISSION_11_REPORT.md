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

## 8. Verdict

**Mission 11 accomplie.**

- Le design system est homogène et garde-fouillé par des tests.
- L'accessibilité de base est en place et testée.
- Aucune régression détectée sur les Missions 08, 09 et 10.
- La PR vers `develop` est prête pour revue. **Aucun merge.**
