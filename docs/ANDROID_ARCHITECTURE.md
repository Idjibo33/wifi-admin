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

Total : **546 tests** JVM, 0 échec, plus **38 tests instrumentés** exécutés
sur un Pixel 7 Pro (Android 17) — voir `MISSION_10_REPORT.md`.

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

## 10. Rafraîchissement automatique et changements de réseau

### 10.1 La répartition des responsabilités

Trois étages, chacun testable à son niveau :

```text
Compose (LifecycleStartEffect)   →  « l'écran est-il visible ? »
   ↓ startPolling() / stopPolling()
RefreshCoordinator (la boucle)    →  « toutes les 30 s, tant que visible »
   ↓ refreshIfStale()
ReadCoordinator (single-flight)   →  « une seule lecture, pas périmée »
   ↓
RouterAdapter
```

| Élément | Lieu | Justification |
|---|---|---|
| Détection de la visibilité | `LifecycleStartEffect` dans les écrans Compose | La Compose connaît le cycle de vie ; le ViewModel n'a pas à le connaître. |
| La boucle de 30 s | `RefreshCoordinator`, **unique** dans `AppContainer` | Un ViewModel est recréé au changement d'onglet : son minuteur repartirait de zéro. Un coordinateur unique non. |
| La mutualisation | `ReadCoordinator` (un `Deferred` en vol) | Le `Mutex` **sérialise** mais ne **mutualise** pas : deux écrans produiraient deux requêtes. |
| La fraîcheur et le back-off | `ReadFreshnessPolicy`, `RefreshPolicy` | Des fonctions pures : testables sans coroutine et sans attente. |
| La détection du réseau | `NetworkMonitor`, observé par le repository | Le repository détient l'état de session : c'est le seul qui puisse comparer le réseau courant à celui de la session. |

### 10.2 La lecture est dédupliquée, pas seulement sérialisée

`ReadCoordinator` tient **un seul `Deferred` en vol**. Un appelant qui arrive
pendant une lecture **attend cette lecture** au lieu d'en lancer une
autre : les deux écrans reçoivent le même résultat, et le routeur n'est
demandé qu'une fois.

C'est la subtilité centrale du chantier : le `Mutex` du repository enveloppe
aujourd'hui l'appel réseau. Le garder ainsi **casserait silencieusement** la
mutualisation — le second appelant attendrait le verrou, constaterait
qu'aucune lecture n'est en vol, et ferait une seconde lecture.

Le compteur de `fetchCalls` du faux adaptateur est la preuve : le test
« deux lectures concurrentes n'atteignent l'adaptateur qu'une fois » échouerait
si le verrou remained autour de la lecture.

### 10.3 L'horloge : monotone pour les délais, civile pour les étiquettes

| Usage | Horloge |
|---|---|
| Période de rafraîchissement | `delay` de coroutines (monotone) |
| Fenêtre de fraîcheur | `AppClock.monotonicMillis()` |
| Back-off | `AppClock.monotonicMillis()` |
| « Lecture en cours depuis quand » | `AppClock.monotonicMillis()` |
| `capturedAtMillis`, `writtenAtMillis` | `AppClock.wallClockMillis()` |

`SystemClock.elapsedRealtime()` est l'horloge monotone d'Android ;
`System.currentTimeMillis()` est l'horloge civile. Un changement de date du
téléphone ne peut donc ni raccourcir ni allonger une période, ni faire
disparaître l'instantané.

### 10.4 La détection du réseau

`NetworkMonitor` surveille les **réseaux Wi-Fi**, pas le réseau par défaut :
un callback sur le réseau par défaut ne dirait rien du Wi-Fi local quand il
cesse d'être le réseau privilégié.

Ce qui **n'est pas** utilisé, et pourquoi :

| Ce qui n'est pas utilisé | Raison |
|---|---|
| `NET_CAPABILITY_VALIDATED` | Un réseau local **sans Internet** reste parfaitement capable de joindre le routeur. Le filtrer reviendrait à casser le cas le plus utile. |
| `NET_CAPABILITY_INTERNET` | Idem. |
| Le SSID et le BSSID | Ils exigent `ACCESS_FINE_LOCATION` depuis Android 12. L'application ne la demande pas. |
| `getLinkProperties()` dans un callback | La plateforme warns explicitement que les appels synchrones sont sujets à races. Les valeurs viennent des **arguments** du callback. |

Un partage de connexion mobile est un réseau Wi-Fi **et** cellulaire : il
n'est jamais le routeur, et il est rapporté comme « pas de réseau local ».

### 10.5 L'identité du réseau, et la limite assumée

`NetworkIdentity` associe l'interface, la plage d'adresses et la passerelle.
Elle entre dans le `SnapshotContext`, à côté de l'hôte et du compte, et c'est
elle — **pas la seule passerelle** — qui est comparée à la restauration.

> **Pourquoi c'est nécessaire.** `192.168.1.1` est la passerelle par défaut
> de pratiquement **tout** routeur domestique, et `admin` le compte par
> défaut. Sans l'identité du réseau, le couple `(hôte, compte)` serait
> identique sur deux routeurs différents, et la liste d'appareils de l'un
> serait affichée pour l'autre.

> **La limite, assumée.** Deux réseaux différents **peuvent** partager la
> même interface, la même plage et la même passerelle. Sans le BSSID — qui
> exigerait une permission de localisation — l'application ne peut pas les
> distinguer. Elle dit donc « routeur détecté », jamais « votre routeur ».
> Le pire cas est un changement **non détecté** : les lectures échouent alors
> en `Unreachable`, **jamais** en expiration.

### 10.6 Un changement de réseau n'est pas une expiration

`ConnectionState.NetworkChanged` est un état **distinct** :

| | Expiration | Changement de réseau |
|---|---|---|
| Déclencheur | renouvellement d'une session raté | le téléphone a quitté le réseau du routeur |
| Session | invalide | **conservée** : le routeur peut encore la connaître |
| Lecture | arrêtée | **suspendue** |
| Données en mémoire | **vidées** | **conservées** : ce sont les dernières connues |
| Navigation | retour à la connexion | **aucune** |
| Message | « Votre session a expiré… » | « Le réseau a changé… » |

Le fichier du cache est conservé dans les deux cas : il porte son propre
contexte réseau, et il ne sera pas restauré sur un autre réseau.

### 10.7 Les règles de sûreté du rafraîchissement

| Règle | Effet |
|---|---|
| Une seule boucle pour toute l'application | 3 requêtes par cycle, pas 6 |
| Le rafraîchissement ne s'ouvre **jamais** de session | aucun `cmd 100` proactif, donc aucun gel de 180 s provoqué |
| Un cooldown **suspend** le rafraîchissement | le routeur qui refuse les connexions est laissé tranquille jusqu'à la fin de la pénalité |
| Les échecs font croître le délai | 30 s → 60 s → 120 s → 300 s |
| Le rafraîchissement s'arrête hors écran | aucun service, aucun WorkManager, aucune permission nouvelle |
| Une lecture manuelle ne double jamais une lecture en cours | elle attend la lecture en vol, ou réutilise une lecture de moins d'une seconde |
| Une lecture manuelle ne double jamais la lecture programmée | la fenêtre de fraîcheur s'applique aussi au bouton « Actualiser » |
| Une lecture **forcée** atteint toujours le routeur | `refreshForced()`, pour les cas où la donnée n'est plus fiable |

La passe de tests qui accompagne la mission compte les requêtes envoyées au
routeur : ce sont eux qui prouvent qu'un cycle de lecture en coûte **trois**
(les commandes 223, 224 et 225) et qu'aucune commande d'écriture n'est
possible.

---

## 11. Noms personnalisés, tri, filtres et dernière observation

Trois fonctionnalités, toutes **locales au téléphone**. Le routeur n'en
sait rien et ne le saura jamais.

### 11.1 L'identité d'un appareil

Le routeur n'expose **aucun** identifiant stable : les champs qu'il
rapporte sont la MAC, l'adresse IP, le nom d'hôte, l'interface et
l'expiration du bail. Les trois premiers peuvent changer sous les pieds de
l'utilisateur.

La clé est donc la **MAC canonique** :

- la normalisation accepte les séparateurs en usage — deux-points,
  tirets, points — et produit toujours la forme `aa:bb:cc:dd:ee:ff` en
  minuscules ;
- une valeur qui n'est pas douze chiffres hexadécimaux n'est **pas** une
  MAC : elle ne produit aucune clé ;
- **l'adresse IP n'est jamais une identité.** Elle change à chaque
  renouvellement de bail et elle peut être réattribuée à un autre
  appareil. Elle est stockée comme un attribut, jamais comme une clé ;
- **le nom d'hôte n'est jamais une identité** : il est déclaré par
  l'appareil, il peut être vide, et `DeviceMerger` le remplace par
  `Unknown Device` quand il manque.

Il n'y a **aucune clé de repli**. Le routeur ne fournit rien d'autre, et
une association par IP serait une attribution de nom **fausse** — pire que
la perte du nom.

> **La limite assumée.** Un appareil qui randomise sa MAC à chaque
> association — l'« adresse Wi-Fi privée » des téléphones modernes — est
> un **appareil nouveau** à chaque fois. L'application ne prétend pas le
> reconnaître : elle crée une nouvelle entrée et ne fusionne jamais
> l'ancienne. Le nom personnalisé ne suit pas un tel appareil.

Un appareil **sans** MAC exploitable ne porte ni nom ni observation : sans
clé, l'application ne retrouverait pas l'entrée.

### 11.2 La validation des noms

Un nom est du texte que l'application affiche. Il n'est jamais
interprété, mais il ne doit pas pouvoir **tromper** :

- la forme **NFC** est appliquée : « café » et « café » sont le même nom,
  et la recherche trouve l'un avec l'autre ;
- les **caractères de contrôle** (C0, C1, DEL) sont retirés ;
- les **indicateurs bidirectionnels** (`U+202E` et voisins) sont retirés :
  un inverseur de sens ferait lire un nom à l'envers, ce qui est une
  usurpation visuelle ;
- la longueur est **plafonnée** à 64 caractères ;
- un nom vide **supprime** le nom personnalisé.

La normalisation est appliquée **au stockage**, pas à l'affichage : sinon
la valeur sale resterait sur le disque.

### 11.3 Le stockage des métadonnées

Les noms personnalisés et les observations sont des **données
personnelles** : ils nomment l'équipement de l'utilisateur. Ils vivent
dans `noBackupFilesDir`, que le framework exclut de la sauvegarde cloud et
du transfert d'appareil à appareil.

Le format est celui du `SnapshotStore` : schéma versionné, **liste blanche
de clés** (un secret ne peut pas y entrer structurellement), écriture
atomique (`tmp` → `fsync` → `ATOMIC_MOVE`), garde de profondeur JSON,
rejet et suppression des fichiers corrompus.

**Un fichier par routeur.** Le nom du fichier est dérivé d'un hash de
`(host, account, networkId)` — la même identité que le `SnapshotContext`.
L'isolation est donc **structurelle** : le fichier de l'autre routeur
n'existe pas dans le répertoire. Le contexte complet est **aussi** dans le
document, et la comparaison à la lecture est une égalité exacte.

> **La limite assumée.** Deux routeurs domestiques ont souvent la même
> passerelle (`192.168.1.1`) et le même compte (`admin`). Ce qui les
> distingue est l'identité du réseau — interface, plage d'adresses,
> passerelle. Deux réseaux qui partagent les trois sont **considérés comme
> le même** : c'est la limite déjà assumée par la Mission 09, et le BSSID
> qui la lèverait exigerait `ACCESS_FINE_LOCATION`.

### 11.4 La dernière observation

La date est **le moment où le routeur a réellement répondu**, pas l'heure
courante : c'est `snapshot.capturedAtMillis`, l'instant capturé par la
fusion des listes.

Ce choix règle les trois pièges d'un coup :

| Situation | `capturedAtMillis` | Effet |
|---|---|---|
| Vraie lecture | l'instant de la lecture | l'observation avance |
| Lecture **réutilisée** par le coordinateur | l'instant de la **première** lecture | l'observation **ne bouge pas** |
| Snapshot **restauré du disque** | — | le chemin ne passe pas par `performRead` : **aucune** observation |
| Réponse sans liste d'appareils | — | `payloadAbsent` : **aucune** observation |

Le libellé ne prétend **jamais** connaître l'heure de déconnexion :
l'application sait qu'un appareil était présent à la dernière lecture, et
rien de plus. « Vu récemment » est une **borne**, pas un instant.

La conservation est de **trente jours**. Une entrée qui porte un nom
personnalisé est **conservée** au-delà : c'est le travail de l'utilisateur.
La purge est déclenchée par l'application, jamais par le système — pas de
`WorkManager`, pas d'`AlarmManager`.

### 11.5 Le tri, les filtres et la recherche

Les règles sont des **fonctions pures** : aucun état, aucun délai, testées
sur la JVM.

- **Par nom** : le collationneur français met les accents à leur place et
  les chiffres avant les lettres.
- **Par adresse IP** : **numérique**, octet par octet. Un tri
  lexicographique placerait `192.168.1.100` avant `192.168.1.2` — les deux
  sont faux.
- **Par signal** : le RSSI décroissant, et les appareils **sans** signal
  **en dernier**. Mélanger un `null` dans le classement revendiquerait un
  niveau qui n'existe pas, et placerait un appareil filaire avant un
  Wi-Fi très lointain.
- Chaque comparateur se termine par un **critère de rupture** stable :
  sans lui, la liste clignoterait à chaque rafraîchissement.

Les filtres sont les **bandes** : tous, 2,4 GHz, 5 GHz, filaire. Ils se
**recouvrent** à dessein : un appareil associé aux deux radios apparaît
dans les deux, parce que le routeur le voit bien sur les deux.

Le filtrage est **purement local** : les appareils sont déjà en mémoire,
et aucune lecture n'est déclenchée. Un test de source et un test
comportemental le prouvent.

Les préférences de tri et de filtre sont conservées dans un fichier
`DataStore` **distinct** des réglages de l'application : elles décrivent
l'affichage, pas le routeur.

### 11.6 La liste

La carte montre le **nom** (personnalisé s'il existe), le **nom du
routeur** en dessous, l'**adresse** et les **bandes**, le **signal**
lorsque le routeur en a donné un, et la **dernière observation** pour un
appareil qui n'est plus là.

Le MAC sort de la liste : il identifie, il ne sert pas à identifier, et il
est déjà dans la fiche.

---

## 12. Ce qui reste à faire
| Sujet | État |
|---|---|
| Filtrage **pare-feu** (`cmd 23`) | **`UNAVAILABLE` sur ce firmware** (clôture du 30/10/2026). L'interface officielle reçoit la même réponse creuse que nous. Aucune intégration ne sera faite. Voir `FIREWALL_ANALYSIS.md` §13ter. |
| Persistance du mot de passe via Keystore | Non implémentée — volontairement absente pour l'instant. |
| Noms personnalisés, tri, filtres, dernière observation | **Existants** (Mission 10). Tout est **local** au téléphone : le routeur n'en sait rien. |
| Support d'autres routeurs | L'architecture est prête (interface `RouterAdapter`), mais aucun second adaptateur n'existe. |
| Interface Compose livrée | **Existante** : tableau de bord, liste des appareils, réglages, connexion. Aucun changement de périmètre. |
| Interface de blocage (bouton Bloquer/Débloquer) | **Exclue** : la capacité est `UNAVAILABLE`, aucun bouton n'est exposé. |
