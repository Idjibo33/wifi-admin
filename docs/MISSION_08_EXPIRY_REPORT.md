# Mission 08 — Rapport de validation du scénario d'expiration de session

> Date : 05/11/2026 · Suite de la livraison de la Mission 08 (commit `2a557c6`)
> Périmètre : validation du **dernier scénario** restant. Aucune écriture sur
> le routeur réel. Mission 09 non commencée.

---

## 1. Contexte

Les cinq premiers scénarios de la Mission 08 ont été validés sur le téléphone
réel par l'utilisateur : connexion, persistance de l'instantané, perte de
connexion, actualisation après reconnexion, déconnexion volontaire.

Il restait le **scénario d'expiration de session**. Ce scénario ne peut pas
être provoqué à volonté sur le routeur réel : c'est le firmware qui expire
une session, et le provoquer demanderait d'agir sur le routeur — ce qui est
interdit. La validation a donc reposé sur une **simulation déterministe**,
validée par quatre sous-agents en parallèle.

---

## 2. Rapports des sous-agents

### 2.1 — Android : cycle de vie des ViewModels et de la navigation

**Verdict : les 6 exigences sont CONFORMES**, vérifiées par lecture du code
**et par désassemblage du bytecode** de `navigation-runtime` 2.9.0 (les
framework jars du cache Gradle), et non par mémoire.

| Exigence | Verdict | Preuve |
|---|---|---|
| Arrêt des lectures | CONFORME | 3 verrous : adaptateur détaché, `generation++`, garde d'état |
| Effacement des identifiants de session | CONFORME | `sessionId = null` (`invalidateSession()`), snapshot mémoire effacé |
| Retour automatique à la connexion | CONFORME | `LaunchedEffect` + `shouldReturnToLogin` |
| Message exact | CONFORME | chaîne exacte ; **réserve** : elle était dupliquée et la copie affichée n'était pas testée |
| Retour inaccessible aux écrans protégés | CONFORME | `popUpTo(graph.id){inclusive=true}` → pile = `[login]` ; **non couvert** par un test JVM |
| Instantané conservé | CONFORME | `expireSession()` ne touche pas au store |

**Constats structurants retenus :**

1. **La clé du `LaunchedEffect` est stable** : `SessionExpired` est un
   `data object`, donc `Composer.changed()` (égalité structurelle) ne
   re-déclenche pas l'effet. **Aucune boucle de navigation** n'est possible.
   Vérifié par bytecode, pas par supposition.
2. **`connect()` n'est jamais appelé automatiquement** : le seul appelant est
   le bouton « Se connecter ». La restauration du cache ne peut pas ressusciter
   une session : elle a lieu **à l'intérieur** de `connect()`, après
   acceptation des identifiants.
3. **Les ViewModels sont détruits** au pop de la pile :
   `NavControllerViewModel.clear()` → `ViewModelStore.clear()` → `onCleared()`
   et annulation du `viewModelScope` (offsets 283-311 du bytecode de
   `NavControllerImpl`).
4. **Faiblesses relevées** : la sécurité de l'expiration reposait sur la
   navigation et non sur les ViewModels ; une navigation par onglet pouvait
   laisser l'utilisateur sur un écran authentifié ; le message pouvait ne
   jamais s'afficher si le `LoginViewModel` préexistait à l'expiration.

### 2.2 — Protocole : erreurs d'authentification et renouvellement

**Constat central, confirmé empiriquement** (sonde Kotlin hors dépôt contre
les classes compilées) :

> L'affirmation « une `RouterAuthenticationException` pendant `fetchDevices()`
> ne peut être qu'une expiration » est **réfutable dans `router-core`** (la
> même exception couvre le mauvais mot de passe et le renouvellement raté) et
> **vraie dans `app`** (une lecture ne porte aucun mot de passe ; la
> distinction se fait par le **contexte d'appel**, pas par le type).

**Séquence exacte observée** : `232, 100, 223, 232, 100` — le renouvellement
est tenté **une seule fois**, jamais deux, jamais en boucle.

**Pièges identifiés :**

| Piège | Conséquence |
|---|---|
| Le renouvellement peut lever `RouterCooldownException` | Sans traitement dédié, une expiration serait signalée à tort. L'app le distingue (`ZltRouterAdapter` : `Cooldown` ≠ `SessionExpired`). |
| `isSessionExpired()` ne regarde que `message`, **sensible à la casse** | `"no_auth"` ne serait pas détecté. Le faux routeur doit envoyer `NO_AUTH` en majuscules. |
| `success:false` seul **ne suffit pas** | Il faut le champ `message`. |
| Un HTTP 401 donne `{"success":false,"error":"HTTP 401"}` | Ce n'est **pas** une expiration mais une réponse/protocole inattendue. |
| `sendRead` réessaie **une fois** en cas d'erreur réseau | Le pire cas est 4 requêtes pour une commande, **sans boucle possible**. |
| Les identifiants ne partent **jamais** pendant la pénalité | Vérifié : la fenêtre est contrôlée **avant** l'envoi du `cmd 100`. |

**Verdict : le renouvellement unique et la distinction cooldown/expiration sont
conformes.** Le manque principal : **aucun test `router-core` ne prouvait** la
séquence « session OK → `NO_AUTH` → renouvellement refusé ». Corrigé (voir §4).

### 2.3 — QA : test déterministe reproductible

**Corrections factuelles à l'énoncé de la mission** (vérifiées dans le code) :

- le chemin CGI est **`/cgi-bin/http.cgi`**, pas `/cgi-bin/luci/...` ;
- l'adresse du routeur vient de la **passerelle lue dans `LinkProperties`**,
  pas de la table de routage noyau ;
- le faux routeur existant **ne savait pas** simuler une expiration.

**Analyse des quatre niveaux de test** — le choix retenu (P1..P4 ci-dessus)
laisse **deux propriétés** à la validation téléphone, parce qu'elles ne sont
pas exprimables en JVM pur : le **bouton Retour** (propriété de
`navigation-compose`) et le **rendu effectif** du message.

**Point de conception le plus important du rapport** : une implémentation naïve
du scénario « la session expire » (faire échouer le `cmd 100`) produit un
**cooldown**, pas une expiration. Le faux routeur utilise donc
`login_fail: "fail"`, la forme réelle du firmware.

**Chemin `run-as` vérifié** : le répertoire s'appelle `no_backup` sur le
disque, et les permissions observées sont `-rw-------` (application seule).
Le fichier d'instantané a été retrouvé sur le téléphone réel (2 068 octets,
`-rw-------`, aucun fichier `.tmp` résiduel, **0** occurrence de champ de
secret).

### 2.4 — Sécurité : boucle de connexion et accès aux écrans protégés

**10 contrôles de sécurité, 10 conformes.** Points saillants :

- **Pas de boucle** : clé stable + `connect()` jamais automatique + garde
  `currentRoute != login`.
- **Le Retour ne peut pas atteindre un écran protégé** : prouvé par bytecode
  (`popBackStackInternal` → `executePopOperations` ; `NavControllerImpl.setGraph`
  prouve que le graphe racine est bien dans la pile).
- **Aucun secret en fichier ni en log** : liste blanche de clés structurelle,
  et **zéro instruction de log** dans toute l'application et dans `router-core`.
- **Le fichier n'est supprimé qu'à la déconnexion volontaire** (et au
  changement de contexte) : inventaire exhaustif des 2 appels à `clear()`.

**3 défauts réels trouvés**, tous corrigés (voir §3) :

| # | Défaut | Gravité |
|---|---|---|
| F1 | Une navigation par onglet dispatchée à l'instant de l'expiration pouvait empiler un écran protégé | MOYENNE |
| F2 | Les ViewModels conservaient la liste des appareils pendant la fenêtre précédant le pop | FAIBLE |
| F3 | Le message pouvait ne jamais s'afficher si le `LoginViewModel` préexistait à l'expiration | FAIBLE |

---

## 3. Corrections apportées

Les trois défauts ont été corrigés, chacun avec son test de non-régression.

| # | Correction | Fichiers |
|---|---|---|
| **F1** | La navigation est keyée sur `(connection, route)` **et** une règle de sécurité `shouldLeaveProtectedScreen` rattrape un écran protégé sans session valide. Elle ne se déclenche que sur une **expiration confirmée** : un timeout ou une perte de Wi-Fi ne doivent **jamais** renvoyer l'utilisateur à la connexion. | `SessionNavigation.kt`, `WifiAdminNavHost.kt` |
| **F2** | `DashboardViewModel` et `DevicesViewModel` **observent** l'état du repository et purgent leur snapshot / leur liste dès que la session n'est plus valide. La sécurité ne repose plus sur la seule navigation. | `DashboardViewModel.kt`, `DevicesViewModel.kt` |
| **F3** | `LoginViewModel` **observe** l'état du repository (et lit la valeur courante en plus) : le message s'affiche même si l'écran de connexion était déjà monté. | `LoginViewModel.kt` |
| **G5** | La phrase exacte est **centralisée** dans `EXPIRY_MESSAGE`, utilisée à l'identique par l'écran de connexion et par les messages d'erreur. Un test de contrat verrouille le texte affiché. | `Presentation.kt`, `LoginScreen.kt` |

---

## 4. Tests ajoutés

| Fichier | Tests | Ce qu'ils prouvent |
|---|---:|---|
| `router-core/.../ZltRouterClientRenewalTest.kt` | 10 | Le renouvellement est tenté **exactement une fois**, la lecture n'est pas rejouée après un refus, le cooldown n'est pas une expiration, les identifiants ne partent pas pendant la pénalité, aucun secret dans le message. |
| `app/.../ui/SessionExpiryFlowTest.kt` | 25 | La chaîne Android complète : arrêt des lectures, purge mémoire, retour à la connexion, message exact, blocage du Retour, conservation du fichier, suppression à la déconnexion, et les 4 tests négatifs (timeout, Wi-Fi, cooldown, réponse malformée). |
| `app/.../ui/login/LoginMessageContractTest.kt` | 5 | La phrase exacte, et le fait que l'écran de connexion ne peut pas porter une autre formulation. **Vérifié** : le test échoue bien si la phrase est altérée. |
| `app/.../navigation/SessionNavigationTest.kt` | +5 | La règle de sécurité de F1 et la couverture des trois écrans protégés. |
| `tools/fake_router_expiry.py` | — | Le faux routeur d'expiration, avec 4 modes. |
| `tools/fake_router_expiry_scenarios.py` | 3 | La chaîne HTTP+JSON de bout en bout, sans écriture. |

---

## 5. Résultats de la vérification

```text
router-core  tests=193  failures=0
poc-cli      tests=56   failures=0
app          tests=121  failures=0
TOTAL        tests=375  failures=0
```

| Scénario hors ligne | Résultat |
|---|---|
| `tools/fake_router_scenarios.py` | 10/10, aucune écriture |
| `tools/fake_router_expiry_scenarios.py` | 3/3, aucune écriture |

---

## 6. Vérification sur le téléphone réel

Effectuée avec les commandes de `docs/MISSION_08_EXPIRY_VALIDATION.md` §4.

| Contrôle | Résultat |
|---|---|
| Fichier d'instantané présent après sauvegarde | **PRESENT** |
| Permissions | `-rw-------` (application seule) |
| Fichier temporaire `.tmp` résiduel | **Aucun** |
| Champs de secret dans le fichier | **0 occurrence** |
| Chemin réel | `no_backup/last-device-snapshot.json` |

> Les contrôles ci-dessus sont **des observations d'état de fichier**. Le
> rendu du message et le comportement du bouton Retour relèvent du §4.3 de la
> procédure et doivent être constatés par l'utilisateur : ce sont des
> propriétés du framework Android, hors de portée d'un test JVM.

---

## 7. Verdict final

| Critère | Verdict |
|---|---|
| Expiration **confirmée** distinguée des erreurs réseau | **CONFORME** |
| Arrêt des lectures après expiration | **CONFORME** |
| Identifiants de session effacés en mémoire | **CONFORME** |
| Retour automatique à l'écran de connexion | **CONFORME** |
| Message exact affiché | **CONFORME** (texte verrouillé par test) |
| Bouton Retour vers un écran protégé | **CONFORME** par analyse du bytecode ; à confirmer visuellement |
| Instantané conservé jusqu'à reconnexion / déconnexion | **CONFORME** (vérifié sur le téléphone) |
| Tests négatifs (timeout, Wi-Fi, cooldown, malformé) | **CONFORME** |
| Pas de navigation répétée | **CONFORME** |
| Boucle de reconnexion | **IMPOSSIBLE** (clé stable + `connect()` manuel) |

### Verdict : **MISSION 08 VALIDÉE**

Les six exigences de la fonctionnalité B sont satisfaites, les trois défauts
trouvés par les sous-agents ont été corrigés et couverts par des tests, et
la simulation d'expiration est **reproductible** hors ligne.

**Réserves, explicites et documentées :**

1. Le **bouton Retour** et le **rendu du message** sont vérifiés par analyse
   du bytecode de `navigation-compose` 2.9.0 et par le test automatisé de la
   règle, mais leur constat visuel reste à faire sur le téléphone (procédure
   §4.3, points 2 et 4).
2. La **durée réelle** d'une session n'est pas mesurée : elle dépend du
   firmware.
3. Un `HTTP 401` ne serait **pas** traité comme une expiration par le client ;
   le firmware 6.07.0 observé répond `HTTP 200` + `message: "NO_AUTH"`. Si un
   firmware changeait ce comportement, l'app afficherait « réponse inattendue »
   — un point de veille, pas un défaut de la Mission 08.

### Suite

La Mission 08 est **close**. La Mission 09 n'a **pas** été commencée, et aucune
action n'a été effectuée sur le routeur réel.
