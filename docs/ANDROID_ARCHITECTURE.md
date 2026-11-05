# Architecture de l'application Android — WiFi Admin

> Ce document décrit l'architecture du module `app`, son lien avec
> `router-core`, et les règles de sécurité qu'il applique.

---

## 1. Vue d'ensemble

```text
┌──────────────────────────────────────────────────────────────┐
│  app/  (Android, Kotlin, Jetpack Compose, Material 3)         │
│                                                              │
│  ui/            login · dashboard · devices · settings       │
│                   │            │            │               │
│                   └────────────┴────────────┘               │
│                                │                             │
│                        ViewModels                            │
│                     (StateFlow + coroutines)                 │
│                                │                             │
│  data/          RouterRepository ── SettingsStore            │
│                       │                                       │
│                 RouterAdapter (interface)                    │
│                       │                                       │
│                 ZltRouterAdapter                             │
└───────────────────────┼──────────────────────────────────────┘
                        │  project(":router-core")
┌───────────────────────┼──────────────────────────────────────┐
│  router-core/          ▼                                      │
│         ZltRouterClient · DeviceMerger · RouterModels        │
│                   (protocole ZLT, aucun Android)             │
└──────────────────────────────────────────────────────────────┘
```

**Le module `app` ne contient aucun code de protocole.** Il ne connaît ni les
numéros de commande, ni l'URL du CGI, ni le format des réponses. Il parle à
une interface Java ordinaire.

---

## 2. La frontière : `RouterAdapter`

```kotlin
interface RouterAdapter {
    val displayName: String
    suspend fun connect(host: String, username: String, password: String): Boolean
    suspend fun fetchDevices(): DeviceSnapshot
    fun disconnect()
}
```

`ZltRouterAdapter` est l'implémentation qui enveloppe `ZltRouterClient`. C'est
le **seul** endroit de l'application qui parle au protocole.

### Pourquoi une interface, plutôt qu'un appel direct

| Bénéfice | Conséquence |
|---|---|
| **Testabilité** | Un ViewModel se teste avec un `FakeAdapter`, sans réseau ni mock de framework. |
| **Support multi-routeur** | Ajouter un adaptateur `OpenWrtAdapter` plus tard ne touche **aucun** écran. |
| **Lisibilité du code applicatif** | Un écran dit « charge les appareils », pas « envoie cmd 223 et 224 ». |
| **Sécurité** | L'interface n'expose **aucune** opération mutante : l'application ne *peut* pas écrire sur le routeur. |

> **Point de sécurité important.** `RouterAdapter` ne déclare que des
> opérations de lecture. Les clients d'écriture de `router-core`
> (`WifiMacFilterClient.write`, `.restore`, `.disable`) ne sont **pas**
> exposés par l'adaptateur, et le module `app` n'importe même pas le package
> `dev.wifiadmin.router.wifi`. Il n'existe donc **aucun chemin de code** dans
> l'application qui puisse envoyer un `POST`.

### Les erreurs sont traduites à la frontière

Les exceptions du protocole (`RouterNetworkException`,
`RouterAuthenticationException`, `RouterProtocolException`) sont converties en
`RouterUnavailableException`, une hiérarchie de cas distincts que l'interface
utilisateur comprend :

| Exception | Signification pour l'utilisateur |
|---|---|
| `Unreachable` | Le routeur est injoignable (mauvaise adresse, Wi-Fi perdu, routeur éteint). |
| `Timeout` | Le routeur n'a pas répondu à temps. **Ce n'est pas une expiration.** |
| `AuthenticationFailed` | Identifiants refusés lors d'une **tentative de connexion**. |
| `SessionExpired` | Une session déjà ouverte n'a pas pu être renouvelée. **Ce n'est pas un mauvais mot de passe.** |
| `NotConnected` | Une lecture a été demandée sans session ouverte. |
| `Cooldown` | Le routeur refuse temporairement les connexions (gel de 180 s). |
| `UnexpectedResponse` | Le routeur a répondu quelque chose d'inattendu. |

Les écrans n'ont jamais à connaître le protocole. Une erreur réseau ne devient
**jamais** une expiration de session : seul `SessionExpired` déclenche le
retour à l'écran de connexion.

---

## 3. `RouterRepository`

Le repository est la source de vérité unique :

```kotlin
class RouterRepository(
    private val adapter: RouterAdapter = ZltRouterAdapter(),
    private val ioDispatcher: CoroutineDispatcher = Dispatchers.IO,
    private val snapshotStore: SnapshotStorage? = null,
) {
    val state: StateFlow<RouterUiState>
    suspend fun connect(host, username, password): Boolean
    suspend fun refresh(): Result<DeviceSnapshot>
    suspend fun disconnect()
}
```

Il porte :

- l'**état de connexion** (`Disconnected`, `Connecting`, `Connected`,
  `SessionExpired`, `Cooldown`, `Error`) ;
- le **dernier instantané** des appareils et son origine (`NONE`, `LIVE`,
  `CACHE`) ;
- l'**erreur** éventuelle ;
- l'**horodatage** de la dernière actualisation ;
- l'indicateur de **réponse incomplète** (`payloadAbsent`).

Un `Mutex` sérialise les opérations : deux rafraîchissements simultanés ne
peuvent pas se chevaucher, ce qui éviterait deux sessions concurrentes. Un
compteur de **génération** invalide les résultats qui ont commencé avant une
déconnexion ou une expiration : une réponse ancienne ne peut pas ressusciter
une session morte.

### Le réseau ne doit jamais tourner sur le thread principal

Le client `router-core` fait de l'**E/S bloquante**. Appeler
`login()` ou `fetchDevices()` depuis le thread principal est interdit par
Android : le système lève `NetworkOnMainThreadException`.

Cette exception est un `RuntimeException`. Si le client la traitait comme une
erreur réseau, l'interface afficherait « le routeur est injoignable » alors
que le routeur est parfaitement joignable — un message totalement trompeur.

Le remède est appliqué à deux niveaux :

| Niveau | Mesure |
|---|---|
| `RouterRepository` | Chaque appel réseau est enveloppé dans `withContext(ioDispatcher)`, avec `Dispatchers.IO` par défaut. |
| `router-core` | `NetworkOnMainThreadException` est **détectée par son nom** (pour ne pas dépendre d'Android) et signalée comme une `RouterProtocolException`, jamais comme une erreur réseau. |
| Tests | Le dispatcher est **injectable** : les tests utilisent un `StandardTestDispatcher` et restent déterministes. |

Un test dédié (`RouterRepositoryTest`) vérifie que l'appel réseau **ne s'exécute
pas** sur le thread principal.

---

## 3bis. Détection automatique de l'adresse du routeur

`RouterAddressResolver` déduit l'adresse du routeur de la **passerelle par
défaut** du réseau Wi-Fi actif :

```kotlin
LinkProperties.routes → first default route → gateway.hostAddress
```

C'est l'adresse que le téléphone utilise pour joindre l'extérieur, qui est le
routeur lui-même sur un réseau domestique. Sur l'appareil de test, la table de
routage confirme `default via 192.168.1.1 dev wlan1`.

### L'adresse n'est pas un champ de saisie

L'adresse n'est **pas modifiable** : elle est affichée en lecture seule sur
l'écran de connexion (« Routeur détecté »). La demander à l'utilisateur alors
qu'elle est connue n'apportait rien et invitait à la faute de frappe.

- Si la passerelle est lue, le champ affiche l'adresse détectée.
- Sinon, l'écran l'indique (« Routeur non détecté ») et utilise l'adresse par
  défaut `192.168.1.1` comme dernier recours.
- Les Réglages ne contiennent plus d'adresse : seul le nom d'utilisateur y est
  conservé.

### Pourquoi le BSSID a été rejeté

Une heuristique basée sur le préfixe du BSSID (l'adresse matérielle du point
d'accès) a été **implémentée puis supprimée** : sur l'appareil de test, le
BSSID est `d8:42:f7:49:c0:ff` alors que le routeur est `192.168.1.1`. Le
préfixe du BSSID n'a **aucun rapport** avec la plage IP du routeur. Proposer
une adresse erronée est pire que de n'en proposer aucune.

---

## 3ter. Une connexion refusée ne doit jamais ouvrir l'application

### Le défaut

Ce firmware répond `success: true` **même quand le mot de passe est faux** :

```json
{"cmd": 100, "login_fail": "fail", "login_times": "1", "success": true}
```

L'ancienne règle d'acceptation (héritée du client de référence) était
« `success` vrai, ou `message == "success"`, ou présence d'un `sessionId` ».
Elle acceptait donc un mot de passe erroné, et l'application passait à l'écran
suivant avec une session inutilisable.

### La règle actuelle, fermée par défaut

Une connexion n'est acceptée que si :

1. la réponse ne porte **aucun** marqueur d'échec `login_fail` ;
2. **et** elle porte un signal positif : `sessionId` non vide, ou
   `"message": "success"`.

`success: true` seul ne suffit plus. Pendant un gel, le routeur renvoie
`{"cmd": 100, "success": true}` sans session : c'est un échec, pas un succès.

### Le gel après échecs

`cmd 232` renvoie `buffer` et `netx_login_time`. Pendant la fenêtre de
pénalité de 180 s, `buffer` vaut `"0"` et `netx_login_time` compte les
**secondes écoulées** : le client n'envoie pas `cmd 100` et affiche
« encore N secondes » avec `N = 180 - netx_login_time`.

Un verrouillage plus long se manifeste par une réponse `success: true` **sans
session** : le client ne l'interprète pas comme un mot de passe erroné, mais
comme un refus temporaire, et demande de patienter.

> Règle recoupée avec l'interface web du routeur (`js/login.js`), qui ne se fie
> jamais à `success` seul.

---

## 4. Les ViewModels

Chaque écran a son ViewModel, construit par une **factory** (et non
directement dans un composable — ce que le lint Android interdit, et ce qui
ferait perdre l'état à chaque recomposition).

| ViewModel | Responsabilité |
|---|---|
| `LoginViewModel` | Saisit les identifiants, ouvre la session, rapporte le résultat. |
| `DashboardViewModel` | Déclenche et suit le rafraîchissement, expose l'instantané. |
| `DevicesViewModel` | Filtre et recherche locale sur la liste d'appareils. |
| `SettingsViewModel` | Adresse du routeur et nom d'utilisateur persistés, déconnexion. |

Ils exposent un `StateFlow` et n'utilisent que le repository. Aucun
ViewModel ne contient de référence à `Activity`, `Context` ou `View`.

---

## 5. Sécurité

### Le mot de passe

| Règle | Mise en œuvre |
|---|---|
| Jamais persisté | Le mot de passe vit dans un état local du composable, est transmis au ViewModel, puis au repository, et n'est stocké nulle part. |
| Jamais dans un état de ViewModel | `LoginUiState` ne contient pas de champ « mot de passe ». Le test `the password is never stored in the state` le vérifie. |
| Jamais journalisé | Aucune instruction de log dans le module `app`. |
| Jamais affiché | Le champ est masqué par défaut, avec une option pour l'afficher momentanément. |
| Jamais transmis ailleurs | L'URL cible est l'adresse du routeur, saisie par l'utilisateur. |

`SettingsStore` (DataStore) ne persiste que **deux** valeurs : l'adresse du
routeur et le nom d'utilisateur. Le mot de passe en est explicitement absent.

> Si la persistance du mot de passe est ajoutée un jour, elle **doit** passer
> par l'**Android Keystore** (`EncryptedSharedPreferences` ou un stockage
> chiffré équivalent), jamais par le DataStore en clair.

### Réseau

| Règle | Mise en œuvre |
|---|---|
| Local uniquement | Aucune URL codée en dur : l'hôte vient du champ de saisie. Aucune requête vers un service tiers. |
| Permissions minimales | `INTERNET`, `ACCESS_NETWORK_STATE`, `ACCESS_LOCAL_NETWORK`. Rien d'autre. |
| Pas de désactivation globale | `usesCleartextTraffic="true"` est **nécessaire** : le routeur parle en HTTP clair sur le LAN. Il est limité au manifeste de l'application et n'affecte pas les autres applications. |
| Protection réseau locale | Sur Android 16+, `ACCESS_LOCAL_NETWORK` est demandée au démarrage. |

### Écriture sur le routeur

**Aucune commande d'écriture n'est possible depuis l'application.** Ce n'est
pas une discipline mais une propriété structurelle :

- `RouterAdapter` ne déclare aucune méthode mutante ;
- `ZltRouterAdapter` n'instancie que `ZltRouterClient`, dont l'API publique
  est `login()`, `fetchDevices()` et `invalidateSession()` — toutes en
  lecture ;
- le module `app` n'importe pas le package `dev.wifiadmin.router.wifi`.

---

## 6. L'interface

### Navigation

Navigation Compose, avec une barre inférieure à trois entrées (Tableau de
bord, Appareils, Réglages) et un écran de connexion sans barre.

```
Login ──> Dashboard ──┐
          Devices ────┼── (barre inférieure)
          Settings ───┘
```

### Material 3

- Thème clair et sombre, suivant la préférence système ;
- couleurs dynamiques (Material You) sur Android 12+ ;
- typographie explicite pour la hiérarchie visuelle.

### États

Chaque écran couvre les quatre états : **Loading**, **Success**, **Empty** et
**Error**. Les composants partagés (`LoadingState`, `ErrorState`,
`EmptyState`, `StatCard`, `DetailRow`) garantissent leur homogénéité.

### Champs indisponibles

La fiche d'un appareil **omets** les champs que le routeur n'a pas fournis
plutôt que d'afficher « - » : un RSSI absent n'est pas displayed, il n'est
pas maquillé.

---

## 7. Le filtrage MAC dans l'interface

Le filtrage MAC n'est **pas** exposé comme fonctionnalité, parce qu'il n'est
pas disponible sur ce firmware. Le tableau de bord affiche une notice
explicite qui distingue deux choses que l'utilisateur pourrait confondre :

1. le **filtrage MAC Wi-Fi** (`cmd 278`) — refusé par le firmware
   (`LIMITED_ACCESS`), il empêcherait l'**association** Wi-Fi ;
2. le **blocage d'accès à Internet** — qui relève du **pare-feu**
   (`cmd 23`), un mécanisme distinct, lui aussi **`UNAVAILABLE`** sur ce
   firmware (clôture du 30/10/2026, voir `FIREWALL_ANALYSIS.md` §13ter).

L'application ne prétend donc **jamais** qu'un appareil « non bloqué » a accès
à Internet. Aucun bouton Bloquer/Débloquer n'existe : l'interface Compose
livrée (tableau de bord, appareils, réglages) est en **lecture seule**, et
l'interface de blocage est **exclue du périmètre**.

---

## 8. Tests

| Couche | Ce qui est testé |
|---|---|
| `router-core` (193) | Le protocole : authentification, expiration de session, renouvellement unique, fusion DHCP/Wi-Fi, classification, filtre Wi-Fi, frontière lecture seule. |
| `poc-cli` (56) | Les rendus console / JSON / diagnostic et l'absence de fuite de données. |
| `app` (126) | Les ViewModels, la navigation d'expiration, le cache local (`SnapshotStore`), la restauration, le contrat du message affiché, la non-régression et la frontière d'architecture. |

**Aucun test n'atteint le routeur réel.** Le transport est soit factice
(`FakeRouter`), soit un serveur HTTP local sur `127.0.0.1`.

Les tests du module `app` utilisent un `FakeAdapter` implémentant
`RouterAdapter` : ils valident le comportement de l'interface sans jamais
toucher au protocole. Les tests du cache utilisent un répertoire temporaire et
une horloge injectable : aucun fichier réel de l'application n'est touché.

Total : **375 tests**, 0 échec (mission 08, validation de l'expiration incluse).

Le scénario d'expiration est **reproductible hors ligne** : voir
`docs/MISSION_08_EXPIRY_VALIDATION.md` et `tools/fake_router_expiry.py`.

---

## 9. Dernier instantané et expiration de session

### 9.1 `SnapshotStore`

Le dernier état valide des appareils est conservé dans un **fichier JSON
unique** :

```text
context.noBackupFilesDir/last-device-snapshot.json
```

`noBackupFilesDir` est exclu de la sauvegarde cloud et du transfert
d'appareil à appareil par le framework Android. `android:allowBackup="false"`
dans le manifeste est une seconde barrière. Le fichier n'est jamais lisible par
une autre application.

Le format est versionné (`schema`, `schemaVersion`), avec une **liste blanche
de clés** : mot de passe, jeton, `sessionId`, challenge et cookies ne peuvent
pas y entrer, même par erreur. Les champs autorisés sont uniquement ceux de
`RouterDevice` (MAC, IP, nom, bandes, signal, interface, bail DHCP, IPv6,
présence) plus l'hôte et le compte.

| Contrainte | Valeur |
|---|---|
| Taille maximale du fichier | 512 Kio |
| Nombre maximal d'appareils | 256 |
| Longueur maximale d'un champ | 256 caractères (MAC : 64) |
| Profondeur JSON maximale | 32 |
| Tolérance d'horloge future | 5 minutes |

Écriture **atomique** : fichier temporaire, `flush`, `fsync`, puis
`Files.move(..., ATOMIC_MOVE, REPLACE_EXISTING)`. Un lecteur voit toujours
l'ancien document complet ou le nouveau, jamais un fichier tronqué. Un échec
d'écriture laisse l'ancien fichier intact et ne fait pas échouer un
rafraîchissement réussi.

Un fichier absent, corrompu, tronqué, trop gros, daté du futur ou d'une version
inconnue est **rejeté et supprimé** : l'application ne « répare » jamais
silencieusement une donnée qu'elle ne comprend pas.

### 9.2 Cycle de vie

| Moment | Mémoire | Disque |
|---|---|---|
| Lecture réussie (`payloadAbsent = false`) | remplacée | écrite |
| Réponse sans liste (`payloadAbsent = true`) | instantané précédent conservé | **jamais écrite** |
| Échec réseau / timeout / cooldown | instantané conservé | conservé |
| Expiration de session | **vidée** | **conservé** |
| Déconnexion volontaire | vidée | **supprimé** |
| Changement d'hôte ou de compte | non restauré | **supprimé** |

La restauration n'a lieu qu'**après** l'acceptation des identifiants par le
routeur, et seulement si l'hôte et le compte correspondent. Un instantané d'un
autre routeur ou d'un autre compte est supprimé, jamais affiché.

Une lecture plus ancienne qu'un instantané déjà stocké ne l'écrase pas
(`OLDER_THAN_STORED`).

### 9.3 Affichage du cache

Après authentification, le dernier instantané est affiché immédiatement, puis
remplacé par la première lecture réussie. Tant qu'il est affiché, un bandeau
indique :

> **Dernières données connues — actualisées le [date et heure].**

En mode cache, aucun appareil n'est présenté comme actuellement connecté :
les badges deviennent neutres et disent « Vu actif » / « Vu filaire », et le
compteur « Wi-Fi actif » devient « Wi-Fi (lecture) ». La fiche détail rappelle
la date d'enregistrement.

### 9.4 Expiration de session

Le client `router-core` tente **une seule fois** de renouveler une session
expirée (`NO_AUTH` / `LOGIN_TIMEOUT`) avant de lever
`RouterAuthenticationException`. Comme une lecture ne porte **aucun mot de
passe**, cette exception ne peut pas être un mauvais mot de passe : l'adaptateur
la traduit en `SessionExpired`.

Sur expiration confirmée :

1. les lectures suivantes sont refusées sans appel réseau ;
2. la session et l'instantané en mémoire sont invalidés ;
3. la navigation revient à la connexion en vidant toute la pile
   (`popUpTo(graph.id) { inclusive = true }`) : le bouton Retour ne peut pas
   rouvrir un écran authentifié ;
4. le message exact est affiché :
   **« Votre session a expiré. Reconnectez-vous pour continuer. »** ;
5. la reconnexion reste **manuelle**.

Un `LaunchedEffect` unique, keyé sur l'état de connexion **et** sur la route
courante, applique la règle `shouldReturnToLogin` : elle ne se déclenche que
pour `SessionExpired` et jamais lorsque l'écran de connexion est déjà
affiché. Aucune boucle de reconnexion automatique n'existe.

Une seconde règle, `shouldLeaveProtectedScreen`, sert de **filet de sécurité** :
si une navigation par onglet est dispatchée à l'instant exact de l'expiration,
elle ne peut pas empiler un écran protégé au-dessus de l'écran de connexion.
Elle ne se déclenche elle aussi que sur une expiration confirmée.

Trois verrous complémentaires rendent l'expiration fiable :

| Verrou | Emplacement |
|---|---|
| L'adaptateur est détaché, le `sessionId` du client passe à `null` | `ZltRouterAdapter.disconnect()` |
| Un compteur de génération invalide les lectures en vol | `RouterRepository.generation` |
| La garde d'état refuse toute lecture hors session | `RouterRepository.refresh()` |

Les ViewModels des écrans authentifiés **observent** l'état du repository et
purgent leur snapshot dès que la session n'est plus valide : la sécurité ne
repose pas sur la seule navigation.

| Situation | État | Navigation |
|---|---|---|
| Session non renouvelable | `SessionExpired` | retour à la connexion |
| Mauvais identifiants (connexion) | `Error(AuthenticationFailed)` | reste sur la connexion |
| Routeur injoignable | `Error(Unreachable)` | aucune |
| Timeout | `Error(Timeout)` | aucune |
| Gel temporaire (180 s) | `Cooldown` | aucune |
| Réponse malformée | `Error(UnexpectedResponse)` | aucune |
| Perte du Wi-Fi | `Error(Unreachable)` | aucune |

---

## 10. Ce qui reste à faire

| Sujet | État |
|---|---|
| Filtrage **pare-feu** (`cmd 23`) | **`UNAVAILABLE` sur ce firmware** (clôture du 30/10/2026). L'interface officielle reçoit la même réponse creuse que nous. Aucune intégration ne sera faite. Voir `FIREWALL_ANALYSIS.md` §13ter. |
| Persistance du mot de passe via Keystore | Non implémentée — volontairement absente pour l'instant. |
| Support d'autres routeurs | L'architecture est prête (interface `RouterAdapter`), mais aucun second adaptateur n'existe. |
| Interface Compose livrée | **Existante** : tableau de bord, liste des appareils, réglages, connexion. Aucun changement de périmètre. |
| Interface de blocage (bouton Bloquer/Débloquer) | **Exclue** : la capacité est `UNAVAILABLE`, aucun bouton n'est exposé. |
