package com.redis.workshop.service;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.redis.workshop.config.RedisStartupHelper;
import jakarta.annotation.PostConstruct;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.geo.*;
import org.springframework.data.redis.connection.RedisGeoCommands;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.data.redis.domain.geo.GeoReference;
import org.springframework.context.annotation.DependsOn;
import org.springframework.stereotype.Service;

import com.redis.workshop.config.RedisSearchHelper;

import java.nio.charset.StandardCharsets;
import java.util.*;

@Service
@DependsOn("startupCleanup")
public class GeoFinderService {

    private static final Logger log = LoggerFactory.getLogger(GeoFinderService.class);

    private final StringRedisTemplate redis;
    private final ObjectMapper objectMapper;
    private final RedisSearchHelper redisSearchHelper;

    @Value("${workshop.startup.load-data:true}")
    private boolean loadData;

    @Value("${workshop.startup.force-reload:false}")
    private boolean forceReload;

    private static final String GEO_KEY = "uc12:geo:atms";
    private static final String META_PREFIX = "uc12:meta:";
    private static final String BRANCH_PREFIX = "uc12:branch:";
    private static final String INDEX_NAME = "idx:uc12:branches";

    public GeoFinderService(StringRedisTemplate redis, ObjectMapper objectMapper,
                             RedisSearchHelper redisSearchHelper) {
        this.redis = redis;
        this.objectMapper = objectMapper;
        this.redisSearchHelper = redisSearchHelper;
    }

    @PostConstruct
    public void init() {
        if (!loadData) return;
        List<Map<String, Object>> branches = getBranchData();
        int expectedCount = branches.size();
        if (forceReload) {
            log.info("UC12: force reload enabled for geo data, rebuilding GEO/JSON structures");
        } else {
            Long geoEntries = redis.opsForZSet().size(GEO_KEY);
            long branchDocs = existingBranchDocCount();
            boolean dataPresent = geoEntries != null && geoEntries >= expectedCount && branchDocs >= expectedCount;
            if (dataPresent && RedisStartupHelper.indexExists(redis, INDEX_NAME)) {
                log.info("UC12: geo key already present ({} entries) and index {} available, skipping reload",
                        geoEntries, INDEX_NAME);
                return;
            }
            if (dataPresent) {
                // JSON docs survive a restart but the index definition may not:
                // recreate the index alone instead of skipping it with the reload.
                log.warn("UC12: geo data present but index {} is missing, recreating index only", INDEX_NAME);
                createIndex();
                return;
            }
        }

        loadBranches(branches);
        createIndex();
    }

    private long existingBranchDocCount() {
        try {
            return Math.max(
                    RedisStartupHelper.indexDocCount(redis, INDEX_NAME),
                    RedisStartupHelper.countKeys(redis, BRANCH_PREFIX + "*")
            );
        } catch (Exception e) {
            return RedisStartupHelper.countKeys(redis, BRANCH_PREFIX + "*");
        }
    }

    @SuppressWarnings("unchecked")
    private void loadBranches(List<Map<String, Object>> branches) {
        for (var branch : branches) {
            double lng = ((Number) branch.get("lng")).doubleValue();
            double lat = ((Number) branch.get("lat")).doubleValue();
            String id = (String) branch.get("id");

            // Approach 1: GEOADD + Hash metadata
            redis.opsForGeo().add(GEO_KEY, new Point(lng, lat), id);
            String metaKey = META_PREFIX + id;
            Map<String, String> meta = new HashMap<>();
            meta.put("name", (String) branch.get("name"));
            meta.put("type", (String) branch.get("type"));
            meta.put("address", (String) branch.get("address"));
            meta.put("hours", (String) branch.get("hours"));
            meta.put("services", String.join(",", (List<String>) branch.get("services")));
            meta.put("lat", String.valueOf(lat));
            meta.put("lng", String.valueOf(lng));
            redis.opsForHash().putAll(metaKey, meta);

            // Approach 2: JSON.SET for RQE
            try {
                String jsonKey = BRANCH_PREFIX + id;
                Map<String, Object> jsonDoc = new LinkedHashMap<>(branch);
                jsonDoc.put("location", lng + "," + lat);
                String json = objectMapper.writeValueAsString(jsonDoc);
                redis.execute(connection -> {
                    connection.execute("JSON.SET",
                            jsonKey.getBytes(StandardCharsets.UTF_8),
                            "$".getBytes(StandardCharsets.UTF_8),
                            json.getBytes(StandardCharsets.UTF_8));
                    return null;
                }, true);
            } catch (JsonProcessingException e) {
                log.error("Failed to serialize branch {}", id, e);
            }
        }
        log.info("Loaded {} ATMs/branches into Redis (Geo + JSON)", branches.size());
    }

    private void createIndex() {
        try {
            redis.execute(connection -> {
                connection.execute("FT.DROPINDEX",
                        INDEX_NAME.getBytes(StandardCharsets.UTF_8));
                return null;
            }, true);
        } catch (Exception ignored) { }

        try {
            redis.execute(connection -> {
                connection.execute("FT.CREATE",
                        INDEX_NAME.getBytes(StandardCharsets.UTF_8),
                        "ON".getBytes(StandardCharsets.UTF_8),
                        "JSON".getBytes(StandardCharsets.UTF_8),
                        "PREFIX".getBytes(StandardCharsets.UTF_8),
                        "1".getBytes(StandardCharsets.UTF_8),
                        BRANCH_PREFIX.getBytes(StandardCharsets.UTF_8),
                        "SCHEMA".getBytes(StandardCharsets.UTF_8),
                        "$.location".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "location".getBytes(StandardCharsets.UTF_8),
                        "GEO".getBytes(StandardCharsets.UTF_8),
                        "$.type".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "type".getBytes(StandardCharsets.UTF_8),
                        "TAG".getBytes(StandardCharsets.UTF_8),
                        "$.services[*]".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "services".getBytes(StandardCharsets.UTF_8),
                        "TAG".getBytes(StandardCharsets.UTF_8),
                        "$.name".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "name".getBytes(StandardCharsets.UTF_8),
                        "TEXT".getBytes(StandardCharsets.UTF_8),
                        "$.hours".getBytes(StandardCharsets.UTF_8),
                        "AS".getBytes(StandardCharsets.UTF_8),
                        "hours".getBytes(StandardCharsets.UTF_8),
                        "TAG".getBytes(StandardCharsets.UTF_8));
                return null;
            }, true);
            log.info("Created index {}", INDEX_NAME);
        } catch (Exception e) {
            log.warn("Index {} may already exist: {}", INDEX_NAME, e.getMessage());
        }
    }


    /** Approach 1: Native GEOSEARCH */
    public Map<String, Object> searchNative(double lng, double lat, double radiusKm) {
        long start = System.nanoTime();

        GeoResults<RedisGeoCommands.GeoLocation<String>> geoResults = redis.opsForGeo()
                .search(GEO_KEY,
                        GeoReference.fromCoordinate(lng, lat),
                        new Distance(radiusKm, Metrics.KILOMETERS),
                        RedisGeoCommands.GeoSearchCommandArgs.newGeoSearchArgs()
                                .includeDistance()
                                .sortAscending()
                                .limit(20));

        List<Map<String, Object>> locations = new ArrayList<>();
        if (geoResults != null) {
            for (var result : geoResults) {
                String id = result.getContent().getName();
                Map<Object, Object> meta = redis.opsForHash().entries(META_PREFIX + id);
                Map<String, Object> loc = new LinkedHashMap<>();
                loc.put("id", id);
                loc.put("distance", Math.round(result.getDistance().getValue() * 1000.0) / 1000.0);
                loc.put("distanceUnit", "km");
                loc.put("name", meta.getOrDefault("name", ""));
                loc.put("type", meta.getOrDefault("type", ""));
                loc.put("address", meta.getOrDefault("address", ""));
                loc.put("hours", meta.getOrDefault("hours", ""));
                loc.put("lat", Double.parseDouble((String) meta.getOrDefault("lat", "0")));
                loc.put("lng", Double.parseDouble((String) meta.getOrDefault("lng", "0")));
                String svc = (String) meta.getOrDefault("services", "");
                loc.put("services", svc.isEmpty() ? List.of() : List.of(svc.split(",")));
                locations.add(loc);
            }
        }

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        String command = "GEOSEARCH " + GEO_KEY + " FROMLONLAT " + lng + " " + lat
                + " BYRADIUS " + radiusKm + " km ASC COUNT 20";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", locations);
        response.put("count", locations.size());
        response.put("latencyMs", latencyMs);
        response.put("approach", "Native Geospatial");
        response.put("command", command);
        return response;
    }

    /** Approach 2: JSON + Redis Query Engine */
    @SuppressWarnings("unchecked")
    public Map<String, Object> searchRQE(double lng, double lat, double radiusKm,
                                          String type, String service) {
        long start = System.nanoTime();

        StringBuilder query = new StringBuilder();
        query.append("@location:[").append(lng).append(" ").append(lat)
                .append(" ").append(radiusKm).append(" km]");
        if (type != null && !type.isEmpty() && !"all".equalsIgnoreCase(type)) {
            query.append(" @type:{").append(type).append("}");
        }
        if (service != null && !service.isEmpty() && !"all".equalsIgnoreCase(service)) {
            query.append(" @services:{").append(service).append("}");
        }

        String ftQuery = query.toString();

        List<Object> rawResults = executeFtSearch(INDEX_NAME, ftQuery, "LIMIT", "0", "20");

        List<Map<String, Object>> results = parseRqeResults(rawResults);

        // Calculate distances (RQE geo filter doesn't return distance)
        for (var r : results) {
            double rLat = ((Number) r.getOrDefault("lat", 0.0)).doubleValue();
            double rLng = ((Number) r.getOrDefault("lng", 0.0)).doubleValue();
            r.put("distance", Math.round(haversine(lat, lng, rLat, rLng) * 1000.0) / 1000.0);
            r.put("distanceUnit", "km");
        }
        results.sort(Comparator.comparingDouble(a -> ((Number) a.get("distance")).doubleValue()));

        long latencyMs = (System.nanoTime() - start) / 1_000_000;
        String command = "FT.SEARCH " + INDEX_NAME + " \"" + ftQuery + "\"";

        Map<String, Object> response = new LinkedHashMap<>();
        response.put("results", results);
        response.put("count", results.size());
        response.put("latencyMs", latencyMs);
        response.put("approach", "JSON + Query Engine");
        response.put("command", command);
        return response;
    }

    /** List all branches/ATMs */
    public List<Map<String, Object>> listAll() {
        List<Map<String, Object>> all = new ArrayList<>();
        for (var branch : getBranchData()) {
            Map<String, Object> item = new LinkedHashMap<>();
            item.put("id", branch.get("id"));
            item.put("name", branch.get("name"));
            item.put("type", branch.get("type"));
            item.put("address", branch.get("address"));
            item.put("lat", branch.get("lat"));
            item.put("lng", branch.get("lng"));
            item.put("services", branch.get("services"));
            item.put("hours", branch.get("hours"));
            all.add(item);
        }
        return all;
    }

    // --- Helpers ---

    private List<Object> executeFtSearch(String indexName, String query, String... extraArgs) {
        return redisSearchHelper.ftSearchRaw(indexName, query, extraArgs);
    }

    @SuppressWarnings("unchecked")
    private List<Map<String, Object>> parseRqeResults(Object rawResults) {
        List<Map<String, Object>> results = new ArrayList<>();
        if (rawResults == null) return results;

        List<Object> list;
        if (rawResults instanceof List<?> l) {
            list = (List<Object>) l;
        } else {
            return results;
        }
        if (list.size() < 2) return results;

        for (int i = 1; i < list.size(); i += 2) {
            if (i + 1 >= list.size()) break;
            String docKey = RedisSearchHelper.toStr(list.get(i));
            Object fieldsObj = list.get(i + 1);
            if (!(fieldsObj instanceof List<?> fields)) continue;

            Map<String, Object> doc = new LinkedHashMap<>();
            String id = docKey.startsWith(BRANCH_PREFIX)
                    ? docKey.substring(BRANCH_PREFIX.length()) : docKey;
            doc.put("id", id);

            for (int j = 0; j + 1 < fields.size(); j += 2) {
                String fn = RedisSearchHelper.toStr(fields.get(j));
                String fv = RedisSearchHelper.toStr(fields.get(j + 1));
                // JSON fields come as $. path with JSON arrays
                if ("$".equals(fn)) {
                    try {
                        Map<String, Object> json = objectMapper.readValue(fv, LinkedHashMap.class);
                        doc.put("name", json.getOrDefault("name", ""));
                        doc.put("type", json.getOrDefault("type", ""));
                        doc.put("address", json.getOrDefault("address", ""));
                        doc.put("hours", json.getOrDefault("hours", ""));
                        doc.put("lat", ((Number) json.getOrDefault("lat", 0.0)).doubleValue());
                        doc.put("lng", ((Number) json.getOrDefault("lng", 0.0)).doubleValue());
                        doc.put("services", json.getOrDefault("services", List.of()));
                    } catch (Exception e) {
                        log.warn("Failed to parse JSON for {}", docKey, e);
                    }
                }
            }
            results.add(doc);
        }
        return results;
    }



    /** Haversine formula — distance in km */
    private double haversine(double lat1, double lon1, double lat2, double lon2) {
        double R = 6371.0;
        double dLat = Math.toRadians(lat2 - lat1);
        double dLon = Math.toRadians(lon2 - lon1);
        double a = Math.sin(dLat / 2) * Math.sin(dLat / 2)
                + Math.cos(Math.toRadians(lat1)) * Math.cos(Math.toRadians(lat2))
                * Math.sin(dLon / 2) * Math.sin(dLon / 2);
        return R * 2 * Math.atan2(Math.sqrt(a), Math.sqrt(1 - a));
    }

    private static Map<String, Object> branch(String id, String name, String type, String address,
                                              double lat, double lng, String hours, String... services) {
        return Map.of(
                "id", id,
                "name", name,
                "type", type,
                "address", address,
                "lat", lat,
                "lng", lng,
                "services", List.of(services),
                "hours", hours
        );
    }

    /** Static branch data — 30 ATMs and branches across Madrid, Barcelona, Valencia and Lisbon. */
    private static List<Map<String, Object>> getBranchData() {
        return List.of(
            branch("atm-001", "RedisBank Sol Hub ATM", "atm", "Plaza Demo Sol 1, Madrid", 40.4168, -3.7038, "24h", "withdrawal", "deposit", "balance"),
            branch("atm-002", "RedisBank Gran Vía Express ATM", "atm", "Avenida Escaparate 32, Madrid", 40.4200, -3.7056, "24h", "withdrawal", "balance"),
            branch("branch-001", "RedisBank Cibeles Advisory Branch", "branch", "Plaza Foro 4, Madrid", 40.4194, -3.6931, "9:00-14:00", "withdrawal", "deposit", "advisor", "mortgage", "business"),
            branch("atm-003", "RedisBank Atocha Transit ATM", "atm", "Paseo Andén 7, Madrid", 40.4065, -3.6895, "24h", "withdrawal", "deposit"),
            branch("branch-002", "RedisBank Castellana Wealth Desk", "branch", "Paseo Norte 79, Madrid", 40.4372, -3.6920, "8:30-14:30", "withdrawal", "deposit", "advisor", "insurance", "private_banking"),
            branch("atm-004", "RedisBank Retiro Park ATM", "atm", "Bulevar Jardín 12, Madrid", 40.4225, -3.6832, "24h", "withdrawal"),
            branch("branch-003", "RedisBank Chamberí Family Branch", "branch", "Calle Mercado 45, Madrid", 40.4340, -3.7015, "9:00-14:00", "withdrawal", "deposit", "advisor"),
            branch("atm-005", "RedisBank Malasaña Smart ATM", "atm", "Calle Creativa 78, Madrid", 40.4266, -3.7032, "24h", "withdrawal", "deposit", "balance"),
            branch("atm-006", "RedisBank Lavapiés Corner ATM", "atm", "Plaza Atelier 3, Madrid", 40.4089, -3.7006, "24h", "withdrawal"),
            branch("branch-004", "RedisBank Salamanca Private Lounge", "branch", "Calle Boutique 25, Madrid", 40.4270, -3.6860, "9:00-14:30", "withdrawal", "deposit", "advisor", "private_banking"),
            branch("atm-007", "RedisBank Moncloa Campus ATM", "atm", "Avenida Universidad 40, Madrid", 40.4310, -3.7180, "24h", "withdrawal", "deposit"),
            branch("branch-005", "RedisBank Tetuán Business Point", "branch", "Calle Mercado Norte 120, Madrid", 40.4505, -3.7040, "8:30-14:00", "withdrawal", "deposit", "advisor", "business"),
            branch("atm-008", "RedisBank Bernabéu Matchday ATM", "atm", "Paseo Estadio 104, Madrid", 40.4530, -3.6883, "24h", "withdrawal", "deposit", "balance"),
            branch("atm-009", "RedisBank Callao Cinema ATM", "atm", "Plaza Pantalla 2, Madrid", 40.4198, -3.7065, "24h", "withdrawal", "deposit"),
            branch("branch-006", "RedisBank Argüelles Mortgage Studio", "branch", "Calle Residencial 16, Madrid", 40.4295, -3.7120, "9:00-14:00", "withdrawal", "deposit", "advisor", "mortgage"),
            branch("atm-010", "RedisBank Tribunal Night ATM", "atm", "Calle Aurora 25, Madrid", 40.4243, -3.6988, "24h", "withdrawal", "balance"),
            branch("atm-011", "RedisBank Ópera Heritage ATM", "atm", "Plaza Escena 6, Madrid", 40.4180, -3.7098, "24h", "withdrawal", "deposit"),
            branch("branch-007", "RedisBank Centro Flagship Branch", "branch", "Calle Portal 18, Madrid", 40.4155, -3.7078, "9:00-15:00", "withdrawal", "deposit", "advisor", "mortgage", "insurance"),
            branch("atm-012", "RedisBank Sants Smart ATM", "atm", "Avinguda Connexió 11, Barcelona", 41.3791, 2.1401, "24h", "withdrawal", "deposit", "balance"),
            branch("branch-008", "RedisBank Eixample Advisory Branch", "branch", "Carrer Demo Central 58, Barcelona", 41.3917, 2.1649, "8:30-14:30", "withdrawal", "deposit", "advisor", "insurance"),
            branch("atm-013", "RedisBank Gothic Quarter ATM", "atm", "Plaça Codi 9, Barcelona", 41.3839, 2.1763, "24h", "withdrawal", "balance"),
            branch("branch-009", "RedisBank Diagonal Wealth Desk", "branch", "Avinguda Futura 220, Barcelona", 41.3953, 2.1619, "9:00-15:00", "withdrawal", "deposit", "advisor", "private_banking", "fx"),
            branch("atm-015", "RedisBank Colón Plaza ATM", "atm", "Plaça Llum 2, Valencia", 39.4699, -0.3763, "24h", "withdrawal", "deposit", "balance"),
            branch("branch-011", "RedisBank Ruzafa Lifestyle Branch", "branch", "Carrer Mercat Nou 44, Valencia", 39.4627, -0.3707, "8:30-14:30", "withdrawal", "deposit", "advisor", "insurance"),
            branch("atm-016", "RedisBank Turia Garden ATM", "atm", "Passeig Verd 6, Valencia", 39.4763, -0.3850, "24h", "withdrawal", "balance"),
            branch("branch-012", "RedisBank City of Arts Branch", "branch", "Avinguda Innovació 19, Valencia", 39.4549, -0.3507, "9:00-15:00", "withdrawal", "deposit", "advisor", "private_banking", "investment"),
            branch("atm-018", "RedisBank Baixa Express ATM", "atm", "Rua Demo 5, Lisbon", 38.7107, -9.1395, "24h", "withdrawal", "deposit", "balance"),
            branch("branch-014", "RedisBank Avenida Advisory Branch", "branch", "Avenida Futuro 88, Lisbon", 38.7223, -9.1393, "8:30-14:30", "withdrawal", "deposit", "advisor", "insurance"),
            branch("atm-019", "RedisBank Parque Nations ATM", "atm", "Passeio Horizonte 14, Lisbon", 38.7686, -9.0959, "24h", "withdrawal", "balance"),
            branch("branch-015", "RedisBank Chiado Wealth Studio", "branch", "Rua Mosaic 27, Lisbon", 38.7102, -9.1427, "9:00-15:00", "withdrawal", "deposit", "advisor", "private_banking", "fx")
        );
    }
}