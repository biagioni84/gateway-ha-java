package uy.plomo.gateway.util;

import com.fasterxml.jackson.core.type.TypeReference;
import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.persistence.AttributeConverter;
import jakarta.persistence.Converter;

import java.util.Collections;
import java.util.List;
import java.util.Map;

/**
 * JPA converters for JSON-serialized columns in SQLite.
 * Inner classes are registered as @Converter(autoApply = false) so they must
 * be applied explicitly via @Convert on each field.
 */
public class JsonConverter {

    private static final ObjectMapper MAPPER = new ObjectMapper();

    // ── Map<String, Object> ───────────────────────────────────────────────────

    @Converter
    public static class StringObjectMap
            implements AttributeConverter<Map<String, Object>, String> {

        private static final TypeReference<Map<String, Object>> TYPE =
                new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(Map<String, Object> map) {
            if (map == null || map.isEmpty()) return "{}";
            try { return MAPPER.writeValueAsString(map); }
            catch (Exception e) { return "{}"; }
        }

        @Override
        public Map<String, Object> convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return Collections.emptyMap();
            try { return MAPPER.readValue(json, TYPE); }
            catch (Exception e) { return Collections.emptyMap(); }
        }
    }

    // ── Map<String, Map<String, Object>> (attributes: cluster → attr → value) ─

    @Converter
    public static class NestedStringObjectMap
            implements AttributeConverter<Map<String, Map<String, Object>>, String> {

        private static final TypeReference<Map<String, Map<String, Object>>> TYPE =
                new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(Map<String, Map<String, Object>> map) {
            if (map == null || map.isEmpty()) return "{}";
            try { return MAPPER.writeValueAsString(map); }
            catch (Exception e) { return "{}"; }
        }

        @Override
        public Map<String, Map<String, Object>> convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return Collections.emptyMap();
            try { return MAPPER.readValue(json, TYPE); }
            catch (Exception e) { return Collections.emptyMap(); }
        }
    }

    // ── Map<String, String> (pincodes: userId → code) ─────────────────────────

    @Converter
    public static class StringStringMap
            implements AttributeConverter<Map<String, String>, String> {

        private static final TypeReference<Map<String, String>> TYPE =
                new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(Map<String, String> map) {
            if (map == null || map.isEmpty()) return "{}";
            try { return MAPPER.writeValueAsString(map); }
            catch (Exception e) { return "{}"; }
        }

        @Override
        public Map<String, String> convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return Collections.emptyMap();
            try { return MAPPER.readValue(json, TYPE); }
            catch (Exception e) { return Collections.emptyMap(); }
        }
    }

    // ── List<String> ──────────────────────────────────────────────────────────

    @Converter
    public static class StringList
            implements AttributeConverter<List<String>, String> {

        private static final TypeReference<List<String>> TYPE =
                new TypeReference<>() {};

        @Override
        public String convertToDatabaseColumn(List<String> list) {
            if (list == null || list.isEmpty()) return "[]";
            try { return MAPPER.writeValueAsString(list); }
            catch (Exception e) { return "[]"; }
        }

        @Override
        public List<String> convertToEntityAttribute(String json) {
            if (json == null || json.isBlank()) return Collections.emptyList();
            try { return MAPPER.readValue(json, TYPE); }
            catch (Exception e) { return Collections.emptyList(); }
        }
    }

    // ── Utility: JSON string → Map ─────────────────────────────────────────────

    public static Map<String, Object> toMap(String json) {
        if (json == null || json.isBlank()) return Collections.emptyMap();
        try {
            return MAPPER.readValue(json, new TypeReference<>() {});
        } catch (Exception e) {
            return Collections.emptyMap();
        }
    }
}
