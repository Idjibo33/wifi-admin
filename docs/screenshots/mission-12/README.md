# Captures Mission 12 — Test de vitesse

> **Données de démonstration — jamais une mesure réelle.**
> Moteur simulé falsifié (`Moteur simulé (fictif)`), fixtures `sample-*`, `192.168.1.10`,
> MAC `aa:bb:cc:dd:ee:*`. Aucun routeur contacté, aucun mot de passe, aucun serveur tiers.

## Fichiers

| Fichier | État |
|---|---|
| `ready-clair.png` | État initial réel : bandeau « indisponible » en tête, jauge « — », bouton désactivé (carte de liaison sous la ligne de flottaison) |
| `running-clair.png` | Démonstration en cours (Download, 42 Mbit/s) |
| `error-disabled-sombre.png` | Écran de production : moteur désactivé + liens externes |
| `result-sombre.png` | Terminé falsifié 42,5 / 18,2 / 24 ms, bouton Relancer désactivé |

Capturées sur Pixel 7 Pro `2B091FDH3004DG` (Android 17) via `SpeedTestInstrumentedTest`,
régénérées le 08/11/2026 depuis le code courant (revue corrective incluse). **Maquettes de
démonstration, pas des mesures** : les états « en cours » et « terminé » ne sont pas
atteignables en production tant que le moteur est désactivé ; le bandeau d'indisponibilité y
reste donc visible. Harnais et procédure : `docs/MISSION_12_DEVICE_VALIDATION.md`.
