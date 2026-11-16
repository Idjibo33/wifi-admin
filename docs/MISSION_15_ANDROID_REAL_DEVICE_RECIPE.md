# Mission 15.1 — Recette réelle Android (Pixel 7 Pro)

Date : 2026-11-16 (UTC). Device : Pixel 7 Pro (Android 17, API 37, `2B091FDH3004DG`).

Branche : `fix/mission-15-android-permission-recipe` @ `9929e88` + corrections VPN.

Aucun benchmark LibreSpeed lancé. Aucune modification routeur ZLT, UFW/iptables, certificats. Aucune suppression de données sans autorisation (autorisation explicite obtenue pour les réinstallations de test).

## Résultats observés

### Scénario A — Premier refus

- **Statut : PASS** (preuves techniques + observation manuelle)
- Aucun dialogue de permission automatique au lancement.
- Bandeau « Accès au réseau local requis pour joindre le routeur » visible.
- Bouton « Autoriser l'accès » présent.
- Tentative de connexion → erreur permission immédiate (aucun timeout 8 s, aucun « Identifiants refusés »).
- Aucun socket LAN ouvert (gate synchrone).

### Scénario B — Accord et connexion réelle

- **Statut : PASS** (observation manuelle)
- Permission accordée via dialogue système sans redémarrage.
- Connexion au routeur réussie après désactivation manuelle du VPN (VPN full-tunnel bloquait le LAN).
- Tableau de bord chargé avec liste d'appareils.
- Aucun benchmark LibreSpeed déclenché.
- Aucun double dialogue de permission.
- **Défaut détecté** : spinner infini lors d'un rafraîchissement manuel réutilisé → corrigé (`DashboardViewModel.onSuccess`).

### Scénario C — Révocation pendant une session

- **Statut : BLOCKED** (comportement système)
- Le processus Android a été tué lors du passage aux paramètres système (pression mémoire).
- État observé après retour : données à 0, erreur à la relance.
- Distinction : scénario « processus vivant » non observable proprement sur cet appareil en raison de la gestion mémoire d'Android 17.
- La logique de préservation de session est couverte par les tests JVM (`RouterRepositoryPermissionGateTest`).

### Scénario D — Réautorisation depuis les paramètres

- **Statut : PARTIEL** (observation manuelle)
- Accord depuis les paramètres → bandeau effacé, accès rétabli.
- Aucune connexion automatique, aucun benchmark.
- Reprise pleinement validée seulement avec session stable (voir C).

### Scénario E — Refus permanent et redémarrage

- **Statut : PASS après correctif** (preuves techniques + observation manuelle)
- Avant correctif : après `Force stop`, le bouton « Autoriser l'accès » réapparaissait (régression — `previouslyRefused` en mémoire perdue).
- Correctif : persistance `SharedPreferences` (`commit()`) du fait qu'une demande a été faite.
- Après correctif : état « Paramètres uniquement » persiste après `Force stop` + relance.
- L'accord depuis les paramètres efface correctement l'état mémorisé.

### Rotation / recréation d'activité

- **Statut : NON EXÉCUTÉ** (non testé sur appareil)
- Couvert par tests JVM (`isRequestInFlight` anti-doublon dans `LocalNetworkPermissionTest`).

### VPN

- **Statut : ANALYSÉ ET CORRIGÉ**
- VPN full-tunnel (Proton) bloquait le LAN → timeout générique sur Login (aucune indication VPN sur Login avant correctif).
- Correctif : indication VPN prudente sur Login (`VPN_MAY_AFFECT_MESSAGE`) lors d'un échec Timeout/Unreachable avec VPN présent, avec bouton « Réessayer ».
- Aucune désactivation automatique du VPN, aucun `bindProcessToNetwork`, aucun contournement.
- Indication VPN déjà présente sur Dashboard/Devices (préexistante).

## Corrections appliquées

1. **Spinner infini lors d'un rafraîchissement manuel réutilisé** (`DashboardViewModel`) — `onSuccess` efface `isRefreshing` + flags stale. Test de régression ajouté.
2. **Perte du refus permanent après destruction du processus** (`AndroidLocalNetworkPermissionGateway`) — persistance `SharedPreferences` (`commit()`). Effacé à l'accord.
3. **Indication VPN sur Login** — ajout `vpnPresent` paramètre + carte VPN avec « Réessayer ».
4. **Cohérence DevicesViewModel** — `onSuccess` efface `isLoading` pour les reads réutilisés.

## Tests exécutés

- `./gradlew testDebugUnitTest --offline` : **BUILD SUCCESSFUL — 445 tests, 0 échec, 0 erreur**
- `./gradlew assembleDebug --offline` : **BUILD SUCCESSFUL**
- Nouveaux tests : `DashboardViewModelRefreshReuseTest`, `LoginVpnHintContractTest`

## Phase de vérification des permissions

Clôturée à la demande de l'utilisateur. Les résultats observés restent inchangés dans ce rapport. Les tests JVM et instrumentés (fakes) ne suffisent pas seuls ; la recette manuelle a été partiellement exécutée sur appareil réel.

## Limites

- Scénario C non validé proprement (mort de processus).
- Rotation non testée sur appareil.
- VPN indication sur Login non vérifiée sur appareil réel (logique couverte par `NetworkDiagnosticsTest` + source-reading test).
- `main` inchangé. Aucune fusion effectuée.
