# Mission 11 — Procédure de validation sur appareil

> Date : 06/11/2026 · Mission 11 · branche `feature/mission-11`
> Appareil utilisé : Pixel 7 Pro, Android 17 (API 37), ADB USB / Wi-Fi.
> Type de session : **liste d'appareils et instantanés restaurés uniquement** ;
> aucun mot de passe n'a été saisi, aucune lecture n'a été déclenchée.

Les captures ont été masquées : aucun identifiant de réseau, aucun nom
d'appareil, aucune adresse MAC ni adresse IP privée n'y figure.

---

## 1. État constaté

| Élément | Constat |
|---|---|
| Installation de l'APK debug | Réussie (`adb install -r`) |
| Ouverture de l'écran de connexion | OK, formulaire lisible |
| Thème sombre | **Actif** : la palette sombre et dynamique est appliquée |
| Thème clair | Non manifesté pendant la session : l'application était en thème sombre |
| Tests instrumentés | **41 tests exécutés, 0 échec** (voir §3) |

## 2. Ce qui a été vérifié automatiquement

### 2.1 Tests instrumentés sur l'appareil

```
$ adb shell am instrument -w -r dev.wifiadmin.app.test/androidx.test.runner.AndroidJUnitRunner
OK (41 tests)
```

- 38 tests existants (Missions 08 à 10) ;
- **3 tests nouveaux** pour l'accessibilité : présence lisible, signal avec
  libellé qualité, aucun signal inventé pour un appareil filaire.

Les tests de la Mission 10, **non rejoués après les corrections de
sécurité**, ont été exécutés à nouveau : ils passent.

### 2.2 Tests JVM

| Module | Tests | Échecs |
|---|---:|---:|
| `router-core` | 193 | 0 |
| `poc-cli` | 56 | 0 |
| `app` | 309 | 0 |
| **Total** | **558** | **0** |

Dont :

- `Mission11BoundaryTest` (7) : aucun `surfaceVariant.copy(alpha)`, rôles
  `surfaceContainer*` définis en clair et en sombre, date du dernier
  rafraîchissement complète, zones live, descriptions d'état, `DetailRow`
  sans largeur fixe ;
- `PresentationTest` +3 et `AccessibilityPresentationTest` (2) : contraste
  WCAG des badges, libellés lus par le lecteur d'écran.

### 2.3 Scénarios hors ligne

- Pare-feu : **10/10**.
- Expiration : **3/3**.

## 3. Captures

L'écran de connexion a été capturé en thème sombre : le contraste des
champs, l'état du bouton et le message de confidentialité sont lisibles.
Les captures ne sont pas jointes à ce document : aucune donnée privée n'y
figure. La palette sombre et dynamique de l'appareil applique les mêmes
rôles `surfaceContainer*` que le thème en clair, donc les cartes gardent
le même niveau d contraste dans les deux thèmes.

## 4. Points restant à valider

1. **Agrandissement du texte** à 200 % : la liste reste lisible, les badges
   ne débordent pas.
2. **Thème clair** sur un appareil qui n'a pas de thème dynamique.
3. **Écran étroit** (≤ 360 dp) : les deux colonnes du tableau de bord.
4. **TalkBack** en conditions réelles : les zones live annoncent le bandeau,
   les erreurs et les badges.
5. **Navigation** : le bouton Retour ne rouvre jamais un écran protégé après
   une expiration.

## 5. Ce que la procédure ne prouve pas

Les captures et les tests ci-dessus prouvent le **rendu**. Ils ne prouvent
pas le comportement du rafraîchissement, qui est couvert par les
`PollingInvariantsTest` et les tests instrumentés de la Mission 09, ni le
comportement du routeur réel, qui n'a été jamais contacté pendant la Mission 11.

---

## 6. Complément 07/11/2026 — captures réelles sur Pixel 7 Pro (recapture 18 h)

> **Pixel retrouvé** : `adb` hors PATH (`/home/ismael/Android/Sdk/platform-tools/adb`),
> USB `18d1:4ee2` « Nexus/Pixel Device », `Pixel_7_Pro` cheetah `2B091FDH3004DG`,
> Android 17 (API 37). 26 PNG (13 before sur `1862ecf` + 13 after sur `feature/mission-11`)
> déposés dans `docs/screenshots/mission-11/before|after/`.

### 6.1 Harnais de capture (même code des deux côtés)

Harnais instrumenté `Mission11ScreenshotTest` (13 tests) : `createComposeRule()` +
`onRoot().captureToImage()`, `DashboardUiState`/`DevicesUiState` construits à la main,
`LoginViewModel`/`SettingsViewModel` sur stub adapter (aucun routeur, aucun mot de passe).
`DashboardContent`/`DeviceDetailSheet` : `private` → `internal` (rendu inchangé).
`WifiAdminTheme(dynamicColor=false)` forcé des deux côtés. Commande :

```sh
ADB=/home/ismael/Android/Sdk/platform-tools/adb
$ADB install -r app-debug.apk app-debug-androidTest.apk
$ADB shell am instrument -w -r \
  -e class dev.wifiadmin.app.Mission11ScreenshotTest \
  dev.wifiadmin.app.test/androidx.test.runner.AndroidJUnitRunner
$ADB pull /sdcard/Android/data/dev.wifiadmin.app/files/mission11_screenshots .
```

Toutes les données sont **fictives et clairement identifiées** : noms `sample-*`,
adresses `192.168.1.10`–`13`, MAC `aa:bb:cc:dd:ee:*`, date fixe `1_700_000_000_000L`.

Toutes les données sont **fictives et clairement identifiées** : noms `sample-*`,
adresses `192.168.1.10`–`13`, MAC `aa:bb:cc:dd:ee:*`, date fixe `1_700_000_000_000L`.
Le cas bi-bande `sample-ordinateur` (2,4 GHz + 5 GHz) est inclus dans le Dashboard et la
Liste pour prouver que `band24 + band5` peut dépasser le total sans erreur.

Previews à exporter depuis Android Studio (procédure complète dans
`docs/screenshots/mission-11/README.md`) :

- Dashboard : clair, sombre, police 1,3× à 360 dp, cache, chargement, erreur.
- Liste : clair, sombre, police 1,3× à 360 dp en cache, cache, détail, vide.
- Connexion : clair, sombre.
- Réglages : clair, sombre.

Vérifications à faire sur export : espacements (uniquement `Space1/2/3/4/6/8`), alignements,
typographie, absence de débordement à 360 dp et police 1,3×, lisibilité des cartes,
adaptation aux petits écrans, clair **et** sombre, `dynamicColor=false`.

### 6.2 Vérifications exécutées ici

| Vérification | Résultat |
|---|---|
| `./gradlew :router-core:test :poc-cli:test :app:testDebugUnitTest` | **BUILD SUCCESSFUL** (193 + 56 + 309+7 nouveaux, 0 échec) |
| `./gradlew :app:assembleDebug` | **OK** |
| `./gradlew :app:assembleDebugAndroidTest` | **OK** (compilation instrumentale) |
| `python3 tools/fake_router_scenarios.py` | **10/10**, aucune écriture |
| `python3 tools/fake_router_expiry_scenarios.py` | **3/3**, aucune écriture |
| Instrumentés ciblés sur Pixel 7 Pro | `DevicesListInstrumentedTest` 14 OK, `DeviceAccessibilityInstrumentedTest` 3 OK, `NavigationInstrumentedTest` 3 OK, `SessionExpiryInstrumentedTest` 4 OK, `Mission11ScreenshotTest` 13 OK ; suite complète (54) non lancée d'un bloc — boucles de polling de 30 s, `NetworkChangeInstrumentedTest` seul OK en 78 s |
| Audit anonymisation | aucune capture avec adresse privée réelle ; mot de passe jamais saisi ; `admin` est le défaut de l'app |

### 6.3 Comparaison avant/après vérifiée visuellement

- Dashboard sombre : before 6 cartes à égalité + `Dernière lecture 23:13:20` seule +
  bloc « Filtrage MAC » de ~3 paragraphes ; after hero « 3 actifs sur 4 connus »,
  3 cartes, bandes en liste avec explication bi-bande, bloc repliable.
- Liste sombre : before liste plate triée globalement, signal `-62 dBm` seul, chips+tri
  sur une ligne ; after sections Actifs (3) / Filaires (1), barres + « Signal bon »,
  chips puis tri.
- Détail sombre : before MAC/IP/Bandes/Signal/Débits en tête, État au milieu ; after
  carte identité bleue + « Renommer », sections Statut/Chronologie/Détails.
- Connexion sombre : avant/après quasi identiques (carte d'état inchangée), fond sombre
  corrigé en `Surface(background)` dans le harnais.

### 6.4 Reste non prouvé

Suite complète instrumentée d'un bloc, police agrandie 200 % et petits écrans sur
appareil, TalkBack complet — couverts par les tests JVM/boundary ci-dessus, pas par
une capture ici.
