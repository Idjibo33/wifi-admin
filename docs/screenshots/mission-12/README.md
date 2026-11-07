# Captures Mission 12 — Test de vitesse

> **Données de démonstration — jamais une mesure réelle.**
> Moteur simulé falsifié (`Moteur simulé (fictif)`), fixtures `sample-*`, `192.168.1.10`,
> MAC `aa:bb:cc:dd:ee:*`. Aucun routeur contacté, aucun mot de passe, aucun serveur tiers.

## Fichiers

| Fichier | État |
|---|---|
| `ready-sombre.png` | Prêt (moteur désactivé en prod ; ici simulé avec liaison) |
| `running-clair.png` | En cours (Download, 42 Mbit/s) |
| `error-disabled-sombre.png` | Écran réel de prod : moteur désactivé + liens externes |
| `result-sombre.png` | Terminé falsifié 42,5 / 18,2 / 24 ms |

Capturées sur Pixel 7 Pro `2B091FDH3004DG` (Android 17) via `SpeedTestInstrumentedTest`
(2026-11-07 ~19 h). Harnais et procédure : `docs/MISSION_12_DEVICE_VALIDATION.md`.
