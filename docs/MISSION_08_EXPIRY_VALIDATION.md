# Mission 08 — Validation du scénario d'expiration de session

> Procédure **reproductible** et **déterministe**. Elle ne modifie pas le
> routeur réel et ne dépend d'aucune temporisation du firmware.

---

## 1. Pourquoi une simulation est nécessaire

Une expiration de session ne peut pas être provoquée à volonté sur le routeur
réel : c'est le firmware qui décide de la durée de vie d'une session, et le
provoquer demanderait soit d'attendre, soit d'agir sur le routeur — ce qui est
interdit.

La validation repose donc sur **trois niveaux** complémentaires :

| Niveau | Ce qu'il prouve | Commande |
|---|---|---|
| **P1 — Protocole** | Le client tente **un seul** renouvellement, distingue un renouvellement refusé d'un cooldown, et ne boucle jamais. | `./gradlew :router-core:test` |
| **P2 — Android** | L'application arrête les lectures, purge la mémoire, revient à la connexion, affiche le message exact, bloque le Retour, conserve le fichier. | `./gradlew :app:testDebugUnitTest` |
| **P3 — Bout en bout** | La chaîne HTTP+JSON réelle contre un faux routeur local : aucune écriture, un seul renouvellement. | `python3 tools/fake_router_expiry_scenarios.py` |
| **P4 — Téléphone** | Le rendu à l'écran et le comportement réel du bouton Retour. | `docs/MISSION_08_PHONE_TEST.md` §B |

> **Le P4 reste indispensable** : le comportement du bouton Retour et le rendu
> du message sont des propriétés du framework Android, pas des fonctions pures.
> Aucun test JVM ne peut les prouver.

---

## 2. Le faux routeur d'expiration

`tools/fake_router_expiry.py` reproduit la séquence du firmware :

```text
cmd 232 (challenge)  →  OK
cmd 100 (login)      →  OK        ← la session est valide
cmd 223 (lecture)    →  NO_AUTH   ← la session vient d'expirer
cmd 232 (challenge)  →  OK        ← le client renouvelle, une seule fois
cmd 100 (login)      →  refusé   ← le renouvellement échoue
                        →  le client lève RouterAuthenticationException
                        →  l'app le traduit en SessionExpired
```

Les modes disponibles (variable d'environnement `FAKE_EXPIRY_MODE`, ou
en-tête `X-Fake-Expiry-Mode`) :

| Mode | Comportement | Ce qu'il prouve |
|---|---|---|
| `renew-refused` | Le renouvellement est refusé | **C'est l'expiration.** |
| `renew-ok` | Le renouvellement réussit | Ce n'est **pas** une expiration : la lecture est rejouée. |
| `renew-cooldown` | Le challenge annonce la pénalité de 180 s | Le gel garde **son propre** message. |
| `expire-after=N` | Les N premières lectures réussissent | L'expiration survient après N lectures. |

> **Piège à connaître.** Une implémentation naïve — « faire échouer le
> `cmd 100` » — produit un **cooldown**, pas une expiration : un `success: true`
> sans `sessionId` ni `message: "success"` est interprété par le client comme un
> refus global de connexion. Le faux routeur utilise donc `login_fail: "fail"`,
> qui est bien la forme du firmware pour un refus de renouvellement.

Le serveur ne compte **que** des commandes de lecture. Toute autre commande est
refusée et comptée : le contrôle « aucune écriture » est donc automatique.

---

## 3. Exécution

### 3.1 Les trois niveaux automatisés

```bash
cd /home/ismael/Documents/wifi-admin/wifi-admin-app

# P1 + P2 : toute la suite, hors ligne
./gradlew :router-core:test :poc-cli:test :app:testDebugUnitTest --offline --rerun-tasks

# P3 : la chaîne HTTP+JSON de bout en bout
./gradlew :poc-cli:installDist --offline
python3 tools/fake_router_expiry_scenarios.py

# Non-régression : les scénarios de pare-feu existants
python3 tools/fake_router_scenarios.py
```

Attendu :

```text
TOTAL tests=375 failures=0
[ok ] renew-refused    expects 'authentication'
[ok ] renew-ok         expects 'sample-phone'
[ok ] renew-cooldown   expects 'cooldown'
every expiry scenario behaved as expected, and no write was attempted
all scenarios behaved as expected, and no write was attempted
```

### 3.2 Les tests pivots

| Test | Prouve |
|---|---|
| `router-core` · `a refused renewal is attempted exactly once` | Le renouvellement est tenté **exactement une fois** (2 `cmd 100` : l'initial + le renouvellement). |
| `router-core` · `a refused renewal does not repeat the read` | La lecture n'est **pas** rejouée après un renouvellement refusé. |
| `router-core` · `a renewal blocked by the penalty window is a cooldown, not an expiry` | Le gel de 180 s n'est **pas** une expiration. |
| `router-core` · `the credentials are not sent during the penalty window` | Les identifiants ne partent **jamais** pendant la pénalité. |
| `app` · `an expiry stops the reads without reaching the adapter again` | Après l'expiration, plus aucune lecture ne part. |
| `app` · `an expiry clears the snapshot from memory` | Plus aucune donnée authentifiée en mémoire. |
| `app` · `the login screen reports the expiry` | L'écran de connexion reçoit le résultat d'expiration. |
| `app` · `the expiry message is the exact sentence` | La phrase exacte, mot pour mot. |
| `app` · `the back stack is emptied so Back cannot reach a protected screen` | Les trois écrans protégés sont couverts. |
| `app` · `an expiry keeps the snapshot file for the next login` | Le fichier survit à l'expiration. |
| `app` · `a voluntary logout deletes the snapshot file` | Le fichier disparaît à la déconnexion. |
| `app` · `a timeout is not an expiry` | Un timeout ne déconnecte pas. |
| `app` · `a lost wifi connection is not an expiry` | Une perte de Wi-Fi ne déconnecte pas. |
| `app` · `a temporary cooldown keeps its own error and does not navigate` | Le gel garde son état d'erreur. |
| `app` · `two simultaneous expiries produce a single expiry state` | Une seule navigation, pas de lecture supplémentaire. |
| `app` · `a tab navigation at the instant of the expiry cannot stack a protected screen` | La course avec la barre d'onglets est couverte. |

---

## 4. Vérification sur le téléphone

Le téléphone sert à deux choses que le JVM ne peut pas prouver : le **rendu
réel** du message et le **bouton Retour**.

### 4.1 Préparation

```bash
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"

adb devices                                    # le téléphone doit être listé
./gradlew :app:installDebug
```

### 4.2 Contrôle du fichier d'instantané (sans jamais afficher son contenu)

Le répertoire s'appelle `no_backup` sur le disque (et non `noBackupFilesDir`).
`run-as` change le répertoire courant vers celui de l'application.

```bash
# Présence / absence, sans afficher le contenu
adb shell run-as dev.wifiadmin.app test -f no_backup/last-device-snapshot.json \
  && echo "PRESENT" || echo "ABSENT"

# Taille seule, sans lire le fichier
adb shell run-as dev.wifiadmin.app stat -c '%s' no_backup/last-device-snapshot.json

# Permissions (le fichier ne doit être lisible que par l'application)
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

### 4.3 Déroulé du scénario d'expiration

1. **Connexion** réelle au routeur → le tableau de bord affiche les compteurs.
2. **Contrôle** : le fichier est `PRESENT`, permissions `-rw-------`.
3. **Provoquer l'expiration** — deux moyens, aucun ne modifie le routeur :
   - laisser l'application inactive assez longtemps pour que le firmware
     expire la session, puis appuyer sur **Actualiser** ;
   - si l'interface web du routeur permet de fermer la session administrateur,
     le faire depuis un navigateur, puis revenir dans l'application et appuyer
     sur **Actualiser**.
4. **Attendu**, dans cet ordre :
   1. retour automatique à l'écran de connexion ;
   2. message **exact** : « Votre session a expiré. Reconnectez-vous pour continuer. » ;
   3. **aucun** appareil, aucun compteur, aucune donnée authentifiée visible ;
   4. le bouton **Retour** ne rouvre **jamais** le tableau de bord ;
   5. **aucune boucle** : l'écran ne clignote pas, aucune requête automatique.
5. **Contrôle** : le fichier est toujours `PRESENT` (l'expiration ne l'efface pas).
6. **Reconnexion** manuelle → le bandeau « Dernières données connues — actualisées
   le [date et heure]. » apparaît, puis est remplacé par la lecture fraîche.
7. **Déconnexion volontaire** (Réglages) → le fichier devient `ABSENT`.

### 4.4 Tests négatifs sur téléphone

| Test | Manipulation | Attendu |
|---|---|---|
| Mauvais mot de passe | Saisir un mot de passe faux | « Identifiants refusés par le routeur. », on reste sur la connexion. |
| Wi-Fi perdu | Mode avion, puis **Actualiser** | « Le routeur est injoignable… », on **reste** sur les écrans authentifiés, le cache est conservé. |
| Routeur éteint | Éteindre le routeur, puis **Actualiser** | Idem, **jamais** le message d'expiration. |
| Gel temporaire | Enchaîner des mots de passe faux | Message de cooldown avec le délai, aucune navigation. |

### 4.5 Contrôle des journaux

```bash
adb logcat -c
adb logcat -s WifiAdmin:V AndroidRuntime:E
```

Attendu : **aucun** mot de passe, jeton, `sessionId` ni challenge dans les
journaux. En pratique, l'application n'écrit **aucun** log : c'est vérifié
par recherche exhaustive dans le code (`grep -rn "android.util.Log\|Log\." app/src/main/`).

---

## 5. Ce qui reste hors de portée d'une validation automatique

| Point | Pourquoi | Qui le vérifie |
|---|---|---|
| Le bouton Retour | Propriété de `navigation-compose` 2.9.0, pas une fonction pure | Test téléphone (§4.3 étape 4) |
| Le rendu du message | Nécessite une interface Compose | Test téléphone (§4.3 étape 2) |
| La durée réelle d'une session | Décidée par le firmware | Test téléphone (§4.3 étape 3) |
| Le chemin et les permissions réels | `noBackupFilesDir` n'existe qu'à l'exécution | `adb run-as` (§4.2) |

Ces quatre points sont **documentés** dans `docs/MISSION_08_REPORT.md` et
`docs/ANDROID_ARCHITECTURE.md`.
