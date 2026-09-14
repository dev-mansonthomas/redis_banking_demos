package com.redis.workshop.config;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import org.springframework.data.redis.core.RedisCallback;
import org.springframework.data.redis.core.StringRedisTemplate;

import java.nio.charset.StandardCharsets;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

public final class RedisStartupHelper {

    private static final ObjectMapper OBJECT_MAPPER = new ObjectMapper();

    private RedisStartupHelper() {}

    public static long countKeys(StringRedisTemplate redis, String pattern) {
        var keys = RedisScanHelper.scanKeys(redis, pattern);
        return keys == null ? 0L : keys.size();
    }

    public static boolean keyExists(StringRedisTemplate redis, String key) {
        return Boolean.TRUE.equals(redis.hasKey(key));
    }

    public static long indexDocCount(StringRedisTemplate redis, String indexName) {
        Object raw = redis.execute((RedisCallback<Object>) connection ->
                connection.execute("FT.INFO", bytes(indexName)));
        return longValue(asMap(raw).get("num_docs"));
    }

    /** True when FT.INFO succeeds, i.e. the index definition exists (regardless of doc count). */
    public static boolean indexExists(StringRedisTemplate redis, String indexName) {
        try {
            return redis.execute((RedisCallback<Object>) connection ->
                    connection.execute("FT.INFO", bytes(indexName))) != null;
        } catch (Exception e) {
            return false;
        }
    }

    public static boolean indexExistsWithMinDocs(StringRedisTemplate redis, String indexName, long minDocs) {
        return indexDocCount(redis, indexName) >= minDocs;
    }

    public static int hashVectorDimension(StringRedisTemplate redis, String key) {
        Object raw = redis.execute((RedisCallback<Object>) connection ->
                connection.hashCommands().hGet(bytes(key), bytes("vector")));
        if (raw instanceof byte[] vectorBytes && vectorBytes.length > 0) {
            return vectorBytes.length / Float.BYTES;
        }
        return -1;
    }

    public static int jsonVectorDimension(StringRedisTemplate redis, String key) {
        try {
            Object raw = redis.execute((RedisCallback<Object>) connection ->
                    connection.execute("JSON.GET", bytes(key), bytes("$.vector")));
            if (raw == null) {
                return -1;
            }
            JsonNode root = OBJECT_MAPPER.readTree(stringValue(raw));
            JsonNode vectorNode = root.path("$.vector");
            if (vectorNode.isArray() && !vectorNode.isEmpty()) {
                JsonNode first = vectorNode.get(0);
                if (first.isArray()) {
                    return first.size();
                }
            }
        } catch (Exception ignored) {
        }
        return -1;
    }

    public static boolean streamGroupExists(StringRedisTemplate redis, String streamKey, String groupName) {
        Object raw = redis.execute((RedisCallback<Object>) connection ->
                connection.execute("XINFO", bytes("GROUPS"), bytes(streamKey)));
        if (!(raw instanceof List<?> groups)) {
            return false;
        }

        for (Object group : groups) {
            Map<String, Object> info = asMap(group);
            if (groupName.equals(stringValue(info.get("name")))) {
                return true;
            }
        }
        return false;
    }

    private static Map<String, Object> asMap(Object raw) {
        if (raw instanceof Map<?, ?> map) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (Map.Entry<?, ?> entry : map.entrySet()) {
                normalized.put(stringValue(entry.getKey()), entry.getValue());
            }
            return normalized;
        }
        if (raw instanceof List<?> list) {
            Map<String, Object> normalized = new LinkedHashMap<>();
            for (int i = 0; i + 1 < list.size(); i += 2) {
                normalized.put(stringValue(list.get(i)), list.get(i + 1));
            }
            return normalized;
        }
        return Collections.emptyMap();
    }

    private static long longValue(Object raw) {
        if (raw instanceof Number number) {
            return number.longValue();
        }
        try {
            return Long.parseLong(stringValue(raw));
        } catch (Exception e) {
            return 0L;
        }
    }

    private static String stringValue(Object raw) {
        if (raw == null) {
            return "";
        }
        if (raw instanceof byte[] bytes) {
            return new String(bytes, StandardCharsets.UTF_8);
        }
        return raw.toString();
    }

    private static byte[] bytes(String value) {
        return value.getBytes(StandardCharsets.UTF_8);
    }
}