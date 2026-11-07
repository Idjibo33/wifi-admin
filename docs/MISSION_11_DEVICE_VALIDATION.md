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
