# Captures Mission 11 — preuves visuelles

> **Données de démonstration — jamais de capture d'un vrai réseau.**
> Toutes les fixtures utilisent des noms fictifs (`sample-*`), des adresses `192.168.1.10`–`13`
> et des MAC `aa:bb:cc:dd:ee:*` issues des `@Preview` existants. Aucun identifiant, aucune
> adresse privée réelle, aucune donnée personnelle n'est capturée. Le mot de passe ne doit
> jamais être saisi dans les champs des captures.

## Captures réelles sur Pixel 7 Pro (7 novembre 2026, recapture)

> Dépôt le 07/11/2026 à 18 h environ, depuis le Pixel 7 Pro cheetah `2B091FDH3004DG`
> (Android 17, API 37) connecté en USB. ADB : `/home/ismael/Android/Sdk/platform-tools/adb`.

- **Before** = worktree `1862ecf` (Mission 10, `develop`), **after** = branche
  `feature/mission-11` (`0af77ee` + harnais + visibilité `internal`). Mêmes fixtures,
  même harnais, même appareil, thème forcé `WifiAdminTheme(darkTheme, dynamicColor=false)`.
- Rendu par test instrumenté `Mission11ScreenshotTest` (`app/src/androidTest/...`) via
  `createComposeRule()` + `onRoot().captureToImage()`, état construit à la main, **aucun
  routeur contacté** (stub adapter `connect=true`, `fetchDevices` jamais appelé par les
  composables capturés). Seuls `DashboardContent`/`DeviceDetailSheet` sont passés de
  `private` à `internal` pour ce harnais ; comportement inchangé.
- Chaque dossier contient **13 PNG** (vérifiés lisibles, `route check` manuelle) :
  `01-login-clair/sombre`, `02-dashboard-clair/sombre`, `02-dashboard-cache-sombre`,
  `03-liste-clair/sombre`, `04-details-clair/sombre`, `05-recherche-filtres-sombre`,
  `06-renommage-sombre`, `07-etat-cache-sombre`, `08-settings-sombre`.
  Thème clair **et** sombre vérifiés sur les écrans principaux ; états secondaires en
  sombre pour l'espace. Les previews `@Preview` restent disponibles en complément.
- Comparaison visuelle immédiate : avant, 6 cartes égales + bloc « Filtrage MAC » intégral ;
  après, hero bleu « 3 actifs sur 4 connus », 3 cartes, bandes en liste, bloc repliable.
  Liste avant : liste plate, signal numérique seul ; après : sections Actifs/Filaires,
  barres de signal + libellé. Détail avant : MAC d'abord, État en bas ; après : carte
  identité bleue, « Renommer », sections Statut/Chronologie/Détails.
- Aucun mot de passe saisi, aucune donnée réelle : voir encadré en haut. Le `192.168.1.x`
  et `aa:bb:…` sont des fixtures fictives communes aux deux versions, pas un réseau réel.

## Noms de fichiers attendus

| Écran | Before | After |
|---|---|---|
| 01 Connexion | `before/01-login-clair.png`, `before/01-login-sombre.png` | `after/01-login-clair.png`, `after/01-login-sombre.png` |
| 02 Dashboard | `before/02-dashboard-*.png` | `after/02-dashboard-*.png` |
| 03 Liste | `before/03-liste-*.png` | `after/03-liste-*.png` |
| 04 Détails | `before/04-details-*.png` | `after/04-details-*.png` |
| 05 Recherche/filtres/tri | `before/05-recherche-filtres-*.png` | `after/05-recherche-filtres-*.png` |
| 06 Renommage | `before/06-renommage-*.png` | `after/06-renommage-*.png` |
| 07 États dégradés | `before/07-etat-cache.png`, `07-etat-networkChanged.png`, `07-etat-payloadAbsent.png` | idem |
| 08 Paramètres | `before/08-settings-*.png` | `after/08-settings-*.png` |

Le cas bi-bande (`sample-ordinateur` en 2,4 + 5 GHz) est inclus dans les previews Dashboard
et Liste pour démontrer que la somme des bandes peut dépasser le total sans être présentée
comme une erreur.
