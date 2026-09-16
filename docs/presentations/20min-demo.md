# Démo Redis en 20 minutes — public développeurs

- **Audience** : développeurs chez un opérateur de réseau électrique (TSO)
- **Durée** : 20 min, démo live sur `redis_banking_demos` (http://localhost:8080)
- **Périmètre** : cas d'usage IA **exclus** (UC9, UC14, UC15, UC16, UC17)
- **Message unique** : *un seul moteur, un seul budget de latence, pour le cache, la protection, la coordination, l'ingestion, la requête et la géo.*

Routes vérifiées dans `UseCaseController` : `GET /usecase/{id}` (id = 1..17).
Chaque page a un bouton **⛶ Fullscreen** (mode présentateur).

---

## 1. Sélection des cas d'usage

6 démos qui ne se recouvrent pas, ordonnées pour raconter **une seule** histoire :
« vous connaissez Redis comme cache — voici pourquoi il est devenu votre couche temps réel ».

| Ordre | UC | URL | Durée | Primitive montrée | Pourquoi elle (contexte réseau électrique) |
|---|---|---|---|---|---|
| 1 | **UC10 Cache-Aside** | `/usecase/10` | 2 min | `GET` / `SET EX`, hit ratio | Le socle connu : 200 ms → <1 ms, TTL = fraîcheur gratuite |
| 2 | **UC4 Rate Limiting** | `/usecase/4` | 2 min | `INCR` + `EXPIRE`, sliding window | APIs exposées aux BRP / agrégateurs / partenaires |
| 3 | **UC13 Distributed Locking** | `/usecase/13` | 2.5 min | `SET NX EX` + Lua, contention 3 clients | Coordination entre instances : un seul job/ordre exécuté |
| 4 | **UC11 Real-time Monitoring** | `/usecase/11` | 3.5 min | Streams `XADD` / `XRANGE` / `XTRIM` | Télémétrie & mesures : ingestion + dashboard live + anomalie |
| 5 | **UC8 Document DB + RQE** | `/usecase/8` | 3.5 min | `JSON.SET`, `FT.CREATE` / `FT.SEARCH` | Référentiel/doc interrogeable sans Elasticsearch à côté |
| 6 | **UC12 Geospatial** | `/usecase/12` | 2.5 min | `GEOADD` / `GEOSEARCH`, `FT.SEARCH` géo + tag | Postes & actifs sur carte, dispatch équipes terrain, geofencing chantiers |

Intro 1.5 min + conclusion 1.5 min = **19 min**. Zéro marge : chronomètre visible.

> Budgets resserrés par rapport à une version 5 démos : UC10 et UC4 sont volontairement expédiés (terrain connu), le temps gagné finance UC12.

### Écartés volontairement

| UC | Raison |
|---|---|
| UC1 / UC2 / UC3 (token, session, profil) | Même primitive que UC10 → Hash + TTL. Une phrase suffit. |
| UC5 (dedup) | Même atomicité que UC13 (`SET NX`) → citée à l'oral pendant UC13. |
| UC6 / UC7 | Tous deux « feature store temps réel » ; UC6 recoupe en plus les Streams d'UC11. |
| UC9 / UC14 / UC15 / UC16 / UC17 | IA, hors périmètre (UC17 = consumer groups, cité à l'oral seulement). |

### Backup (si 3 min de rab)

**UC7 Feature Store** (`/usecase/7`) — features servies en <1 ms pour les modèles de prévision (charge, vent) : `HSET` / `HINCRBY` / `FT.SEARCH`.

---

## 2. Fil conducteur

### Intro — 1.5 min

> « L'appli devant vous est une démo bancaire, mais oubliez les virements : lisez « mesures », « ordres de marché », « partenaires ». Les primitives sont les mêmes. 17 cas d'usage, **une seule instance Redis**. On en ouvre 6. »

Montrer la landing page + **RedisInsight dans un second onglet (port 8001)** :
« tout ce que je fais, vous le voyez dans les clés. »

### 1 — UC10 · Le terrain connu (2 min)

**Gestes** : sélectionner un produit → **Fetch Product** = CACHE MISS (~200 ms) → **Fetch Product** à nouveau = CACHE HIT (<1 ms). Montrer le hit ratio, puis **Clear Cache (this product)**.

**À dire** : « le pattern n'est pas `GET`/`SET`, c'est le **TTL** : la fraîcheur devient une propriété de la donnée, pas un cron. »

**Clés** : `uc10:product:*` (le « RDBMS » simulé est sous `uc10:mockdb:*`).

**Liaison** : « même Hash + TTL derrière les tokens, sessions et profils (UC1-3) — je ne les ouvre pas, c'est le même geste. »

### 2 — UC4 · Le même compteur, mais comme garde-fou (2 min)

**Gestes** : **Call API** deux ou trois fois → compteur qui monte ; **Burst (5 calls)** pour taper la limite d'un coup → refus en rouge ; **↺ Reset**. Onglets code : *Fixed Window (demo)* vs *Sliding Window (alternative)*.

**À dire** : « `INCR` est **atomique** : sous 10 000 req/s réparties sur N instances, pas de race condition, pas de verrou applicatif. Clé = par partenaire, par API-key, par IP — vous choisissez la granularité. »

**Clés** : `uc4:*`.

**Liaison** : « l'atomicité protège une ressource. Elle peut aussi en garantir la propriété. »

### 3 — UC13 · Coordination (2.5 min)

**Gestes** : **Acquire Lock** → TTL qui décompte → **Acquire Lock** avec un autre client ID = refus (garantie `NX`) → **Release Lock** → **Simulate 3 Concurrent Clients** (3 clients courent, 1 seul gagne). Montrer le script Lua dans le panneau code ; onglet *WATCH/MULTI/EXEC (alternative)* seulement si on vous le demande.

**À dire** : « `NX` = exclusion mutuelle, `EX` = pas de deadlock si le porteur crashe, Lua = seul le porteur libère. C'est ce qui empêche deux instances de rejouer le même traitement. »

**Clés** : `uc13:lock:*`.

**Liaison** : « même primitive pour l'idempotence d'ingestion (UC5 : un message rejoué n'est traité qu'une fois). Et maintenant, l'ingestion elle-même. »

### 4 — UC11 · Redis devient le flux (3.5 min) — **pic de la présentation**

**Gestes** : **Start Simulation**, laisser le graphe se construire (~2 TPS), commenter TPS / montant moyen, puis **Inject Anomaly** → spike + % haut risque. **Stop**, puis **Reset**.

**À dire** : « Streams = log append-only, ID horodaté à la ms → les requêtes par fenêtre temporelle sont natives. `XTRIM MAXLEN` borne la mémoire : la rétention est un paramètre, pas un projet. Ici l'agrégation est en Java, mais les **consumer groups** permettent à N workers de se partager le flux avec accusé de réception (`XACK`) — c'est ce qui tourne dans UC17. »

**Clé** : `uc11:stream:transactions` (à montrer dans RedisInsight pendant que ça tourne).

**Liaison** : « on a de la donnée qui bouge. Il reste la donnée qu'on interroge. »

### 5 — UC8 · Un seul datastore (3.5 min)

**Gestes** : onglet **CRUD Operations** → *Create Document* (`JSON.SET`), *Read* (`JSON.GET`), *Read Field* (`JSON.GET $.title`) → onglet **Full-Text** → requête libre avec scores de pertinence. Si le temps manque, sauter l'onglet *Query* (`@category:{PSD2}`).
**Ne pas ouvrir les onglets *Vector* et *Hybrid*** (hors périmètre IA).

**À dire** : « JSON natif + index secondaires : tag, numérique, plein texte, géo. Les catégories ici sont des régulations financières ; chez vous ce sont des codes de réseau, des procédures, des fiches d'actifs. La question n'est pas « Redis ou Elasticsearch » mais « combien de systèmes je peux retirer de mon architecture ». <10 ms. »

**Clés** : `uc8:doc:*`.

**Liaison** : « j'ai dit « et géo » dans la liste des types d'index. Ce n'était pas du remplissage. »

### 6 — UC12 · La géo dans le même moteur (2.5 min) — **clôture visuelle**

**Gestes** :
1. Onglet **Native Geospatial** → preset **Puerta del Sol** → rayon 2 km → **Search** : les points s'affichent sur la carte avec leur distance.
2. Onglet **JSON + Query Engine** → même position → filtre type **ATM** → **Search** : la requête devient `FT.SEARCH` avec `@location:[lng lat 2 km] @type:{atm}`.
3. Ajouter un filtre de service (ex. **Advisor**) → une seule requête, zéro filtrage applicatif.

**À dire** : « Deux chemins. Le natif — `GEOADD` / `GEOSEARCH` — c'est un Sorted Set, O(N+log(M)), imbattable pour de la pure proximité. Le Query Engine, c'est la géo **combinée** à n'importe quel autre critère dans **une seule requête** : périmètre + type d'actif + service disponible + fenêtre horaire. Chez vous : quel poste dans ce rayon, avec quel équipement, quelle équipe dispo. Et c'est la **même instance** que les 5 démos précédentes — pas de base géo à côté. »

**Clés** : `uc12:geo:atms` (le Sorted Set géo), `uc12:branch:*`, `uc12:meta:*`.

### Conclusion — 1.5 min

1. Cache, rate limiting, locks, streaming, requête, géo : **une instance, six rôles** — et vous en avez vu 6 sur 17.
2. La vraie économie n'est pas la latence, c'est le **nombre de composants** à opérer, monitorer, patcher.
3. Ce qu'on n'a pas ouvert : la partie IA (mémoire d'agent, RAG, cache sémantique, guardrails) — même instance, même index vectoriel. Sujet d'une prochaine session.
4. Multi-région **Active-Active** pour les contraintes de disponibilité / géo.
5. Repo + guide présentateur dispo : tout se relance avec `make demo`.

---

## 3. Discipline de scène

- Si une démo dérape de **plus de 30 s** au-delà du budget : la couper, passer à la suivante, aucun retour en arrière.
- Le sacrifice prévu si vous êtes en retard : l'onglet *Query* d'UC8, puis l'étape 3 d'UC12 (filtre de service). **Ne pas sacrifier UC12 en entier** — c'est la clôture visuelle.
- Garder RedisInsight sur `uc4:*` / `uc11:stream:transactions` : c'est ce qui rend crédible « c'est vraiment Redis ».
- **Ne jamais ouvrir un UC IA « juste pour montrer »** : c'est le seul risque de perdre 5 min.
- Utiliser **⛶ Fullscreen** sur chaque page de démo (mode présentateur).

## 4. Checklist pré-démo

- [ ] `docker compose ps` — `redis`, `app`, `redis-insight` up
- [ ] http://localhost:8080 répond, landing page OK
- [ ] RedisInsight http://localhost:8001 connecté à l'instance workshop
- [ ] UC10 : **Clear All Cache** fait (pour garantir un MISS en ouverture)
- [ ] UC11 : simulation arrêtée et **Reset** fait
- [ ] UC13 : aucun lock résiduel (`uc13:lock:*` vide)
- [ ] Index RQE présents : `redis-cli FT._LIST | grep -E 'uc8|uc12'` → `idx:uc8:documents` + `idx:uc12:branches`
- [ ] UC12 : carte qui charge (tuiles accessibles depuis la salle — **tester le réseau du lieu**, prévoir une capture d'écran de secours)
- [ ] Onglets ouverts dans l'ordre : `/usecase/10` → `/4` → `/13` → `/11` → `/8` → `/12`
- [ ] Chronomètre visible, notifications OS coupées
