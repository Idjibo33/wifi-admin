# Mission 12 — Rapport d'implémentation

> Date : 07/11/2026 · Base `bd154c7` (Mission 11). Moteur réel **désactivé** (voir
> `MISSION_12_SPEEDTEST_RESEARCH.md`). Aucun appel Internet, aucun test réel lancé.

## 1. Architecture

```
data/speedtest/
  SpeedTestPolicy.kt        plafonds 20 Mo / 10 Mo / 30 s (purs, validés)
  SpeedTestCalculator.kt    Mbps, médiane latence, capped, jauge (purs JVM)
  SpeedTestResult.kt        résultat, états, erreurs, CancelReason
  WifiLinkInfoProvider.kt   liaison sans permission (NetworkCapabilities + WifiInfo rate/RSSI/bande)
  SpeedTestEngine.kt        DisabledSpeedTestEngine + FakeSpeedTestEngine + SpeedTestRepository
  SpeedTestHistoryStore.kt  DataStore borné 20/30 jours, codec JSON, consentement
ui/speedtest/
  SpeedTestPresentation.kt  messages FR, formatage, liens manuels
  SpeedTestViewModel.kt     StateFlow, greffe réseau, cancel sur perte/expiration
  SpeedTestScreen.kt        Accessibilité + consentement + jauge + résultats + liaison
navigation/                 Route.SpeedTest, PROTECTED_ROUTES, factory, hero dashboard
```

Séparation stricte : le moteur ne connaît ni `ZltRouterClient`, ni `RouterTransport`, ni `/cgi-bin`,
ni `cmd 100` (`Mission12BoundaryTest`). `RefreshCoordinator` et le polling routeur ne sont jamais
déclenchés par le test.

## 2. Fonctionnalités livrées

- Écran « Test de vitesse » (destination protégée, retour flèche, sans nouvel onglet) avec hero
  réseau, jauge Mbps, actions Lancer/Annuler/Relancer, résultats Download/Upload/Latence/date,
  liaison Wi-Fi, notice d'impossibilité et liens externes manuels.
- Liaison Wi-Fi en lecture seule : débit négocié (`NetworkCapabilities` downstream, fallback
  `WifiInfo` rate), RSSI + `SignalBars`, fréquence + bande, standard Wi-Fi 5/6/6E/7 ; valeur
  inconnue = « indisponible », jamais 0.
- Historique local `SpeedTestPreferencesStore` (consentement + 20 résultats, 30 jours) ; aucun
  mot de passe, session, token, MAC, SSID ou IP publique stockée.
- Moteur réel désactivé : l'utilisateur voit « indisponible » avec l'explication et les liens
  externes ; aucun octet ne quitte le téléphone. `FakeSpeedTestEngine` compte les volumes,
  honore les plafonds et démontre les états.
- Routage Wi-Fi : un test ne démarre que si `LocalNetworkStatus.LocalWifi` (hotspot exclu),
  annulé sur `NoWifi`/`NotOnWifi`/changement d'`identity.key` — aucun fallback mobile.

## 3. Algorithmes

`Mbps = octets×8 / secondes / 1e6` (horloge monotone), latence = médiane de 5 sondes
(mitige les pics), jauge = palier rond au-dessus de la valeur (10/25/50/100/250/500/1000),
gaz stable testé par `SpeedTestCalculatorTest`.

## 4. Erreurs, protections, garde-fous

Single-flight (`Mutex` + `Job` + `generation`), annulation coopérative (`ensureActive`,
`Call.cancel` quand un moteur réseau existera), plafonds globaux comptés retry inclus
(compteur atomique), timeouts 8/10/10/30 s, **aucun** test auto/périodique/arrière-plan,
1 test à la fois, consentement avant premier test (politique + données + consommation affichées),
`ACCESS_WIFI_STATE`/`ACCESS_FINE_LOCATION` jamais demandées.

## 5. Sous-agents (4, lecture seule, intégrés par l'agent principal)

| Agent | Apport côté implementation |
|---|---|
| 1 Recherche | Comparatif NDT7/Ookla/Fast/Cloudflare/HTTP ; moteur désactivé ; algorithme futur |
| 2 Android | `SpeedTestRepository` single-flight, `WifiNetworkProvider`, coroutines/cancel, DI |
| 3 UI/UX | Dashboard hero (sans nouvel onglet), jauge, 11 états, Material 3/contraste/talkBack |
| 4 Sécurité/QA | Plafonds globaux, Wi-Fi strict, TLS/privacy, V/S plan, halting gate, crise R1–R7 |

## 6. Tests

- JVM : `SpeedTestCalculatorTest` (12), `SpeedTestEngineTest` (5 : disabled, fake, no-WiFi,
  perte, pas de mobile), `Mission12BoundaryTest` (6 : séparation, aucun endpoint en données,
  plafonds, pas de localisation, pas de secret, route protégée). Suite complète :
  `router-core` + `poc-cli` + `app` — 339 tests, 0 échec.
- Instrumentés : `SpeedTestInstrumentedTest` (5 : prêt sombre, en cours clair, erreur sombre,
  résultat sombre, bouton cliquable) + captures Pixel 7 Pro falsifiées (`docs/screenshots/mission-12/`).
- Non-régression : `fake_router_scenarios.py` 10/10, `fake_router_expiry_scenarios.py` 3/3,
  `DevicesListInstrumentedTest` 14 OK, `SessionExpiryInstrumentedTest` 4 OK (session expirée).

## 7. Limites restantes

- Aucune mesure Internet réelle : procédure manuelle dans `MISSION_12_DEVICE_VALIDATION.md`.
- Liaison : un seul débit (pas de split Rx/Tx natif via `WifiInfo`) ; omis si indisponible.
- Jauge constante 0,5 de progression (valeurs moteur non branchées en prod).
- Historique : pas d'écran de suppression dédié (effet sur logout, purge 30 jours à l'écriture).
- `AndroidManifest.xml` : `INTERNET` déjà requis pour le trafic routeur ; rien ne sort du LAN
  tant que le moteur est désactivé.

## 8. État de la PR

Branche `feature/mission-12-speedtest` vers `develop`, captures dans `docs/screenshots/mission-12/`.
Aucun merge sans approbation.
