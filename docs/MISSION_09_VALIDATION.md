# Mission 09 — Validation sur téléphone

> Procédure de test de la Mission 09 (rafraîchissement automatique et
> changements de réseau), **plus** les deux contrôles visuels de la Mission 08
> qui restent ouverts.
>
> Aucune commande n'est envoyée au routeur par cette procédure : elle ne
> modifie **aucun** réglage du routeur.

---

## 1. Préparation

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"

adb devices                                    # le téléphone doit être listé
./gradlew :app:installDebug
adb logcat -c
adb logcat -s WifiAdmin:V AndroidRuntime:E     # dans un autre terminal
```

> Aucun mot de passe, jeton, `sessionId` ni challenge ne doit apparaître
> dans les journaux. L'application n'écrit aucun log : c'est vérifié par
> recherche exhaustive dans le code.

---

## 2. Contrôles visuels de la Mission 08 (à conserver)

Ces deux points sont **restés ouverts** depuis la Mission 08. Ils doivent
être faits, et leur résultat noté.

### 2.1 Message d'expiration affiché

1. Connectez-vous au routeur et laissez une lecture réussir.
2. Provoquez l'expiration : inactivité assez longue pour que le firmware
   expire la session, ou déconnexion administrateur depuis l'interface web
   du routeur (sans modifier de réglage).
3. Appuyez sur **Actualiser**.

**Attendu** — l'écran de connexion s'affiche avec le message **exact** :

> **Votre session a expiré. Reconnectez-vous pour continuer.**

- [ ] Le message est exactement cette phrase, sans reformulation.
- [ ] Aucun compteur d'appareils, aucune MAC, aucune IP n'est visible.
- [ ] L'application ne revient pas seule à l'écran de connexion : **seule**
      l'expiration confirmée le déclenche.

### 2.2 Bouton Retour

1. Sur l'écran de connexion obtenu en 2.1, appuyez sur **Retour**.

**Attendu** :

- [ ] Le tableau de bord n'est **jamais** ré-ouvert.
- [ ] L'application se ferme (pile de navigation vide).
- [ ] Après relance (`adb shell monkey -p dev.wifiadmin.app 1`), l'application
      démarre sur l'écran de connexion, pas sur le tableau de bord.

```bash
adb shell input keyevent KEYCODE_BACK
adb shell monkey -p dev.wifiadmin.app 1
```

---

## 3. Fonctionnalité A — Rafraîchissement automatique

### 3.1 Une lecture par période

1. Connectez-vous et ouvrez le **Tableau de bord**.
2. Notez l'heure de la dernière lecture affichée.
3. Attendez **30 à 35 secondes**.

**Attendu** :

- [ ] les compteurs et la liste se mettent à jour ;
- [ ] **aucun** indicateur de chargement envahissant n'apparaît ;
- [ ] l'écran ne scintille pas, ne se réinitialise pas.

### 3.2 Une seule lecture pour deux écrans

1. Ouvrez le **Tableau de bord**, puis l'onglet **Appareils** rapidement.
2. Attendez 30 secondes.

**Attendu** :

- [ ] la liste des appareils est **identique** à celle du tableau de bord ;
- [ ] les deux écrans ne se rafraîchissent pas décalés (ils partagent la
      même lecture).

> La preuve automatique est dans `ReadCoordinatorTest` : deux lectures
> concurrentes n'atteignent l'adaptateur qu'**une fois**.

### 3.3 Le rafraîchissement manuel ne double rien

1. Sur le tableau de bord, appuyez sur **Actualiser** plusieurs fois
   rapidement.

**Attendu** :

- [ ] le bouton est désactivé pendant la lecture ;
- [ ] le routeur n'est **pas** sollicité plusieurs fois.

### 3.4 Arrêt hors écran

1. Sur le tableau de bord, appuyez sur le bouton **Accueil** du téléphone
   (l'application passe en arrière-plan).
2. Attendez **2 minutes**.
3. Revenez sur l'application.

**Attendu** :

- [ ] aucune lecture n'a eu lieu pendant l'absence (le routeur n'a pas été
      sollicité) ;
- [ ] au retour, **une seule** lecture a lieu : les données sont à jour.

> Contrôle objectif : le compteur de requêtes du faux routeur local, ou le
> journal `adb logcat` sur un routeur de test.

### 3.5 Rotation de l'écran

1. Sur le tableau de bord, faites pivoter l'appareil.

**Attendu** :

- [ ] les données restent affichées ;
- [ ] **aucune** lecture supplémentaire (les données sont encore fraîches) ;
- [ ] l'application ne se fige pas.

### 3.6 Erreurs réseau

1. Connectez-vous, attendez une lecture réussie.
2. Passez en **mode avion**, puis appuyez sur **Actualiser**.

**Attendu** :

- [ ] un bandeau indique que la synchronisation a échoué et rappelle la date
      des dernières données ;
- [ ] les derniers appareils **restent affichés** ;
- [ ] l'application **ne revient pas** à l'écran de connexion ;
- [ ] les badges ne disent pas « Actif » mais « Vu actif » ;
- [ ] le compteur « Wi-Fi actif » devient « Wi-Fi (lecture) ».

---

## 4. Fonctionnalité B — Changements de réseau

### 4.1 Perte du Wi-Fi

1..Connectez-vous et attendez une lecture réussie.
2. **Coupez le Wi-Fi** du téléphone (mode avion), puis appuyez sur
   **Actualiser**.

**Attendu** :

- [ ] un bandeau **« Le réseau a changé. Le routeur n'est plus joignable
      ici. »** s'affiche ;
- [ ] les dernières données restent visibles ;
- [ ] **aucune** navigation vers la connexion ;
- [ ] **aucun** message d'expiration.

### 4.2 Retour du Wi-Fi

1. Rétablissez le Wi-Fi sur le réseau du routeur.
2. Attendez 30 secondes, ou appuyez sur **Actualiser**.

**Attendu** :

- [ ] le bandeau disparaît ;
- [ ] les données redeviennent fraîches ;
- [ ] aucune reconnexion n'est demandée : **le message d'expiration ne doit
      jamais apparaître** pour un simple changement de réseau.

### 4.3 Bascule vers les données mobiles

1. Connectez-vous au Wi-Fi du routeur, puis **désactivez le Wi-Fi** en
   leaving les données mobiles actives.

**Attendu** :

- [ ] le comportement est celui de 4.1 : les lectures sont suspendues, la
      session n'est pas invalidée ;
- [ ] le message d'expiration n'apparaît **jamais**.

### 4.4 Changement vers un autre routeur

1. Connectez-vous au routeur A et attendez une lecture réussie.
2. Connectez-vous au Wi-Fi d'un **autre** routeur (B), de préférence avec
   une passerelle différente.

**Attendu** :

- [ ] le bandeau « Le réseau a changé » s'affiche ;
- [ ] les appareils du routeur A **restent** affichés comme dernières données
      connues, mais ne sont **jamais** présentés comme ceux du routeur B ;
- [ ] aucune donnée du routeur A n'est réécrite dans le cache du routeur B.

### 4.5 Après reconnexion sur le bon réseau

1. Retournez sur le Wi-Fi du routeur A et reconnectez-vous.

**Attendu** :

- [ ] le bandeau « Dernières données connues — actualisées le [date]. »
      apparaît brièvement ;
- [ ] il est remplacé par les données fraîches.

---

## 5. Persistance du fichier d'instantané

Le répertoire s'appelle `no_backup` sur le disque. `run-as` change le
répertoire courant vers celui de l'application.

```bash
# Présence / absence, sans afficher le contenu
adb shell run-as dev.wifiadmin.app test -f no_backup/last-device-snapshot.json \
  && echo "PRESENT" || echo "ABSENT"

# Taille seule, sans lire le fichier
adb shell run-as dev.wifiadmin.app stat -c '%s' no_backup/last-device-snapshot.json

# Permissions : le fichier ne doit être lisible que par l'application
adb shell run-as dev.wifiadmin.app ls -l no_backup/last-device-snapshot.json

# Aucun fichier temporaire résiduel
adb shell run-as dev.wifiadmin.app ls -A no_backup | grep -F '.tmp' \
  && echo "KO: temporaire résiduel" || echo "OK: pas de temporaire"

# Aucun secret dans le fichier (le résultat doit être vide)
adb shell run-as dev.wifiadmin.app cat no_backup/last-device-snapshot.json \
  | grep -i -c -E "password|passwd|token|session|challenge|cookie"
# attendu : 0
```

> **Ne copiez jamais le contenu du fichier** dans un ticket ou un message : il
> contient les MAC, les IP et les noms des appareils du foyer.

### 5.1 Au changement de réseau

- [ ] Le fichier **reste** : l'expiration et le changement de réseau ne
      suppriment pas un instantané valide.
- [ ] Après reconnexion sur un **autre** routeur, le fichier affiché est
      celui du nouveau contexte, jamais celui de l'ancien.

### 5.2 À la déconnexion volontaire

1. Réglages → **Se déconnecter**.

- [ ] Le fichier **disparaît** (`ABSENT`).

---

## 6. Tableau des comportements attendus selon l'état du réseau

| État du réseau | Bandeau | Données affichées | Navigation | Message |
|---|---|---|---|---|
| Wi-Fi du routeur, lecture réussie | aucun | fraîches | aucune | aucun |
| Wi-Fi du routeur, lecture échouée | « Synchronisation impossible… » | dernières connues | aucune | « Le routeur est injoignable… » |
| Wi-Fi du routeur, réponse incomplète | « Réponse incomplète du routeur… » | dernières connues | aucune | aucun |
| **Wi-Fi perdu** | **« Le réseau a changé… »** | dernières connues | **aucune** | aucun |
| **Données mobiles** | **« Le réseau a changé… »** | dernières connues | **aucune** | aucun |
| **Autre routeur** | **« Le réseau a changé… »** | dernières connues | **aucune** | aucun |
| Cache restauré après reconnexion | « Dernières données connues — actualisées le… » | anciennes, datées | aucune | aucun |
| **Session expirée** | aucun (écran de connexion) | **aucune** | **retour à la connexion** | **« Votre session a expiré. Reconnectez-vous pour continuer. »** |
| Routeur en cooldown | « Synchronisation impossible… » | dernières connues | aucune | « Trop de tentatives… » |

---

## 7. Ce qu'il faut noter

Pour chaque point : réussi / échoué, capture d'écran si possible, et l'heure.
Signale en particulier :

- le nombre de lectures observées sur 30 secondes (il doit être **un**) ;
- toute lecture en arrière-plan ;
- toute apparition du message d'expiration hors d'une expiration réelle ;
- toute incohérence entre le tableau de bord et la liste des appareils ;
- toute divergence entre le bandeau affiché et le tableau ci-dessus.
