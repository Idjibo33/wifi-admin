# Mission 08 — tester le dernier instantané et l'expiration sur téléphone

> Ces manipulations se font **sur le téléphone et le routeur réels**. Aucune
> commande d'écriture n'est envoyée au routeur. Ne modifie aucun réglage du
> routeur pendant les tests.

## Prérequis

- Build debug installée : `./gradlew :app:installDebug` (téléphone connecté en
  USB, débogage USB activé).
- Le téléphone est connecté au Wi-Fi du routeur.
- Le mot de passe administrateur est connu.
- `adb` fonctionne : `adb devices` liste le téléphone.

Pour observer les journaux sans exposer de secret :

```bash
adb logcat -s WifiAdmin:V AndroidRuntime:E
```

Aucun mot de passe, jeton ou identifiant de session ne doit apparaître dans les
journaux.

---

## A. Dernier instantané

### A1. Le cache est écrit après une lecture réelle

1. Ouvre l'application, connecte-toi.
2. Attends que le tableau de bord affiche les compteurs et la liste des
   appareils.
3. Ouvre **Appareils**, puis reviens au tableau de bord.
4. Vérifie la présence du fichier (build debug) :

```bash
adb shell run-as dev.wifiadmin.app ls -l no_backup
```

Attendu : `last-device-snapshot.json` existe.

### A2. Aucun secret dans le fichier

```bash
adb shell run-as dev.wifiadmin.app cat no_backup/last-device-snapshot.json \
  | grep -i -E "password|passwd|token|session|challenge|cookie"
```

Attendu : **aucune sortie**. Ne copie pas le contenu du fichier dans un ticket
ou un message : il contient les MAC, IP et noms des appareils du foyer.

### A3. Le cache n'est pas sauvegardé dans le cloud

```bash
adb backup -noapk dev.wifiadmin.app
```

Attendu : l'archive ne contient pas `last-device-snapshot.json`
(`android:allowBackup="false"`).

### A4. Affichage après authentification

1. Force l'arrêt de l'application :

```bash
adb shell am force-stop dev.wifiadmin.app
```

2. Rouvre l'application et reconnecte-toi.
3. Observe le tableau de bord juste après la connexion.

Attendu : les dernières données connues s'affichent immédiatement, avec le
bandeau :

> **Dernières données connues — actualisées le [date et heure].**

Puis la première lecture réussie remplace le bandeau par les données du moment.
Si le routeur répond trop vite pour l'observer, utilise A5.

### A5. Échec de synchronisation avec cache

1. Connecte-toi et attends une lecture réussie.
2. Coupe le Wi-Fi du téléphone (ou active le mode avion) **après** l'affichage.
3. Reviens sur **Tableau de bord** ou **Appareils** et appuie sur
   **Actualiser**.

Attendu :

- les appareils restent affichés ;
- un bandeau indique que la synchronisation a échoué et rappelle la date des
  dernières données ;
- les badges ne disent **jamais** « Actif » : ils disent « Vu actif » /
  « Vu filaire » ;
- le compteur « Wi-Fi actif » devient « Wi-Fi (lecture) » ;
- l'application **ne revient pas** à l'écran de connexion.

### A6. Déconnexion volontaire

1. Réglages → **Se déconnecter**.
2. Vérifie le fichier :

```bash
adb shell run-as dev.wifiadmin.app ls -l no_backup
```

Attendu : `last-device-snapshot.json` a disparu.

3. Reconnecte-toi : aucun bandeau de cache ne doit apparaître.

### A7. Changement de compte

1. Connecte-toi avec un compte, laisse une lecture réussie s'écrire.
2. Déconnecte-toi (le fichier est supprimé).
3. Reconnecte-toi avec un **autre nom d'utilisateur** (si le routeur en a un).

Attendu : aucune donnée de l'ancien compte n'est affichée. Le fichier d'un
autre contexte est supprimé à la restauration.

---

## B. Expiration de session

### B1. Provoquer une expiration réelle

Le firmware décide lui-même de la durée de vie d'une session. Deux moyens
**sans écrire sur le routeur** :

- laisser l'application inactive assez longtemps pour que la session expire,
  puis appuyer sur **Actualiser** ;
- si l'interface web du routeur propose une déconnexion administrateur,
  l'utiliser depuis un navigateur, puis revenir à l'application et appuyer sur
  **Actualiser**.

> La durée exacte et l'effet d'une déconnexion web sur la session de
> l'application ne sont pas garantis par la documentation du firmware. Note le
> comportement observé pour la prochaine mission.

### B2. Comportement attendu

Attendu, dans cet ordre :

1. l'application revient à l'écran de connexion ;
2. le message exact s'affiche :
   **« Votre session a expiré. Reconnectez-vous pour continuer. »** ;
3. aucun appareil authentifié n'est visible ;
4. le bouton **Retour** ne ramène **pas** au tableau de bord : il quitte
   l'application (ou reste sur la connexion) ;
5. une reconnexion manuelle fonctionne ;
6. après reconnexion, le dernier instantané est de nouveau disponible, avec
   son bandeau, puis remplacé par une lecture fraîche.

Vérifie aussi qu'aucune boucle ne se produit : l'écran de connexion ne doit pas
clignoter ni se recharger tout seul. Les journaux ne doivent montrer qu'**une**
tentative de renouvellement au maximum par lecture.

### B3. Ce qui ne doit PAS être une expiration

| Test | Manipulation | Attendu |
|---|---|---|
| Mauvais mot de passe | Saisir un mot de passe faux | « Identifiants refusés par le routeur. », on reste sur la connexion. |
| Wi-Fi perdu | Mode avion, puis Actualiser | « Le routeur est injoignable… », on reste sur les écrans authentifiés, cache conservé. |
| Routeur éteint | Éteindre le routeur (hors application), puis Actualiser | Même comportement qu'une perte de Wi-Fi, **jamais** le message d'expiration. |
| Gel temporaire | Enchaîner plusieurs mauvais mots de passe | Message de cooldown avec le délai, aucune navigation. |
| Réponse malformée | Non provoquable sans serveur de test | L'application signale une réponse inattendue, sans expiration. |

### B4. Vérifier l'absence de fuite après expiration

Après le retour à la connexion, le fichier peut rester sur le disque
(l'expiration ne supprime pas un instantané valide). En revanche, **aucune**
donnée ne doit être visible dans l'interface avant la reconnexion.

```bash
adb shell run-as dev.wifiadmin.app ls -l no_backup
```

Le fichier peut être présent ; c'est attendu. La déconnexion volontaire (A6)
est ce qui le supprime.

---

## C. Non-régression rapide

- Tableau de bord : compteurs, dernière lecture, notice de filtrage.
- Appareils : recherche, filtres Tous / Wi-Fi / Filaire, fiche détail.
- Réglages : nom d'utilisateur, version, déconnexion.
- Thème clair et thème sombre.
- Rotation de l'écran : l'état ne doit pas être perdu de façon incohérente.
- Aucun bouton **Bloquer** / **Débloquer** nulle part.

---

## D. Ce qu'il faut noter

Pour chaque test : réussi / échoué, capture d'écran si possible, et l'heure.
Signale en particulier :

- le délai réel d'expiration observé ;
- si une déconnexion depuis l'interface web invalide la session de
  l'application ;
- toute différence entre le message attendu et le message affiché ;
- tout retour en arrière possible vers un écran authentifié.
