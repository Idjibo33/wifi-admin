# Mission 10 — Procédure de validation automatisée sur le téléphone

> Cette procédure est **automatisée**. Elle s'exécute sans intervention
> manuelle : les commandes ci-dessous sontExactement celles qui ont
> produit les preuves du rapport.

---

## 1. Préparation

```bash
cd /home/ismael/Documents/wifi-admin/wifi-admin-app
export PATH="$HOME/Android/Sdk/platform-tools:$PATH"
adb devices -l
```

Un seul appareil doit être connecté. Les tests instrumentés s'exécutent
sur le téléphone réel ; **aucun test ne contacte le routeur**.

## 2. Construction et installation

```bash
./gradlew :app:assembleDebug --offline
./gradlew :app:installDebug --offline
./gradlew :app:installDebugAndroidTest --offline
```

L'installation **préserve** les données utilisateur : aucun `pm clear`,
aucune désinstallation de l'application.

## 3. Tests unitaires

```bash
./gradlew :router-core:test :poc-cli:test :app:testDebugUnitTest --offline --rerun-tasks
```

Vérification du décompte :

```bash
for d in router-core/build/test-results/test \
         poc-cli/build/test-results/test \
         app/build/test-results/testDebugUnitTest; do
  grep -ho 'tests="[0-9]*" skipped="[0-9]*" failures="[0-9]*" errors="[0-9]*"' $d/*.xml \
    | awk -F'"' -v d="$d" '{t+=$2; f+=$6; e+=$8} END {print d " tests=" t " failures=" f " errors=" e}'
done
```

## 4. Scénarios hors ligne

```bash
python3 tools/fake_router_scenarios.py
python3 tools/fake_router_expiry_scenarios.py
```

Les deux suites doivent finir par `no write was attempted` : le compteur
d'écritures du faux routeur doit rester à zéro.

## 5. Tests instrumentés sur l'appareil

```bash
adb shell am instrument -w -r dev.wifiadmin.app.test/androidx.test.runner.AndroidJUnitRunner
```

Résultat attendu : `OK (38 tests)`.

Les classes de la Mission 10 :

| Classe | Ce qu'elle prouve |
|---|---|
| `DevicesListInstrumentedTest` | Le rendu de la liste : nom, nom du routeur, signal, bandes, tri, recherche, états vides, dialogue de renommage. |
| `DeviceListPreferencesInstrumentedTest` | Les préférences de tri et de filtre sont conservées, et une valeur inconnue ne casse rien. |

## 6. Vérifications manuelles complémentaires

Ces points ne sont **pas** couverts par un test automatisé et demandent
un regard humain.

### 6.1 Le renommage dans l'application

1. Se connecter au routeur.
2. Ouvrir l'onglet **Appareils**.
3. Toucher un appareil, puis le crayon.
4. Taper un nom, valider.
5. **Vérifier** : le nom personnalisé est affiché, et « Router : … » reste
   juste en dessous.

### 6.2 La persistance après redémarrage

1. Renommer un appareil.
2. **Fermer complètement** l'application.
3. Rouvrir l'application et se reconnecter.
4. **Vérifier** : le nom personnalisé est toujours là.

### 6.3 Le changement de réseau

1. Renommer un appareil.
2. Couper le Wi-Fi du téléphone pendant plus d'une minute.
3. Le reconnecter.
4. **Vérifier** : le nom personnalisé est toujours là, et l'observation
   n'a pas été mise à jour pendant l'absence.

## 7. Ce que la procédure ne prouve pas

| Point | Raison |
|---|---|
| Le comportement d'un appareil qui **randomise sa MAC** | La randomisation est décidée par l'appareil ; le routeur ne signale rien. L'application le traite comme un nouvel appareil, par construction. |
| L'isolation entre deux routeurs de **même plage et même passerelle** | L'identité réseau ne contient pas de BSSID : ce serait exiger `ACCESS_FINE_LOCATION`. Limite structurelle, assumée depuis la Mission 09. |
| La purge après **trente jours** | La purge est déclenchée par l'application : un téléphone jamais ouvert conserve ses données au-delà de la durée. |
| Le rendu **pixel** | Les tests d'instrumentation vérifient l'arbre sémantique, pas l'image. |

## 8. Retour à l'état initial

```bash
adb uninstall dev.wifiadmin.app.test
adb shell dumpsys wifi | grep -E "Wi-Fi is|mIsStaConnected"
```

Le Wi-Fi doit être activé et connecté, et l'APK de test doit être désinstallé.
