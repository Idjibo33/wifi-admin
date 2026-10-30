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
`RouterUnavailableException`, une hiérarchie de trois cas que l'interface
utilisateur comprend :

| Exception | Signification pour l'utilisateur |
|---|---|
| `Unreachable` | Le routeur est injoignable. |
| `AuthenticationFailed` | Identifiants refusés, ou session expirée. |
| `UnexpectedResponse` | Le routeur a répondu quelque chose d'inattendu. |

Les écrans n'ont jamais à connaître le protocole.

---

## 3. `RouterRepository`

Le repository est la source de vérité unique :

```kotlin
class RouterRepository(private val adapter: RouterAdapter = ZltRouterAdapter()) {
    val state: StateFlow<RouterUiState>
    suspend fun connect(host, username, password): Boolean
    suspend fun refresh(): Result<DeviceSnapshot>
    fun disconnect()
}
```

Il porte :

- l'**état de connexion** (`Disconnected`, `Connecting`, `Connected`, `Error`) ;
- le **dernier instantané** des appareils ;
- l'**erreur** éventuelle ;
- l'**horodatage** de la dernière actualisation.

Un `Mutex` sérialise les opérations : deux rafraîchissements simultanés ne
peuvent pas se chevaucher, ce qui éviterait deux sessions concurrentes.

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
   (`LIMITED_ACCESS`), il empêche l'**association** Wi-Fi ;
2. le **blocage d'accès à Internet** — qui relève du **pare-feu**
   (`cmd 23`), un mécanisme distinct dont l'effet n'est pas vérifié.

L'application ne prétend donc **jamais** qu'un appareil « non bloqué » est
accès à Internet. Un appareil bloqué côté Internet resterait d'ailleurs
**visible et connecté** dans la liste.

---

## 8. Tests

| Couche | Ce qui est testé |
|---|---|
| `router-core` (87) | Le protocole : authentification, expiration de session, fusion DHCP/Wi-Fi, classification, filtre Wi-Fi, frontière lecture seule. |
| `poc-cli` (16) | Les rendus console / JSON / diagnostic et l'absence de fuite de données. |
| `app` (19) | Les ViewModels : connexion, résultats d'erreur, recherche, filtres, état de chargement. |

**Aucun test n'atteint le routeur réel.** Le transport est soit factice
(`FakeRouter`), soit un serveur HTTP local sur `127.0.0.1`.

Les tests du module `app` utilisent un `FakeAdapter` implémentant
`RouterAdapter` : ils valident le comportement de l'interface sans jamais
toucher au protocole.

---

## 9. Ce qui reste à faire

| Sujet | État |
|---|---|
| Filtrage **pare-feu** (`cmd 23`) | **`UNAVAILABLE` sur ce firmware** (clôture du 30/10/2026). L'interface officielle reçoit la même réponse creuse que nous. Aucune intégration ne sera faite. Voir `FIREWALL_ANALYSIS.md` §13ter. |
| Persistance du mot de passe via Keystore | Non implémentée — volontairement absente pour l'instant. |
| Support d'autres routeurs | L'architecture est prête (interface `RouterAdapter`), mais aucun second adaptateur n'existe. |
| Interface Compose | **Hors périmètre** : aucun bouton de blocage, la capacité n'existe pas sur ce firmware. |
