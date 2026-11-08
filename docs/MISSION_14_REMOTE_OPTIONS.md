# Mission 14 — Options distantes (étude sans déploiement)

> Date : 2026-11-08 · Statut : **étude uniquement** — aucun serveur public contacté,
> aucun transfert de mesure envoyé, aucun compte créé, aucune infrastructure déployée.
> Objectif : autoriser, le cas échéant, une mesure Internet réelle depuis le Niger
> sans jamais embarquer un endpoint de tiers dans la release.

## 1. Rappel des contraintes (missions 12/13/14)

- Release : `SPEEDTEST_BASE_URL=""` → `DisabledSpeedTestEngine` (zéro octet).
  Toute mesure réelle exige un endpoint **HTTPS valide**, **configuré** et
  **consenti**. Aucun endpoint n’est codé en dur dans `data/`.
- Confidentialité : le serveur de mesure voit l’IP publique du Wi-Fi pendant le test
  (mention explicite du consentement). Aucune persistance d’IP (`getIP.php`
  jamais appelé, `telemetry.php` jamais appelé, historique local sans IP).
- Plafonds : `20 Mo download / 10 Mo upload / 30 s` par test (`SpeedTestPolicy`).
  Sur lien rapide, le plafond s’atteint avant stabilisation → affichage `≥` + message
  « Plafond atteint avant stabilisation », jamais un chiffre inventé.
- Aucune revendication d’équivalence avec WiFiman / FAST.com / Ookla sans campagne
  comparative (même chemin, même serveur, même jour, facteurs documentés).

## 2. Option A — Serveur LibreSpeed public explicitement autorisé pour un client tiers

**Description.** Utiliser un endpoint `https://…/garbage.php` + `empty.php`
opéré par un tiers (ex. listes communautaires `librespeed.org` / GitHub
`librespeed/api` de pointeurs, ou instanciations publiques de référence).
Le client Kotlin y envoie ses plafonds (20/10 Mo) sans `getIP.php`.

**Autorisation.** C’est le point bloquant. Les instances publiques ne sont pas
des services de capacité publique : bande passante consommée sans quota contractuel,
ToS de courte durée, listes volatiles, sponsors imprévisibles. La doc officielle
présente LibreSpeed comme **auto-hébergé** (`⚙ Self-hosted`, LGPL), pas comme
une API gratuite. Aucun texte de la documentation Docker n’autorise une
consommation agrégée par une app distribuée. Rejouer ce schéma en release
exposerait le projet à (a) une bande passante de tiers non contractuelle,
(b) une liste cassante sans préavis, (c) une télémétrie du tiers sur `results/`.

**Localisation / latence depuis le Niger.** Les instances publiques connues sont
principalement en Europe / Amérique du Nord. Depuis Niamey, la latence aller-retour
vers l’Europe occidentale serait typiquement de l’ordre de 80–180 ms (vs < 10 ms
en LAN), avec gigue de routage satellite/fibre selon le FAI. Le moteur mesure
alors plus la distance que le débit d’accès : les résultats sous-estiment le
lien local et deviennent instables entre runs. À titre indicatif uniquement
(et non mesuré pendant cette mission — aucun contact).

**Capacité / disponibilité / confidentialité.** Capacité inconnue et partagée
avec d’autres clients, pas de SLA, journaux et `results/` possiblement conservés
par le tiers (l’IP publique est visible du serveur pendant la mesure).
L’app ne peut pas auditer ces journaux. Aucun secret ne part dans les requêtes
(certificat TLS validé), mais l’identité réseau reste exposée au tiers.

**Coût / maintenance / bande passante.** Coût externe externalisé (contesté
légalement), zéro maintenance locale, mais bande passante consommée sur le tiers
(même plafonnée à 30 Mo/test) : difficile à planifier, source de bannissement
éventuel si l’app des millions de requêtes. La mesure ne correspond pas à la
« dernière mille » (serveur distant, pas de edge local) : inadéquat pour un
diagnostic d’accès Wi-Fi.

**Verdict A : rejetée pour la release.** Ne peut être envisagée que pour une
expérience consentie, ponctuelle, avec l’autorisation écrite de l’opérateur
identifié, un certificat valide, un rate-limit documenté, et la preuve que les
journaux côté serveur ne collectent rien (démonstration opérateur). Aucun
contact n’a été tenté.

## 3. Option B — Infrastructure distante déjà contrôlée ou accessible légalement

**Description.** Université, entreprise, association ou hébergeur local qui
accepte contractuellement de répondre à `empty.php`/`garbage.php` pour ce
projet (backend auto-hébergé autorisé, `MODE=backend`, `TELEMETRY=false`,
`DISABLE_IPINFO=true` recommandé) et qui publie son certificat. Aucune
infrastructure n’a été contactée ou déployée.

**Autorisation.** Contrôlée par écrit : opérateur nommé, URL HTTPS, quota
(bande passante, débit, fréquence), conservation des journaux (réponse exigée :
aucune), droit de révocation. Sans cet écrit, l’option reste hypothétique.

**Localisation / latence.** Une infrastructure au Niger ou en Afrique de l’Ouest
(Lagos, Abidjan, Accra) donnerait une latence bien inférieure à l’Europe
(≈ 30–60 ms possibles selon le routage local) et une mesure plus proche de la
« dernière mille » que les serveurs nord-américains. Un serveur en Europe
reste mesurable pour du diagnostic long trajet, mais pas pour du débit d’accès.

**Capacité / disponibilité / confidentialité.** Capacité connue (quota contractuel),
disponibilité selon le contrat (vs clé), confidentialité traçable (journaux
sous contrôle écrit, `getIP.php` inactif côté client). L’IP publique reste visible
du serveur pendant la mesure (nature du protocole), mais sans persistance.

**Coût / maintenance / bande passante.** Coût assumé par l’opérateur (mutualisé
ou facturé), maintenance à l’opérateur, quota de bande passante à respecter côté
app (le plafond 30 Mo/test borne la consommation). Risque : le point unique
devient une panne unique ; prévoir un repli `Disabled` propre (déjà implémenté).

**Verdict B : seule voie distante viable** — et uniquement sur **autorisation
contractuelle écrite** + certificat valide + reverse-proxy avec rate-limit côté
serveur. Elle préserve le modèle Mission 13 (« endpoint auto-hébergé configuré,
désactivé par défaut, HTTPS, pas de télémétrie ») sans consommer la bande
passante d’un tiers anonyme. À instruire négociation par négociation, jamais
par défaut.

## 4. Option C — Hébergement privé éventuel (évaluation coût/performance uniquement)

**Description.** Location d’un petit VPS pour héberger LibreSpeed en mode
`backend` (ou `standalone` si une UI de vérification manuelle est voulue),
derrière un reverse-proxy Caddy/nginx avec certificat Let’s Encrypt ou CA privée,
`TELEMETRY=false`, `DISABLE_IPINFO=true`, `MULTISTREAM` désactivé côté client
(l’app reste mono-flux — pas de multistream côté client à absorber).

**Coût (évaluation indicative, non déployée).** Les offres d’entrée de gamme
taille `1 vCPU / 1 Go / 20 Go` (Hetzner CX, Scaleway Start, Contabo S, OVHcloud,
DigitalOcean Basic) se situent généralement entre **≈ 3–7 €/mois**,
avec des variantes africaines/asiatiques (ex. Lagos, Johannesburg, Dubaï,
Bahreïn) parfois plus chères mais plus proches. Ce sont des ordres de grandeur
2026 à revalider au moment de l’achat ; aucun compte n’a été créé et aucune
souscription n’existe. Au-delà du loyer, prévoir : domaine/Let’s Encrypt
(gratuit), temps d’exploitation et sauvegarde des journaux `results/` si activés
(ici **désactivés**).

**Performance / localisation depuis le Niger.** Un VPS à Paris/Francfort
offrirait ≈ 90–150 ms aller-retour depuis Niamey (variable selon le FAI), à comparer
à < 10 ms en LAN : acceptable pour un diagnostic « longue distance »,
impropre à une mesure « dernier kilomètre ». Un point en Afrique de l’Ouest
réduirait la latence mais seules quelques offres y existent, souvent moins
prévisibles en bande passante. Dans tous les cas, la mesure reflète le trajet
complet (Wi-Fi + FAI + transit Internet), pas le seul Wi-Fi : la docs l’indiquera
explicitement plutôt qu’un chiffre nu.

**Capacité / disponibilité / confidentialité.** Capacité dimensionnable (quota
mensuel de transfert à surveiller), disponibilité = SLA de l’hébergeur (pas de
HA locale), confidentialité totale (journaux sous contrôle, `results/` absents).
Risque principal : exposition publique de `garbage.php` sans rate-limit =
cible d’abus/amplification. **Un reverse-proxy avec rate-limit par IP, un
quota, et des `fail2ban`-like sont obligatoires** avant toute exposition
(voir sous-agent Réseau §4.4–4.5). Sans eux, l’instance devient un amplifyur.

**Maintenance.** Mises à jour de l’image officielle (pin + re-tirage contrôlé),
renouvellement TLS, surveillance logs, sauvegarde éventuelle de la DB SQLite
(télémétrie désactivée → rien à sauvegarder). Charge récurrente à budgéter :
quelques heures par mois pour un back-office sans équipe dédiée.

**Verdict C : évaluable, non retenue à ce stade.** Aucune création de compte,
aucun déploiement, aucune facture. Si une mesure distante devient nécessaire,
l’étape suivante serait : (1) chiffrage écrit du besoin (fréquence des tests,
quota mensuel), (2) choix de région (Afrique de l’Ouest prioritaire, Europe
en repli), (3) appel d’offres sur 2–3 fournisseurs avec comparatif
« coût total / latence / capacité / maintenance », (4) reverse-proxy +
rate-limit + certificat avant tout run réel. L’alternative la moins coûteuse
reste le **serveur local contrôlé Mission 14** (voir §5).

## 5. Recommandation

1. **Court terme (Mission 14)** : ne pas aller au-delà du serveur local
   (`infra/librespeed-local`, boucle + `adb reverse` + profil TLS loopback).
   Aucune dépense, aucun risque tiers, latence LAN maîtrisée, métrologie validée
   en JVM (405 tests) et sur Pixel (instrumentés verts, sans trafic réel).
2. **Moyen terme** : si un débit Internet réel devient indispensable,
   instruire **l’option B** (infra contrôlée contractuelle) en priorité ;
   envisager l’**option C** (VPS privé) seulement avec un budget et un plan de
   maintenance. **Rejeter l’option A** (tiers public non contractuel) dans la
   release.
3. Garde-fous inchangés : `Disabled` par défaut, HTTPS, `followRedirects=false`,
   plafonds 20/10/30, aucune télémétrie, consentement explicite. Tout futur
   run réel enverra ≤ 30 Mo et affichera `≥` si le plafond est atteint.

## 6. Sources consultées (sans transfert de mesure)

- Documentation officielle Docker : `https://github.com/librespeed/speedtest/blob/master/doc_docker.md` (modes `standalone`/`backend`/`frontend`/`dual`, variables `TELEMETRY`, `DISABLE_IPINFO`, `WEBPORT`, docker compose).
- LibreSpeed (librespeed.org / GitHub `librespeed/speedtest`) : outil **auto-hébergé**, LGPL — pas une API de capacité publique.
- M-Lab NDT7 (`measurementlab.net`, `github.com/m-lab/ndt-server`) : plateforme de mesure ouverte avec protocoles publishés, consommation ciblée et APIs publiques — pertinent pour comparer les protocoles, pas pour embarquer un endpoint tiers en release (plafonds et consentement différents).
- Comparatifs publics (Ookla / NDT7 / Cloudflare Speed Test) : convergent en laboratoire, divergent en latence élevée — rappel que la comparaison exige même chemin, même serveur, même jour.
- Tableaux de prix VPS “public entry” (Hetzner, Scaleway, Contabo, OVH, DigitalOcean) : repérés via guides publics (2025–2026) — ordres de grandeur uniquement, **aucun compte n’a été créé** et aucun déploiement réalisé.
- Docs internes Mission 13 : `MISSION_13_LIBRESPEED_RESEARCH.md` (protocole, télémétrie désactivable, `results/` supprimable, `getIP.php` non appelé), `MISSION_13_METROLOGY.md` (formule `×1.0`, plafonds, jitter), `MISSION_13_SECURITY_REVIEW.md` (§5.2/§5.3 : auto-hébergé seul viable).

*Aucun serveur tiers n’a été interrogé ; aucun benchmark produit ni déployé.*
