# Mission 08 — Rapport consolidé : dernier instantané et expiration de session

> Date : 31/10/2026 · Mission : 08 · Département : développement Android
> Modèle de référence : ZLT W304VA PRO, firmware 6.07.0.

---

## 1. Objectif

Améliorer la fiabilité du MVP Android **sans élargir le périmètre** :

1. **Fonctionnalité A — Dernier instantané** : conserver localement le dernier
   état valide des appareils, l'afficher après authentification, le remplacer
   par de nouvelles données valides, et l'indiquer en cas d'échec.
2. **Fonctionnalité B — Expiration de session** : détecter une expiration
   réelle, arrêter les lectures, revenir proprement à la connexion, distinguer
   expiration / mauvais identifiants / routeur injoignable / timeout / gel /
   réponse malformée / perte Wi-Fi.

## 2. Rapports des sous-agents (audit, en lecture seule)

### 2.1 — Architecture et persistance (Sous-agent 1)

**Constat de départ.** Le repository était déjà la seule frontière réseau et
`DeviceSnapshot.capturedAtMillis` portait déjà l'horodatage de la lecture.
Le travail Mission 08 était donc un codec + un fichier + un branchement, sans
redéfinir les modèles.

**Décisions retenues (implémentées).**

| Point | Décision |
|---|---|
| Emplacement | `context.noBackupFilesDir/last-device-snapshot.json` + fichier temporaire |
| Atomicité | `flush` → `fsync` → `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` |
| Format | JSON versionné (`dev.wifiadmin.snapshot`, v1) + liste blanche de clés |
| Contexte | Hôte normalisé + compte (a défaut : rejet et suppression) |
| Durée de conservation | Jusqu'à la déconnexion volontaire, au changement de contexte, ou à la désinstallation. Aucun TTL automatique imposé. |
| Expiration | Mémoire vidée, fichier conservé (reconnexion sur le même contexte). |

**Risques relevés.** I/O sur le main thread (prévenu : dispatchers), concurrence
(doublon MAC à l'identifiant UI, plafond 256 appareils), JSON profond (gardes
contre `StackOverflowError`), taille (512 Kio max).

### 2.2 — Authentification et navigation (Sous-agent 2)

**Constat.** Le client `ZltRouterClient` fait déjà un renouvellement **unique**
sur `NO_AUTH` / `LOGIN_TIMEOUT` et ne boucle pas ; `connect()` retourne `false`
pour un mauvais mot de passe ; une lecture ne porte jamais de mot de passe.

**Décisions retenues (implémentées).**

- `RouterAuthenticationException` pendant un refresh → `RouterUnavailableException.SessionExpired`.
- `SocketTimeoutException` traversée par la chaîne de causes → `RouterUnavailableException.Timeout`.
- `RouterCooldownException` (gel 180 s) → état applicatif distinct `Cooldown`, **pas** une expiration.
- Navigation : `LaunchedEffect` unique keyé sur l'état, retour à la connexion avec
  `popUpTo(graph.id) { inclusive = true }`, `launchSingleTop`, `restoreState = false`.

### 2.3 — UI/UX Compose (Sous-agent 3)

**Décisions retenues (implémentées).**

- Bandeau : **« Dernières données connues — actualisées le [date et heure]. »**
  via `CachedDataMessage` (testé en unitaire).
- Mode cache : badges neutres (« Vu actif », « Vu filaire »), compteur « Wi-Fi (lecture) ».
- Fiche détail : « État à la lecture » + rappel de la date d'enregistrement.
- Aucun changement visuel inutile ; composants existants réutilisés.

### 2.4 — Sécurité et qualité (Sous-agent 4)

**Verdict.** Périmètre maintenu en lecture seule. Points de vigilance notés :
les limites de taille (512 Kio, 256 appareils, champs bornés) ; la fermeture de la
liste blanche ; la suppression sur déconnexion et changement de contexte ; la
non-régression des scénarios hors ligne.

## 3. Bilan consolidé — sécurité et non-régression

| Domaine | Verdict |
|---|---|
| Secret persisté | **Non.** La liste blanche de clés interdit structurellement tout champ de type credential. |
| Sauvegarde cloud | **Désactivée** (`allowBackup="false"`) + répertoire `noBackupFilesDir`. |
| Accès avant authentification | **Impossible.** Le store n'est lu qu'après acceptation des identifiants, pour le contexte courant. |
| Fichiers non conformes | Absents / corrompus / version inconnue / futur / trop gros → rejet + suppression. |
| Expiration | **Confirmée uniquement** pour une renouvellement raté d'une session déjà ouverte. |
| Erreur réseau ≠ expiration | **Respecté** : unreachable, timeout, cooldown, protocole et perte Wi-Fi ne naviguent pas. |
| Écriture applicative | **Nulle** sur le routeur, aucune action Bloquer/Débloquer, aucun backend. |
| Tests | **328 tests, 0 échec** ; scénarios hors ligne **10/10**, aucune écriture. |

**Verdict global.** La Mission 08 est livrée. Les points qui exigent une
validation sur le téléphone réel sont listés à l'§5.

## 4. Livrables

| Livrable | État |
|---|---|
| `SnapshotStore` et intégration | ✅ |
| Gestion fiable de l'expiration | ✅ |
| Ajustements ciblés Compose | ✅ |
| Tests unitaires | ✅ (+ cache, expiration, navigation, presentation, display) |
| Documentation | ✅ `ANDROID_ARCHITECTURE.md`, `IMPLEMENTATION_PLAN.md`, `MAC_FILTER_ANALYSIS.md`, `ROUTER_PROTOCOL.md` (§7/§9ter mis à jour) |
| Rapport sous-agents | ✅ synthèse §2 |
| Rapport consolidé | ✅ ce document |
| Instructions de test téléphone | ✅ `docs/MISSION_08_PHONE_TEST.md` |

## 5. Tests et résultats

| Suite | Avant | Après | Échecs |
|---|---:|---:|---:|
| `router-core` | 183 | 183 | 0 |
| `poc-cli` | 56 | 56 | 0 |
| `app` | 35 | 89 | 0 |
| **Total** | **274** | **328** | **0** |

Scénarios hors ligne : **10/10**, aucune tentative d'écriture.

La suite complète a été relancée après la dernière modification du repository
(`clear` de l'instantané au démarrage d'un login, suppression du doublon
superflu de la vérification de cooldown) :

```bash
./gradlew :router-core:test :poc-cli:test :app:testDebugUnitTest --offline
python3 tools/fake_router_scenarios.py
```

## 6. Limites et points nécessitant une validation réelle

1. **Détection d'expiration** : dépend du comportement documenté du firmware
   (renouvellement unique sur `NO_AUTH` / `LOGIN_TIMEOUT`). La durée réelle de
   validité d'une session n'est pas mesurée.
2. **Affichage du cache sur téléphone** : l'instantané écrit dans
   `noBackupFilesDir` (vérifié via `adb run-as … ls/cat`) n'a pas encore été
   confirmé sur l'appareil.
3. **Expiration réelle** : impossible à forcer sans action sur le routeur ;
   les tests sur téléphone se reposent sur l'inactivité ou une déconnexion
   web.
4. **Révision indépendante finale** : la sous-agent de revue n'a pas pu être
   relancée (limite d'utilisation du modèle) ; la revue a été faite
   **directement dans l'agent principal** à la place, puis les anomalies
   trouvées (message d'erreur d'authentification oublié lors d'un login
   raté, doublon de vérification, persistance d'un instantané au démarrage
   d'un login) ont été corrigées.
5. **Téléphone** : instructions complètes dans
   `docs/MISSION_08_PHONE_TEST.md`.

## 7. Non-régression

- Aucun test de `router-core` ni de `poc-cli` n'a été modifié.
- Les tests existants de l'app (connexion, recherche, filtres, payloads, état
  de chargement) passent toujours.
- L'interface de blocage et le module firewall/wifi restent hors périmètre.

---

*Rapport consolidé rédigé après la livraison de la Mission 08. Vérification à
refaire à chaque changement majeur.*
