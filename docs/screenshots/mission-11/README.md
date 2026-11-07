# Captures Mission 11 — preuves visuelles

> **Données de démonstration — jamais de capture d'un vrai réseau.**
> Toutes les fixtures utilisent des noms fictifs (`sample-*`), des adresses `192.168.1.10`–`13`
> et des MAC `aa:bb:cc:dd:ee:*` issues des `@Preview` existants. Aucun identifiant, aucune
> adresse privée réelle, aucune donnée personnelle n'est capturée. Le mot de passe ne doit
> jamais être saisi dans les champs des captures.

## Statut des captures (7 novembre 2026)

- `adb` est introuvable sur cette machine (`which adb` → vide) et aucun émulateur n'est
  disponible. Les captures instrumentées sur appareil ne peuvent donc pas être produites ici.
- À la place, les preuves visuelles sont fournies par les **Compose Preview avec fixtures
  fictives clairement identifiées**, exportables depuis Android Studio :
  - `DashboardScreen.kt` : `DashboardContentPreview` (clair), `DashboardDarkPreview` (sombre),
    `DashboardLargeFontPreview` (police 1,3×, 360 dp), `DashboardCachedPreview`, `DashboardLoadingPreview`, `DashboardErrorPreview`
  - `DevicesScreen.kt` : `DeviceListPreview` (clair), `DeviceListDarkPreview` (sombre),
    `DeviceListLargeFontPreview` (1,3×, 360 dp, cache), `DeviceListCachedPreview`, `DeviceDetailPreview`, `DeviceEmptyPreview`
  - `LoginScreen.kt` : `LoginPreview` (clair), `LoginDarkPreview` (sombre)
  - `SettingsScreen.kt` : `SettingsSectionPreview` (clair), `SettingsDarkPreview` (sombre)
- Procédure d'export (Android Studio) :
  1. Ouvrir le fichier, afficher la vue `@Preview`, choisir le thème clair ou sombre,
     l'échelle de police 1,0 ou 1,3 et la largeur 360 dp.
  2. Exporter le PNG et le déposer dans `docs/screenshots/mission-11/after/` avec le nom
     indiqué ci-dessous.
  3. Pour le « before », exporter les mêmes previews depuis un worktree sur `1862ecf`
     (Mission 10) et déposer dans `docs/screenshots/mission-11/before/`.
- **Aucun fichier PNG n'est prétendu existant tant qu'il n'a pas été exporté.**
  Ce dossier contient pour l'instant uniquement ce README, qui documente l'origine
  démonstration et la procédure. Les comparaisons avant/après textuelles sont dans
  `docs/MISSION_11_REPORT.md` §4.

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
