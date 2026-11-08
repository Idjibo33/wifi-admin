# Mission 13 — Protocole et métrologie LibreSpeed

> Date : 08/11/2026 · Branche `feature/mission-13-speedtest-reel` · Dépôt `wifi-admin-app`.
> Statut : **proposition métrologique** (aucun code modifié par cette mission). Seuls `docs/MISSION_13_LIBRESPEED_RESEARCH.md`, `docs/MISSION_13_ARCHITECTURE.md`, le code source `data/speedtest/*.kt` et le dépôt officiel `librespeed/speedtest` (lecture de code/docs uniquement) ont été consultés. Aucun appel vers un endpoint de mesure n'a été effectué — ni `garbage.php`, ni `empty.php`, ni `getIP.php`, ni `telemetry.php`.
> Contraintes produit intangibles : plafonds **20 Mo DL / 10 Mo UL / 30 s** (SpeedTestPolicy.kt), compteur d'octets **réels** lus/écrits (jamais `Content-Length`, jamais `maxBytes` supposé), réponses incomplètes gérées, aucun appel réseau en test (MockWebServer loopback `127.0.0.1` uniquement), aucune revendication de précision équivalente à WiFiman / FAST.com sans validation comparative.
> Skills appliquées : `systematic-debugging` (cause racine avant correction, hypothèses testables) et `tdd` (tests aux seams publics S1–S4 de `MISSION_13_ARCHITECTURE.md`, valeurs attendues littérales et indépendantes, tranches verticales une test → une implémentation).

---

## 1. Diagnostic — cause racine des quatre problèmes

Conformité `systematic-debugging` Phase 1 : on ne corrige que ce qui est tracé.

### 1.1 Download = un seul GET `ckSize=1` (~1 Mio), insuffisant sur liaison rapide

**Observé** (`LibreSpeedClient.kt:89-124`) :

```kotlin
.url("${endpoint.baseUrl}/garbage.php?ckSize=1&r=${Random.nextInt(1_000_000)}")
// boucles jusqu'à maxBytes, mais un seul response sur toute la phase
```

- `garbage.php` officiel renvoie exactement `ckSize × 1 048 576 octets` de `openssl_random_pseudo_bytes` incompressible (cf. `backend/garbage.php` lu le 08/11/2026). Avec `ckSize=1`, le serveur ne peut servir que **1 Mio au maximum**.
- Conséquences : sur un lien Wi-Fi à 50 Mbps ou plus, 1 Mio est transféré en < 200 ms. Le débit mesuré est alors dominé par la connexion TCP/TLS, le ramp-up de la fenêtre de congestion (TCP slow-start) et la résolution du timer — pas par l'état stable de la liaison. Aucune fenêtre de mesure stabilisée n'existe. Le résultat est **systématiquement sous-estimé et non reproductible** sur liaison rapide, et il contredit la sémantique `capped=true` (« plafond atteint avant stabilisation ») : ici, l'arrêt n'est pas le plafond, c'est l'épuisement du corps.
- Cause racine : **volume unique trop petit**, pas un défaut de formule. La formule `SpeedTestCalculator.mbps = octets × 8 / durée × 1e6` est correcte ; elle est appliquée à un volume qui ne contient pas d'état stable.
- Divergence avec le protocole officiel : le worker JS demande `garbagePhp_chunkSize=100` (= 100 Mio/requête) × `xhr_dlMultistream=6` flux relancés en boucle (cf. recherche §3.3). Le client actuel ne reprend ni le multistream, ni la boucle « relance à `onload` ».

### 1.2 Upload = corps de zéros, comptage supposé

**Observé** (`LibreSpeedClient.kt:126-168`) :

```kotlin
val chunk = ByteArray(65_536) // 64 KiB de zéros, réutilisé
sink.write(chunk, 0, n)       // zéro jamais rempli de données aléatoires
...
onProgress(maxBytes)           // compté après le 200, sans compter les écritures
return maxBytes
```

- Un tampon de zéros est **compressible à l'extrême** (ratio > 100:1 sur tout compresseur générique). Même si TLS chiffre après compression applicative, tout intermédiaire qui compresse en clair (proxy de test, VPN à déduplication/compression, CGNAT avec optimisation, réécriture serveur) peut réduire les octets réellement transmis sur le fil. Le client compte alors un volume que le réseau n'a peut-être pas vu. Le client officiel et le CLI Go envoient du **pseudo-aléatoire incompressible** (`Uint32Array` + `Math.random() * 2^32` côté JS ; payload ChaCha8 côté CLI) avec `Content-Encoding: identity`.
- Deuxième défaut, plus grave : `upload()` **ne compte pas les octets écrits**. Il écrit `maxBytes` zéros puis déclare `onProgress(maxBytes)` / retourne `maxBytes` après le `200`. Si l'écriture est interrompue à 40 %, le résultat affiche quand même 10 Mo. C'est l'inverse du contrat « compteur d'octets réels » (`MISSION_13_ARCHITECTURE.md` §6.1) et rend le backstop du repository inopérant sur l'upload.
- Cause racine : **corps compressible + compteur supposé**, pas un défaut de parallélisme. L'upload actuel est séquentiel (1 flux), ce qui est souhaitable — il ne faut pas changer ça, il faut changer le contenu et le comptage.

### 1.3 Métadonnées initiales incomplètes (latence / jitter / latence sous charge / durée / serveur / consommation)

État actuel (`SpeedTestResult.kt:11-23`) :

| Champ | État | Cause |
|---|---|---|
| `latencyMillis` | présent (`medianLatencyMillis` sur 5 sondes) | OK — seam S2 testé. Ce n'est **pas** un ping ICMP : c'est un temps de réponse HTTP. À nommer comme tel dans l'UI. |
| jitter | **absent** (aucun champ, aucun calcul) | Le worker JS et le CLI Go mesurent un jitter à partir des mêmes sondes que la latence. Sans définition formelle, tout affichage serait inventé. |
| latence sous charge (bufferbloat) | **absente** | Aucun protocole LibreSpeed ne la définit ; elle exigerait des sondes pendant le transfert. Une valeur approximative serait mensongère (voir §5.2). |
| `durationMillis` | présent (durée totale monotone) | OK, mais ambigu : ne dit pas quelles fenêtres ont été exclues (warm-up). À documenter précisément. |
| serveur | `providerLabel = "LibreSpeed — vers <hôte>"` | OK à condition de ne montrer que l'**hôte** (jamais l'URL complète, jamais l'IP publique, jamais l'ISP). |
| consommation | `downloadBytes` + `uploadBytes` réels (DL seulement) | OK côté DL ; fausse côté UL (voir §1.2). Doit être la somme affichée en Mo sur l'écran, consommée **pendant** le test (warm-up inclus). |

### 1.4 Aucune revendication de précision sans validation comparative

- Le facteur JS `overheadCompensationFactor=1.06` (surcoût HTTP/réseau) **n'est pas** appliqué côté WiFi Admin (`mbps` sans facteur). Écart attendu d'environ 6 % avec le worker JS. Toute comparaison chiffrée avec WiFiman, FAST.com, Ookla ou LibreSpeed navigateur sans campagne comparative sur le même chemin serait non probante (chemin différent, facteur différent, serveurs différents, plafonds différents).
- Dessus de ~16 Mbps, le plafond 20 Mo coupe avant stabilisation BBR et **sous-estime systématiquement** (recherche Mission 12 §3, repris architecture §7). Un chiffre plafonné n'est jamais un débit maximal.
- Cause racine : l'absence de règles d'affichage explicites. Ce document les fixe comme contrat (§6).

---

## 2. Principes métrologiques (non négociables)

1. **On ne compte jamais du supposé.** Seuls les octets réellement lus (`source.read`) ou réellement écrits (`sink.write`) alimentent le compteur. `Content-Length` n'est jamais consulté. `maxBytes` n'est jamais renvoyé comme résultat.
2. **Le plafond est une fermeture, pas une estimation.** Dès que le total du sens atteint le plafond, le flux est fermé (`call.cancel()` / fin de `writeTo`) et le surplus n'est jamais lu ni écrit. Le dépassement maximal est borné par la granularité de lecture écrite (voir §3.3, réservation atomique).
3. **Le warm-up est exclu du débit mais inclus dans le plafond et dans le budget temps.** Un prépayé paye les octets même pendant le warm-up ; ne pas les compter au plafond mentirait sur la consommation.
4. **Chaque phase est mesurée sur sa propre fenêtre monotone.** La latence ne dilue jamais le débit (décision architecture §4). Le Mbps d'une phase = `octets_de_la_fenêtre × 8 / durée_de_la_fenêtre × 1e6` (`SpeedTestCalculator.mbps`, inchangée, sans facteur 1.06 — écart documenté, voir §6).
5. **Une réponse courte n'est pas une erreur, c'est un signal.** Le serveur LibreSpeed coupe à `ckSize × 1 Mio`. Le client doit **relancer** (boucle download/upload du worker JS) tant qu'il reste du budget. Seule une suite d'échecs ou un budget épuisé produit `Incomplete` / `ServerUnavailable` / `Timeout`.
6. **Une fenêtre trop courte n'est jamais un débit de confiance.** Si `fenêtre < 1 000 ms` ou `octets == 0`, on ne publie pas un Mbps trompeur : `isTooShortToTrust` reste le garde-fou (voir §3.4).
7. **Tests aux seams, pas aux détails.** S1 = `LibreSpeedEngine.run()` contre MockWebServer ; S2 = fonctions pures `SpeedTestCalculator` / `SpeedTestEndpointValidator`. Aucun test ne mocke `OkHttpClient`, aucun ne recalcule attendu avec la même formule que le code (anti-tautologie `tdd`) ; toutes les durées/seed sont contrôlées par horloge et dispatcher injectés.

---

## 3. Download multi-segments borné

### 3.1 Paramètres proposés

| Paramètre | Valeur | Justification |
|---|---|---|
| `ckSize` par requête | **2** (soit 2 Mio/réponse serveur) | Le serveur LibreSpeed ne sert que par multiples de 1 Mio (`openssl_random_pseudo_bytes(1048576)` × `ckSize`). `ckSize=1` (actuel) donne une granularité de 1 Mio mais un seul segment avant EOF ; `ckSize=2` double la taille utile par requête sans jamais dépasser le plafond d'un coup (20 Mo / 2 Mio = 10 segments max). Alternative `ckSize=3..5` rejetée : granularité de dépassement trop grossière et un seul segment consomme 25 % du plafond. |
| Flux parallèles `N` | **3** | Compromis : le worker JS utilise 6, l'architecture §6.4 borne à 4. 3 flux saturent un lien Wi-Fi grand public sans multiplier les appels à fermer en cas de `cancel()`. L'architecture D2-P1 propose N=4 dès T3 ; §3.3 montre que N=3 ou 4 ne changent pas le compteur grâce à la réservation atomique. **Arbitrage : N=3** (moins de rafales CGNAT, même précision, un appel de moins à annuler). |
| Buffer de lecture | **64 KiB** | Déjà utilisé ; aucun changement. `ensureActive()` à **chaque buffer** (pas seulement chaque fenêtre 250 ms), sinon un `cancel()` met jusqu'à 250 ms à réagir (architecture §9 règle 6). |
| `r` anti-cache | nouveau `Random.nextInt()` **par requête** | Le worker ajoute `"r=" + Math.random()` à chaque requête. Garder une seule valeur pour toute la phase (état actuel) risquerait un cache intermédiaire. Changer à chaque relance, sans jamais journaliser. |
| `Accept-Encoding: identity` | en-tête à poser (download + upload) | Réplique la protection du worker JS / CLI Go contre la compression intermédiaire du corps download. |
| Warm-up (DL) | **2 000 ms écartés du calcul**, comptés au plafond et au budget | Le worker JS écarte 1.5 s (TCP window). 2 s est retenu (architecture D2) car le lien peut être LTE derrière Wi-Fi, où le slow-start dépasse 1.5 s. Coût sous plafond : sur un lien très lent, le warm-up consomme une part du 20 Mo mais apporte quand même sa mesure propre (fenêtre after-warm-up) ; sur un lien très rapide, le plafond peut être atteint pendant le warm-up → `capped=true` sur compteur total (voir §3.4). |
| Fenêtre de mesure (DL) | de `warmUpEnd` jusqu'à `stop`, **au moins 1 s**, au plus le budget (§3.3) | Voir calcul §3.4. |
| Fenêtre de progression | 250 ms (émission `Running`) | Déjà spécifiée. La progression est informative, le calcul final utilise les totaux exacts, pas les fenêtres sommées. |

### 3.2 Séquence (boucle LibreSpeed adaptée aux plafonds)

```
pour chaque flux f ∈ [0..N)  (lancés à 0 ms / 300 ms / 600 ms — décallage worker JS) :
    tant que budget_DL restant ET phase active ET non annulé :
        response = GET garbage.php?r=<neuf>&ckSize=2
        si code != 200 → ServerUnavailable (pas de retry auto, pas de followRedirects)
        stream = response.body.source()
        tant que octets_reservés < plafond :
            réserver ≤ 64 KiB via AtomicLong (voir §3.3)
            n = stream.read(buffer, 0, réservé)
            si n == -1 → fin du segment, fermer response, BREAK → relance (réponse courte normale)
            ajouter n au compteur global AtomicLong
            ensureActive() ; émettre onProgress si ≥ 250 ms écoulés
        fermer response (use {})
```

- Une réponse courte (EOF avant plafond) **n'est jamais une erreur** : le serveur coupe à `ckSize × 1 Mio` par construction. Le client relance un nouveau GET, exactement comme le worker JS relance chaque flux dès `onload`.
- Une réponse tronquée par le réseau (EOF prématuré < `ckSize × 1 Mio`, coupure TCP) est traitée de la même façon : les octets lus restent comptés, on relance. Seule une **suite** de causes distinctes produit une erreur (voir §3.4).
- Le retrait de flux terminé est remplacé par un nouveau flux sans attendre la fin des autres (le worker JS ne synchronise pas les flux à dessein, pour éviter qu'ils finissent en même temps). La seule synchronisation est le compteur global.

### 3.3 Arrêt : plafond, durée, annulation — ordre et bornes

| Condition | Mécanisme | Effet |
|---|---|---|
| **Plafond octets** (20 Mo) | Réservation atomique : chaque lecteur calcule `allowed = maxDownloadBytes - compteurRemainant` et ne lit que `min(64 KiB, allowed)`. Si `allowed ≤ 0`, le flux s'arrête sans lire. | Le total ne dépasse jamais 20 Mo, quel que soit le parallélisme. État `capped=true`. `call.cancel()` sur les flux restants. |
| **Durée phase DL** | Budget **12 s** (warm-up 2 s + mesure ≤ 10 s) vérifié à chaque buffer via horloge monotone. | Arrêt propre, `capped=false` si plafond non atteint. Débit calculé sur la fenêtre de mesure réelle. |
| **Budget total 30 s** | `withTimeout(30 s)` du repository (déjà en place) + `callTimeout=30 s` OkHttp (architecture §6.2). | Sécurité : le budget total = latence ≤ 5 s + DL 12 s + UL 12 s + 1 s finalisation. Toute phase qui déborde est tuée ; le backstop du repository coupe un moteur défaillant. |
| **Annulation** | `ensureActive()` par buffer + `call.cancel()` de tous les appels en vol (ensemble `ConcurrentHashMap`, `cancel()` idempotent et thread-safe). | Réaction < 1 buffer ; le `finally` ferme sans suspendre ; un `cancel()` périmé n'atteint jamais un run successeur (architecture §9). |
| **Erreur réseau** | `IOException` / `SocketTimeoutException` / HTTP ≠ 200 → mapping tableau architecture §8 (`ServerUnavailable` / `Timeout` / `Incomplete`). `CancellationException` remonte telle quelle. | Aucun retry auto (validation §7.3 de la recherche : un retry compterait contre le plafond ; `xhr_ignoreErrors=0` — échec net). |

La granularité 64 KiB + réservation atomique **supprime le dépassement parallèle** (± `N × 64 KiB`) qu'aurait un compteur additionné après lecture. C'est ce qui rend le plafond « strict » même avec 3 flux.

### 3.4 Calcul du débit et fenêtres courtes

Deux compteurs sont tenus par phase : `totalBytes` (plafond + consommation affichée, warm-up inclus) et `measuredBytes` (octets lus **après** `warmUpEnd`). Deux instants monotones : `warmUpEnd` et `stop`.

- **Cas nominal** (plafond non atteint pendant le warm-up, fenêtre ≥ 1 s) :
  `downloadMbps = SpeedTestCalculator.mbps(measuredBytes, stop - warmUpEnd)`
  avec `capped = false` si `totalBytes < maxDownloadBytes`.
- **Cas plafond atteint** (`totalBytes == maxDownloadBytes`) :
  dénominateur = `stop - warmUpStart` (durée totale de la phase, warm-up inclus), numérateur = `totalBytes`. Le résultat est un **plancher** : `capped=true`, affiché `≥ X`. Cas fréquent sur lien rapide : le plafond est atteint pendant le warm-up de 2 s (à 100 Mbps, 2 s ≈ 25 Mo > 20 Mo). Dans ce cas **aucune fenêtre stabilisée n'existe** et le plancher est le seul chiffre honnête. Le texte « Plafond atteint avant stabilisation » s'affiche.
- **Fenêtre trop courte** : si `fenêtre < 1 000 ms` ou `measuredBytes == 0` (réponse courte unique, lien coupé, serveur fermé), on applique `SpeedTestCalculator.isTooShortToTrust`. Règle :
  - si `totalBytes == 0` → `Incomplete` (rien mesuré).
  - si `0 < totalBytes < 1 Mio` et fenêtre `< 1 s` → `Incomplete`, pas `0.0 Mbps` mensonger.
  - sinon, on publie le plancher sur la durée totale avec `capped=true`.
  Le `0.0 Mbps` de `mbps(0, …)` n'est jamais présenté comme une preuve de lien nul sans contexte (un `0` peut aussi être un serveur injoignable déjà mappé en `ServerUnavailable`).
- **Réponses courtes répétées** : si 3 segments consécutifs renvoient 0 octet avec un code 200 (serveur corrompu, cache vide, backend autre que `garbage.php`), le moteur s'arrête avec `Incomplete` plutôt que de boucler jusqu'au timeout. Le seuil de 3 est un compromis : assez pour éviter un faux échec sur un glitch isolé, assez pour ne pas consommer 30 s sur un serveur vide.

### 3.5 Compatibilité MockWebServer

La boucle §3.2 est faite pour le seam S1 : MockWebServer sert des corps finis et déterministes sur `127.0.0.1` (loopback — seul cas où `http` est autorisé, validation §5.4). Chaque test alloue un `Dispatcher` qui route sur `path.contains("garbage.php")` (déjà le pattern de `LibreSpeedEngineTest.route`). Compatibilité :

- Un test qui sert exactement **1 000 000 octet** avec un plafond 5 Mo consomme un seul segment tronqué (EOF normal) et attend `downloadBytes == 1 000 000` exactement (tracer bullet T3 de l'architecture) — aucune hypothèse sur le parallélisme.
- Un test qui sert **10 000 000 octets** avec un plafond 2 Mo attend `downloadBytes == 2 000 000` exactement et `capped=true` (T4) — la réservation atomique rend l'égalité stricte, pas « ≤ + tolérance ».
- Aucun test n'utilise de vraies durées réseau : le `TestAppClock` et le `StandardTestDispatcher` (schéma déjà prouvé par `SpeedTestRepositoryLifecycleTest`) rendent `warmUpEnd` / `stop` déterministes. Les retards MockWebServer (`setBodyDelay`, `throttleBody`) ne servent qu'aux tests d'annulation, jamais aux mathématiques.

---

## 4. Upload incompressible, compté et annulable

### 4.1 Corps : générateur pseudo-aléatoire déterministe incompressible

- **Source d'entropie par chunk** : `java.util.Random(seed)` émetteur de pseudo-aléatoire. `Random` est **déterministe par graine**, donc testable et reproductible ; `SecureRandom` est exclu (lenteur, non reproductible, inutile ici — on ne prouve pas la sécurité, on prouve l'incompressibilité).
- **Graine** : constante de protocole `0x5EED...` (valeur exacte à figer en implémentation, jamais lue depuis l'horloge ou le réseau). Graine par flux : `baseSeed + streamIndex`, séquence continue **à l'intérieur** du flux (chaque chunk de 64 KiB = 2 097 152 tirages `nextInt()`, soit une entropie ≈ 64 KiB d'aléatoire).
- **Pourquoi c'est incompressible** : la sortie d'un PRNG de qualité est statistiquement indiscernable de l'aléatoire uniforme pour tout compresseur sans la graine. Un ratio de compression cible > 0.95 (à vérifier par test : ratio sur un corpus de zéros vs corpus PRNG, ce dernier doit rester proche de 1.0 — seuil 0.95 littéral, indépendant).
- **Régénération par bloc** : un `RequestBody` de 1 Mio possède `contentLength = 1 048 576` **fixe** (évite `Transfer-Encoding: chunké`, incompatible avec certains serveurs Rust — divergence CLI Go notée dans la recherche §3.5). Chaque POST régénère son bloc à partir de la suite du PRNG du flux. Aucun blob de 20 Mo n'est jamais alloué (limite worker JS `xhr_ul_blob_megabytes=20` dépasserait le plafond 10 Mo en un seul lot — recherche §6).

| Caractéristique | Avant (zéros) | Après (PRNG déterministe) |
|---|---|---|
| Ratio compression | ≈ 0.0001 | ≈ 1.0 |
| Risque de déduplication proxy | élevé | négligeable |
| Reproductibilité en test | n/a | totale (même graine → même octet si on lit le corps côté test) |
| Mémoire | 64 KiB réutilisé | 64 KiB par `writeTo` |
| `Content-Encoding` | absent (zéros bénéficient de la compression) | `identity` explicite (réplique worker JS) |

### 4.2 Comptage réel écrit

```
totalUpload = AtomicLong(0)
pour chaque flux upload f (1 flux séquentiel — voir §4.3) :
    tant que total < maxUploadBytes ET durée restante :
        block = min(1 MiB, maxUploadBytes - total)
        POST empty.php?r=<neuf>, body = RequestBody(contentLength=block) { writeTo(sink) :
            PRNG.remplir buffer 64 KiB
            tant que block restant :
                n = min(64 KiB, restant)
                sink.write(buffer, 0, n)        // retourne seulement si N octets acceptés
                total.addAndGet(n)               // comptage = écritures réussies
                onProgress(total)
                si cancelled || !actif → throw IOException("cancelled")  // abort
            sink.flush()
        }
        si response.code != 200 → ServerUnavailable (413 = serveur mal configuré, doc.md § Web server upload limit)
        si EOF prématuré / écriture incomplète → Incomplete si consécutive, sinon relance
```

- Le compteur n'avance **que sur écriture réussie** (`sink.write` ne retourne que si les `n` octets sont acceptés). Une écriture interrompue à 40 % ne compte que les chunks déjà écrits — le retour actuel `return maxBytes` disparaît.
- « Compte après le 200 » reste la règle d'**acceptation** : un POST non confirmé par le serveur (5xx, timeout, 413) n'est pas compté comme transmis utilement. Si le serveur répond 200 alors que le client a écrit moins que `block` (incohérence), on compte les octets réellement écrits, pas `block`. La distinction est écrite dans le code sous forme de commentaire visible au réviseur.
- Le backstop du repository (déjà spécifié architecture §7) devient effectif sur l'upload : un `Running(uploadBytes=…)` biaisé par un moteur défaillant tue le run en `Incomplete`.

### 4.3 Parallélisme upload : 1 flux séquentiel

- Le worker JS et le CLI utilisent 3 flux upload. L'architecture §6.4 retient **1 flux** pour les liens asymétriques (upload souvent le facteur limitant ; 3 flux saturent le buffer et faussent le comptage avant stabilisation). Compromis métrologique : sur lien symétrique rapide, 1 flux reste plafonné à 10 Mo avant stabilisation de toute façon ; le `≥` honnête l'exprime. **Le 1 flux est retenu**, avec la réserve que T5 de l'architecture le validera contre MockWebServer (un test multi-flux peut le relever si la mesure réelle le justifie — voir arbitrages §8).
- Durées : warm-up upload **2 s** écartés (au lieu des 3 s du worker JS : à 10 Mo, 3 s laissent moins de 7 s utiles — recherche §6), mesure ≤ **10 s**, comptés au plafond. Un `time_auto` / `bonusT` du worker JS est **explicitement écarté** (`time_auto=false`) : raccourcir le test en fonction du débit fabriquerait une corrélation entre débit et durée qui rompt la comparabilité et le testabilité.

### 4.4 Annulation pendant l'écriture

- `writeTo` est bloquant (non-`suspend`). La coopérativité coroutines y est impossible ; l'annulation passe donc par **le même drapeau volatile** que le download (`cancelled`), vérifié **à chaque chunk de 64 KiB** (et pas seulement au début de `writeTo` comme aujourd'hui). Le worker doit aussi honorer `CancellationException` côté moteur : à chaque `await` / retour d'appel réseau, `ensureActive()`.
- `cancel()` ferme chaque appel en vol ; un nouvel appel lancé après `cancel()` lève immédiatement. L'`IOException("cancelled")` de `writeTo` est détectée par `client.isCancelled()` et remappée en `CancellationException`, jamais en `ServerUnavailable` (déjà le mapping `LibreSpeedEngine.run`, à conserver).
- Testabilité : un test S1 envoie un upload lent (`throttleBody` côté MockWebServer) et appelle `cancel()` ; il attend une `CancellationException` et `uploadBytes < maxUploadBytes` — jamais un retour de `maxBytes`.

---

## 5. Latence, jitter, latence sous charge

### 5.1 Latence (déjà livrée — à nommer honnêtement)

- 5 sondes séquentielles `GET empty.php?r=…` sur connexion keep-alive, `Cache-Control: no-cache`, timeouts branchement `SpeedTestPolicy` (connect 8 s / read 10 s / call 5 s par sonde).
- Indicateur retenu : **médiane de 5 sondes** (`SpeedTestCalculator.medianLatencyMillis`, jamais min/max, jamais `0` — `null` si aucune sonde). Divergence assumée avec LibreSpeed, qui prend le **minimum** (recherche §3.2) : la médiane est plus robuste et déjà spécifiée Mission 12 — on ne « corrige » pas vers le min.
- Nom affiché : **« latence HTTP (médiane de 5) »**, jamais « ping ». Ce n'est pas un ping ICMP (doc.md : « This test is NOT an ICMP ping »). Où cela compte : tout écran/aide qui dit « ping » sans qualifier est interdit (§6).

### 5.2 Jitter — définition retenue (implémentable et testable)

**Verdict : le jitter est retenu, car il est fiable et testable sur les mêmes 5 sondes.**

- **Formule** (moyenne des écarts absolus successifs, non pondérée) :
  ```
  J = (1 / (n − 1)) × Σ_{i=1}^{n−1} | t_{i+1} − t_i |
  ```
  avec `t_i` = latence de la sonde `i` (ms), `n` = nombre de sondes réussies.
- **Unités** : millisecondes (`ms`), décimale 1 chiffre (ex. `5.0 ms`). Conservée en `Double` dans le résultat futur (`jitterMillis: Double?`).
- **Nombre de sondes** : **5 sondes → 4 intervalles**. C'est peu (bruit d'une sonde = 25 % de l'estimation), mais c'est le contrat `latencyProbes=5` (`SpeedTestPolicy`, validation `1..9` — passer à 10 exigerait un changement de validation architecture §5.4 + budget 30 s). La définition le rend honnête : « jitter indicatif sur 4 intervalles ». La version pondérée LibreSpeed (`0.7/0.3` en hausse, `0.8/0.2` en baisse, premier jitter jeté, `ping = min`) est **écartée** : elle est spécifique au minimum LibreSpeed, non générique sur la médiane, et non testable avec des littéraux simples.
- **Règles de validité** (fonction pure `SpeedTestCalculator.jitterMillis`) :
  - `n < 2` → `null` (aucun intervalle, jamais `0 ms` — `0` prétendrait une liaison parfaite).
  - Une sonde échouée → les intervalles adjacents sont **supprimés**, pas comblés (on ne relie pas `t_i` à `t_{i+2}`). Si 2 sondes échouent → au plus 3 intervalles sur une série discontinue : on renvoie quand même la moyenne mais l'UI doit la marquer « indicatif ». Règle prudente : si plus de 2 sondes manquent, `jitter = null`.
  - Valeur `0.0` sur de vraies sondes égales est possible et affichable (« variation < 0.05 ms » après arrondi) — mais jamais comme défaut de disponibilité.
- **Exemple travaillé (source indépendante du code, à copier dans le test)** :
  sondes littérales `[20, 22, 25, 30, 40]` →
  écarts `|22−20|=2`, `|25−22|=3`, `|30−25|=5`, `|40−30|=10` →
  `J = (2+3+5+10)/4 = 5.0 ms`. Médiane des mêmes sondes = `25 ms`. Le test attend **littéralement `5.0`**, il ne recalcule pas avec la fonction testée (anti-tautologie `tdd`).
- **Stockage** : `SpeedTestResult` ne porte pas encore de champ jitter. L'implémentation future ajoutera `jitterMillis: Double?` (défaut conservé, codec d'historique migré par valeur absente = `null`). **En attendant, l'UI affiche « jitter indisponible »** — pas `— ms`, pas `0 ms`. Le terme « indisponible » est le même pour les métadonnées non mesurées (§6).
- **Seam de test** : S2 pur JVM (`SpeedTestCalculatorTest`) puis S1 moteur (MockWebServer avec délais imposés par le `Dispatcher` : sondes à 20/22/25/30/40 ms simulés par `setBodyDelay`). Deux tranches verticales (§7).

### 5.3 Latence sous charge — reportée (`indisponible` documenté)

**Verdict : la latence sous charge n'est pas mesurée ; elle est reportée comme indisponible, avec justification.**

- **Justification 1 — aucun protocole ne la définit.** Ni `garbage.php` / `empty.php`, ni le worker JS, ni le CLI Go ne mesurent une latence pendant le transfert (ils séquencent ping, puis DL, puis UL). Toute « latence sous charge » serait une invention WiFi Admin, sans référence.
- **Justification 2 — budget insuffisant.** Mesurer la latence sous charge exigerait des sondes concurrentes pendant DL/UL : trafic supplémentaire, contention avec le compteur (le thread de sondes émettrait pendant la lecture), et sous les plafonds 20/10 Mo la phase de charge est déjà trop courte pour une moyenne pertinente.
- **Justification 3 — non reproductibilité en test.** MockWebServer ne modélise pas le bufferbloat (pas de file d'attente réseau). Un test de latence sous charge serait une tautologie ou un faux signal (il mesurerait l'ordonnancement JVM, pas la file routeur).
- **Règle d'affichage** : l'écran ne montre **aucune valeur** de latence sous charge. Le champ existe dans la doc comme `indisponible` (pas `null` déguisé en `0`). La phrase d'aide : « Latence mesurée sans charge. La latence pendant un gros transfert peut être supérieure (bufferbloat) ; nous ne la mesurons pas avec ce protocole. » Aucun chercheur ne la réclamera tant qu'un protocole de mesure dédié (ex. BBR / RRUL, hors périmètre LibreSpeed) n'est pas spécifié et validé.

---

## 6. Ce qu'on ne doit JAMAIS afficher — métadonnées et précision

### 6.1 Tableau des métadonnées (contrat d'affichage)

| Métadonnée | Valeur retenue | Unité | Règle d'affichage |
|---|---|---|---|
| Débit download | `SpeedTestCalculator.mbps(measuredBytes, fenêtre)` ou plancher | Mbps (décimal, 1 décimale) | `≥ X` si `capped`. Jamais un chiffre seul sans `≥` sur un lien plafonné. |
| Débit upload | idem §4.2 (octets écrits réussis) | Mbps | idem. |
| Latence | médiane de 5 sondes HTTP | ms entier | « latence HTTP (médiane de 5) », jamais « ping ICMP », jamais `0`. |
| Jitter | §5.2 (ou « indisponible » en attendant le champ) | ms, 1 décimale | « jitter indicatif (4 intervalles) », jamais `0` par défaut. |
| Latence sous charge | **indisponible** (reportée §5.3) | — | Phrase d'aide, aucune valeur. |
| Durée du test | durée totale monotone du run (latence + DL + UL) | s, 1 décimale | Affichée avec les octets consommés : « Consommé : 18.4 Mo en 12.3 s ». |
| Serveur | `providerLabel` : hôte seul (`vers speed.example.org`) | texte | Toujours visible avant `Lancer` (consentement). Jamais l'URL brute, jamais l'IP publique, jamais l'ISP. |
| Consommation | `downloadBytes + uploadBytes` réels du run | Mo (Mio ? voir §6.2) | Affichée avant le 1er clic (« jusqu'à 30 Mo/test ») et après (« consommé ce test : X Mo »). |
| Liaison Wi-Fi | `WifiLinkInfo` (téléphone ↔ routeur) | champ séparé | Jamais confondue avec le débit Internet. `null` quand inconnu, jamais `0`. |

### 6.2 Convention d'unités (à figer)

- **Débits** : Mbps **décimaux** (`× 8 / 1e6`), conformément à `SpeedTestCalculator` et au worker JS (`speed * 8 * 1.06 / 1e6` — le `1.06` n'est **pas** appliqué, écart documenté). Ne jamais présenter un Mebibit (`/ 1 048 576`, `useMebibits`) sans le nommer.
- **Consommation** : octets bruts stockés en `Long`, affichés en **Mio** (`/ 1 048 576`, 1 décimale) car les plafonds sont définis en Mio (`20L * 1024 * 1024`). Préciser « Mio » dans l'écran de consommation ; un Mo décimal y serait ambigu.
- **Durées** : millisecondes stockées, secondes affichées.

### 6.3 Liste interdite — jamais afficher

1. **Un débit précis sur un lien plafonné.** Ostensiblement : `≥ 78 Mbps (plafond atteint avant stabilisation : valeur minimale, pas débit maximal)`. Jamais « 78 Mbps » seul, jamais arrondi vers le haut, jamais de graph sans la bande de plancher.
2. **Une équivalence avec WiFiman / FAST.com / Ookla.** Aucune phrase du type « équivalent à WiFiman », « mêmes valeurs que FAST », « comparé à Ookla » sans campagne comparative sur le même chemin, même serveur, facteurs documentés, même jour. Tant que cette campagne n'existe pas, l'écran ne cite que **les liens externes manuels** (navigateur, Mission 12 inchangé).
3. **« Ping » sans qualifier.** Pas de « ping 24 ms » alors qu'on a mesuré un temps de réponse HTTP médian. Toujours « latence HTTP ». Pas de « ping sous charge », pas de « jitter sous charge ».
4. **Un `0` déguisé en mesure.** `latency=0`, `jitter=0`, `Mbps=0.0` ne sont jamais affichés comme résultats. `null` ou « indisponible » quand rien n'a été mesuré ; `0` seulement si des octets/mesures prouvent zéro (ex. `mbps(0, 5000)=0.0` ne survit pas au garde-fou `isTooShortToTrust` avant affichage).
5. **Une IP publique, un ISP, une distance, un score.** `getIP.php` n'est jamais appelé (recherche §7.2) ; `SpeedTestResult` ne stocke jamais d'IP/MAC/SSID/secret. La télémétrie (`results/telemetry.php`) n'est jamais appelée ; le serveur de référence est déployé sans `results/` (voir recherche §5).
6. **Un dépassement de plafond présenté comme « optimisation ».** Un `downloadBytes` > plafond est un bug (backstop), pas une performance.
7. **Une certitude sans incertitude.** Pas de « votre débit est de 78 Mbps » au dixième près sur une fenêtre de 1 s plafonnée. La précision d'affichage suit la qualité de la fenêtre (§3.4) : plus la fenêtre est courte, plus le chiffre est un plancher.

---

## 7. Tests proposés — seams, tranches verticales, anti-tautologie

Règle `tdd` : **aucun test à un seam non confirmé**. Les seams proposés ci-dessous sont ceux déjà confirmés en architecture (S1–S4). Chaque tranche est un cycle rouge → vert complet, un seam à la fois. Aucun test ne contacte Internet : S1 sert `127.0.0.1` via MockWebServer ; S2 est pur JVM ; S3 rejoue un moteur truqué ; S4 est instrumenté loopback uniquement.

### 7.1 Download multi-segments (seam S1 — `LibreSpeedEngine.run`)

| Tranche | Test (rouge) | Attendu littéral indépendant |
|---|---|---|
| **T-DL1 — relance sur segment court** | MockWebServer `garbage.php` sert 1 segment de 2 Mio puis EOF ; plafond 6 Mo. | `downloadBytes == 2 000 000` exactement (pas de réinvention), `capped == false`, `downloadMbps > 0` et `≤ plafond_global`. Le test prouve la boucle de relance, pas la formule. |
| **T-DL2 — plafond exact sous parallélisme** | MockWebServer sert 100 Mo en continu ; plafond DL 2 Mo. | `downloadBytes == 2 000 000` **exactement** (réservation atomique §3.3), `capped == true`. Un `≤ + 192 Ko` serait un échec — le plafond est strict. |
| **T-DL3 — warm-up écarté** | MockWebServer découpé en 2 segments avec un délai (segment 1 = warm-up, segment 2 = mesure), horloge de test avancée. | `downloadMbps` calculé sur la seule fenêtre de mesure — littéral côté test : `measuredBytes` imposés / `fenêtre` imposée = Mbps littéral (ex. `4 000 000 octets / 4 000 ms → 8.0 Mbps`). |
| **T-DL4 — réponses courtes répétées** | MockWebServer renvoie 200 avec corps vide 3 fois de suite. | `SpeedTestFailure.Incomplete` (pas `0.0 Mbps`, pas de boucle jusqu'au timeout). |
| **T-UL1 (miroir upload) — §7.2** | — | — |

### 7.2 Upload incompressible compté (seam S1)

| Tranche | Test (rouge) | Attendu littéral indépendant |
|---|---|---|
| **T-UL1 — corps incompressible** | Le `Dispatcher` MockWebServer lit le corps POST et le compare au corpus attendu généré dans le test par le même PRNG (graine littérale) ; une version zéros est comparée au compresseur. | Chaîne d'octets égale à la graine du test ; ratio de compression du corps réel > 0.95 (littéral), ratio des zéros < 0.01 (témoin). |
| **T-UL2 — compteur réel écrit** | MockWebServer répond 200 puis ferme la connexion au milieu du 2ᵉ POST (ou répond 500). | `uploadBytes == octets réellement écrits avant l'erreur`, jamais `== maxUploadBytes`. Cas nominal : `uploadBytes == maxUploadBytes` et `capped == true`. |
| **T-UL3 — annulation pendant écriture** | Upload lent (`throttleBody`), `cancel()` à mi-course. | `CancellationException`, `uploadBytes < maxUploadBytes`, slot repository libéré (déjà couvert par la tranche T7 architecture). |
| **T-UL4 — 413 serveur mal configuré** | MockWebServer répond 413. | `ServerUnavailable`, aucun octet compté au-delà de l'écrit avant 413. |

### 7.3 Jitter (seam S2 pur puis seam S1)

| Tranche | Test (rouge) | Attendu littéral indépendant |
|---|---|---|
| **T-J1 (S2)** | `SpeedTestCalculator.jitterMillis(listOf(20L, 22L, 25L, 30L, 40L))` | exactement `5.0` (cf. §5.2 — calculé à la main dans le commentaire du test, jamais par la fonction). Cas limites : 1 sonde → `null`, 0 sonde → `null`, intervalles supérieurs à 2 sondes manquantes → `null`. |
| **T-J2 (S1)** | MockWebServer impose des délais 20 / 22 / 25 / 30 / 40 ms par sonde (`setBodyDelay` indexé). | `SpeedTestResult.jitterMillis == 5.0` ± tolérance d'horloge explicite (ex. ± 3 ms, littéral), `latencyMillis == 25` (médiane des mêmes sondes). Cette tranche suppose le champ ajouté au résultat ; tant qu'il est absent, T-J2 est rouge par compilation — c'est voulu (vert après implémentation). |

### 7.4 Non-régression (seams S3–S4, inchangés)

- **Backstop repository (S3)** : un faux moteur qui sur-compte tue le run en `Incomplete` (déjà `SpeedTestRepositoryBackstopTest` — à étendre à l'upload : un moteur qui retourne `uploadBytes=99 Mo` doit être tué).
- **UI (S4)** : `SpeedTestContent` avec état configuré — « vers `<hôte>` », préfixe `≥`, « latence HTTP (médiane de 5) », « jitter indisponible » en attendant T-J2, « latence sous charge indisponible » (aucune valeur), bouton actif, consommation `30 Mo/test` avant clic. Sans endpoint : bouton désactivé (déjà couvert, inchangé).

### 7.5 Critères d'acceptation de la PR (vers `develop`, aucune fusion)

- [ ] `git diff --stat router-core/` vide. Aucun fichier de code touché par cette mission métrologique (le présent document seul).
- [ ] T-DL1→T-J2 verts dans une CI **offline** (aucun octet hors `127.0.0.1`).
- [ ] Plafonds 20/10/30 s tenus même si le serveur ment (`Content-Length` contradictoire) ou envoie plus (T-DL2).
- [ ] `git grep -n "https://" data/speedtest/` hors fixtures de test : vide (le protocole ne code aucun hôte).
- [ ] Aucune phrase de supériorité/équivalence WiFiman/FAST/Ookla dans `ui/speedtest/` ni `docs/` d'écran utilisateur.

---

## 8. Arbitrages et décisions

| Sujet | Décision retenue | Alternatives écartées | Justification métrologique |
|---|---|---|---|
| `ckSize` | **2** | 1 (un seul segment), 4–5 (ex. recherche §7 : 2–5) | 2 Mio = plus petit multiple serveur qui double utilement la taille utile sans consommer le plafond d'un coup. 5 Mio = 25 % du plafond en un segment. |
| Flux DL | **3 parallèles, relance continue** | 1 séquentiel (sous-estime), 4–6 (rafale, plus d'annulations) | 3 est le seuil où le gain marginal s'aplatit en Wi-Fi public ; le worker JS en utilise 6 pour le navigateur, le CLI 3 en natif. Le compteur atomique rend N interchangeable. |
| Flux UL | **1 séquentiel** | 3 (symétrie JS/CLI) | Liens asymétriques : l'upload est le facteur limitant ; 1 flux donne un comptage linéaire et annulable. À réviser si une mesure réelle prouve un plafonnement par flux (T-UL1→T-UL3 le détecteront). |
| Warm-up DL/UL | **2 s écartés, comptés** | 1.5 s JS / 3 s UL JS | 2 s est la médiane des deux défauts officiels, adaptée aux liens mobiles derrière Wi-Fi (slow-start > 1.5 s) et au plafond 10 Mo (3 s UL = fenêtre utile < 7 s). |
| `time_auto` / bonus | **désactivé** | `true` (court-circuit JS) | Corréler la durée au débit fabrique la mesure et tue la répétabilité. |
| Facteur 1.06 | **non appliqué** (formule ×1.0 conservée) | 1.06 JS, 1.0513/1.0369/1514/1460 (doc.md) | Le facteur est « typical IPv4 Internet » (doc.md) ; le chemin réel de l'opérateur (peut-être 4G, IPv6, MTU différent) est inconnu. Documenter l'écart ≈ 6 % vaut mieux qu'une calibration inventée (recherche §6). |
| Jitter | **moyenne des |Δ| sur 5 sondes, 4 intervalles** | jitter pondéré LibreSpeed (0.7/0.3), écart-type, écart interquartile | La moyenne des |Δ| est la plus simple testable sur littéraux, compatible avec la médiane (le worker pondère autour du minimum). L'écart-type serait instable sur 4 points. |
| Latence sous charge | **reportée** | sondes concurrentes pendant DL | Voir §5.3 : aucun protocole ne la définit, budget insuffisant, non testable en MockWebServer. |
| Nombre de sondes | **5 (inchangé)** | 10 (worker JS) | `latencyProbes=5` est le contrat `SpeedTestPolicy` (validation `1..9`) ; 10 exigerait un changement de validation + budget. Jitter sur 5 sondes = 4 intervalles, assumé « indicatif ». |

---

## 9. Risques, limites de validité et validation terrain

| Risque / limite | Conséquence si ignoré | Mitigation dans ce protocole | Validation requise |
|---|---|---|---|
| Lien très rapide (> ~80 Mbps) : plafond atteint pendant le warm-up | Faux débit précis, infériorité vs WiFiman/FAST ressentie | `≥` + plancher + fenêtre d'invalidation (§3.4) ; liens externes en substituts | Mesures terrain sur lien saturé : vérifier que `capped=true` et « ≥ » s'affichent dès le dépassement. |
| Lien très lent (< 2 Mbps) : fenêtre de mesure < 1 s | Mbps non représentatif, bruit timer dominant | `isTooShortToTrust` + règle 3 segments vides (§3.4) | Rejeu MockWebServer avec délais. |
| Serveur autre que PHP (`garbage.dat` statique, Rust/Go) | `ckSize` ignoré, taille fixe — la boucle reste valide mais la granularité change | Le client ne fait **aucune** hypothèse sur la taille d'un segment : il boucle jusqu'au budget quel que soit le corps. Le plancher est par définition indépendant du segment. | Test avec `garbage.dat` de 10 Mo (recherche §5.2). |
| Serveur mal configuré (POST > limite nginx, `client_max_body_size`) | 413 / upload en échec | Mapping `ServerUnavailable` + aide opérateur (doc.md § Web server upload limit, déjà documentée recherche §5). | Test T-UL4 + documentation déploiement (Mission 13 §5.2). |
| Compression intermédiaire (CGNAT, VPN) | Zéros déjà éliminés ; PRNG incompressiblement sûre, `Content-Encoding: identity` + `Accept-Encoding: identity` | T-UL1 + en-têtes | Aucune validation réseau réelle n'est requise ici : la garantie est structurelle. |
| Jitter sur 5 sondes bruité | Sur-interprétation (« ma liaison est instable ») | `null` si < 2 sondes, « indicatif (4 intervalles) », jamais comparé à aucun seuil métier | Aucune — la prudence est l'affichage. |
| Serveur loin (pas de point de présence Ouest-africain) | La mesure reflète le chemin international, pas la dernière mille | « vers `<hôte>` » toujours visible, liaison Wi-Fi affichée séparément | Mesure de latence réelle par région (hors mission). |
| Ancien champ manquant (`jitterMillis`) rompt le codec d'historique | Perte d'historique ou crash de lecture | Ajouter le champ avec défaut `null`, migration par absence (§5.2) | Test du codec avec une entrée pré-champ (à écrire en tranche d'implémentation, pas dans cette mission). |

**Ce que ce protocole ne prétend pas mesurer :** la qualité d'expérience (bufferbloat, per-player, histèresis), la bande passante réelle du chemin au-delà du plafond, le débit maximal supporté par le lien. Ces grandeurs exigent des durées/volumes supérieurs aux plafonds ou un protocole différent (NDT7, M-Lab) — voir architecture §D3/D8. Les relever en silence serait violer le contrat de consommation du prépayé.

---

## 10. Résumé des arbitrages (pour le réviseur)

1. **Download** : 3 flux parallèles, `ckSize=2` (2 Mio/segment), relance continue sur EOF (réponse courte normale), warm-up 2 s écarté du calcul mais compté au plafond, fenêtre de mesure monotone ≥ 1 s, arrêt réservation atomique au plafond 20 Mo ou à 12 s, 3 réponses vides consécutives → `Incomplete`. MockWebServer-compatible : corps finis déterministes, compteurs exacts, assertions littérales.
2. **Upload** : 1 flux séquentiel, corps PRNG déterministe (`Random(seed)`, 64 KiB/chunk, bloc POST 1 Mio à `Content-Length` fixe, `Content-Encoding: identity`), warm-up 2 s, 12 s max, compteur = octets écrits réussis (`sink.write`), annulation par drapeau volatile à chaque chunk, 413/5xx → `ServerUnavailable`, jamais de retour de `maxBytes` supposé.
3. **Jitter** : `J = moyenne(|Δt|)` sur les 5 sondes (4 intervalles), `ms` à 1 décimale, `null` si < 2 sondes, testé littéralement (`[20,22,25,30,40] → 5.0 ms`). Tant que le champ n'est pas implémenté : « jitter indisponible », jamais `0`.
4. **Latence sous charge** : reportée comme **indisponible** — aucun protocole LibreSpeed ne la définit, budget et testabilité insuffisants, aucune valeur synthétique.
5. **Précision** : débits plafonnés toujours `≥` + « valeur minimale », latence toujours « HTTP (médiane de 5) » jamais « ping ICMP », consommation affichée (Mio) et durée, serveur = hôte seul, aucun équivalent WiFiman / FAST.com / Ookla sans campagne comparative, aucune IP/ISP/distribution, aucun télémétrique.

---

*Document de métrologie — Mission 13. Aucun secret, aucune IP publique, aucun SSID/MAC, aucun endpoint public ne figure dans ce fichier. Il ne modifie aucun code : le moteur réel reste désactivé par défaut sans endpoint validé. La création de ce fichier est la seule modification de cette mission.*
