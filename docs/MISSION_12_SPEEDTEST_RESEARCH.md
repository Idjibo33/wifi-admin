# Mission 12 — Recherche : moteur de test de vitesse Internet

> Date : 07/11/2026 · Branche `feature/mission-12-speedtest` (base `bd154c7`, Mission 11 fusionnée).
> Méthode : étude M-Lab NDT7 v0.11.0, AUP/Privacy M-Lab, Ookla Terms, Fast.com FAQ, Cloudflare speedtest (JS) —
> §10 liste les sources. Aucun endpoint n'a été appelé pendant l'étude.

## 1. Question

Mesurer download / upload / latence via le Wi-Fi du téléphone, par un moteur **autorisé,
fiable et contrôlable** sous plafonds **20 Mo / 10 Mo / 30 s**, sans inventer d'endpoint ni
confondre débit de liaison et débit Internet, depuis le Niger.

## 2. Comparatif

| Critère | M-Lab NDT7 | Ookla SDK | Fast.com | Cloudflare | HTTP générique |
|---|---|---|---|---|---|
| Précision | Goodput 1×TCP, métrologie ouverte | Industrielle (multi-flux, IQM) | DL seul ≈25 Mo×N | Percentiles, ramp-up × tailles | Souvent sous-estimé |
| Fiabilité | Best-effort, `204` hors capacité | Contrat | Aucun engagement tiers | Aucun engagement natif | Aucune garantie |
| Niger | Serveur éloigné probable (Europe/NA) | 15 000 serveurs revendiqués | OCA FAI | Anycast | Aucun serveur |
| ToS tiers | **Autorisé** (consentement préalable, politique GDPR, IP publiée CC0 indéfiniment, 40/j interactifs, contribution commerciale) | Licence entreprise, usage perso, pas de dérivé | « Not designed or supported for 3rd party use » | Terms §7 : pas de contournement, token expérimental | Aucune autorisation |
| Coût | Gratuit, contribution attendue | Contrat | Site seul | Site seul | 0 € sans serveur |
| Android | Kotlin communautaire non officiel ; spec simple en WebSocket/TLS | SDK officiel | Pas de SDK | JS navigateur seul | OkHttp pur |
| Privacy | IP publique publiée à jamais — incompatible LAN-only privé par défaut | Télémétrie riche | IP réseau Netflix | IP tronquée | Contrôlable si auto-hébergé |
| Plafonds 20/10/30 s | Temps OK ; octets côté client seulement ; >16 Mbps le plafond coupe avant stabilisation | Non (saturation 10 Gbps) | Non (25 Mo×N) | Oui en JS, non en Kotlin | Oui si serveur possédé |

Seul M-Lab est explicitement autorisé pour un tiers, au prix de publications permanentes d'IP
et d'une contribution. Aucun autre endpoint n'est à la fois autorisé et contrôlable :
l'inventer ou copier celui d'un tiers serait un détournement (spec + ToS ci-dessus).

## 3. Plafonds : pourquoi 20 Mo ne mesure pas les litres rapides

M-Lab (étude plafonds 2023) vise 250 Mo pour 200 Mbps en 10 s. À 20 Mo, 160 Mbit en 10 s :
au-delà de ~16 Mbps DL (8 Mbps UL), la mesure s'arrête avant stabilisation BBR et sous-estime
systématiquement. La UI marque alors un résultat plafonné « ≥ X » plutôt qu'un Mbps précis.
Ookla, Fast et Cloudflare ignorent les plafonds par méthodologie ou par droit.

## 4. Algorithme (prêt pour un futur moteur)

Warm-up 2 s écarté, fenêtres 250 ms, `Mbps = octets×8 / secondes / 1e6` sur la fenêtre stable,
latence = médiane de 5 sondes (jamais min/max), handshake WS et `TCPInfo.MinRTT` nommés comme
tels (jamais « ping ICMP »), fermeture anticipée sur plafond/durée, `capped` affiché.

## 5. Fournisseur choisi : aucun — moteur réel désactivé

**Aucun endpoint n'est simultanément fiable, autorisé et contrôlable sous nos plafonds
depuis le Niger.** Ookla/Fast/Cloudflare sont interdits sans licence ; le HTTP générique
n'a pas de serveur légitime ; M-Lab impose publication d'IP et plafonds relevés. Plutôt que
fabriquer un Mbps, la mission livre :

- le moteur désactivé (`DisabledSpeedTestEngine`, zéro appel réseau),
- l'architecture testable (`SpeedTestEngine`, `FakeSpeedTestEngine`, repository single-flight,
  `SpeedTestPolicy`, `SpeedTestCalculator`),
- la liaison Wi-Fi (`WifiLinkInfoProvider` → débit négocié, RSSI, bande, standard ; jamais 0,
  jamais confondu avec Internet),
- l'UI, l'historique local borné (20 entrées, 30 jours) et les tests.

Déblocage futur : voie A (serveur auto-hébergé `ndt-server`, TLS, anti-abus) ou voie B
consentie M-Lab (politique GDPR, consentement, plafonds ≥100–250 Mo, mention « vers serveur
M-Lab », contribution). Sans ces prérequis écrits, le test réel reste désactivé.

## 6. Consommation, confidentialité, risques

30 Mo/test en prépayé, serveur éloigné (chemin international, pas « dernière mille »),
opérateur/CGNAT/PEP, `204` hors capacité, publication IP M-Lab (RGPD), polling automatique
interdit (limites M-Lab et facture). L'écran affiche la consommation potentielle avant tout
test et ne télécharge jamais en arrière-plan.

## 7. Liens externes (navigables par l'utilisateur, jamais appelés par l'app)

`speed.measurementlab.net`, `speedtest.net`, `speed.cloudflare.com`, `fast.com` — fournis
dans l'écran comme substituts manuels. L'app ne les contacte pas (voir `Mission12BoundaryTest`).

## 8. Justification architecturale

Le moteur Vit dans `data/speedtest/` (pas dans `router-core`, pas dans `RouterAdapter`) :
aucun `cmd`, aucune session, aucun token. `INTERNET` + `ACCESS_NETWORK_STATE` déjà déclarés ;
aucune permission de localisation n'est demandée pour la liaison (dégradation gracieuse
`SecurityException`).

## 9. Choix à valider produit

Accepter la publication d'IP (voie B) ou financer un serveur (voie A) ; dans tous les cas
relever les plafonds pour les liens rapides. En attendant, la liaison Wi-Fi et les liens
externes restent la mesure utile.

## 10. Sources

NDT7 spec v0.11.0, clients Go/JS/Android communautaire, Locate v2, AUP + Privacy + Developer M-Lab,
page plafonds, Ookla Terms + Linking + SDK + Methodology, Fast.com FAQ + Netflix TechBlog,
Cloudflare `@cloudflare/speedtest` + speed.cloudflare.com + Website Terms §7,
`docs/ROUTER_PROTOCOL.md` F4/F6/H5, `AndroidManifest.xml` (commentaire LAN), dépôt OkHttp 4.12.0.
