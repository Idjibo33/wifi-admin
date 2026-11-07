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

## 6. Complément 07/11/2026 — modernisation profonde, validation sans appareil

> Même branche `feature/mission-11`, PR #4. `adb` est **introuvable** sur cette machine et
> aucun émulateur n'est disponible : **aucune capture instrumentée n'a été produite ici**,
> et aucun PNG n'est prétendu existant sans être déposé dans
> `docs/screenshots/mission-11/before|after/` (qui contient pour l'instant le `README.md`).

### 6.1 Protocole de substitution (Compose Preview + fixtures)

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
| Instrumentés sur appareil | **Non rejoués** — `adb` absent, aucun émulateur (référence 06/11 : 41 tests OK sur Pixel 7 Pro) |
| Audit anonymisation | `tools/anonymise_observation.py` présent ; aucune capture avec adresse privée réelle ; mot de passe jamais saisi |

### 6.3 Points restant à valider sur appareil avant tout merge

1. Relancer les 41 tests instrumentés (`connectedDebugAndroidTest`).
2. Vérifier l'export des previews clair/sombre 360 dp + police 1,3× dans `docs/screenshots/mission-11/after/`.
3. TalkBack complet, clavier (recherche, renommage), retour arrière (onglets, sheet, dialogue,
   déconnexion, expiry) et changement de réseau.
4. Confirmations : badges WCAG AA inchangés, `liveRegion` inchangés, `testTag` inchangés.
