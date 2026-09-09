package com.example.decision;

import com.fasterxml.jackson.core.JsonProcessingException;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.SerializationFeature;
import com.fasterxml.jackson.datatype.jsr310.JavaTimeModule;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 统一 Tool 返回格式：业务数据 + dataAsOf + metricId
 */
public final class ToolResultFormatter {

    private static final ObjectMapper MAPPER = new ObjectMapper()
            .registerModule(new JavaTimeModule())
            .disable(SerializationFeature.WRITE_DATES_AS_TIMESTAMPS);

    private ToolResultFormatter() {
    }

    public static String success(String metricId, String metricName, OffsetDateTime dataAsOf,
                                 List<Map<String, Object>> rows) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metricId", metricId);
        payload.put("metricName", metricName);
        payload.put("dataAsOf", formatDataAsOf(dataAsOf));
        payload.put("rowCount", rows.size());
        payload.put("rows", rows);
        return toJson(payload);
    }

    public static String error(String metricId, String message) {
        Map<String, Object> payload = new LinkedHashMap<>();
        payload.put("metricId", metricId);
        payload.put("error", message);
        payload.put("dataAsOf", formatDataAsOf(OffsetDateTime.now(ZoneOffset.ofHours(8))));
        return toJson(payload);
    }

    public static String formatDataAsOf(OffsetDateTime time) {
        if (time == null) {
            return "未知";
        }
        return time.format(DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss XXX"));
    }

    private static String toJson(Map<String, Object> map) {
        try {
            return MAPPER.writerWithDefaultPrettyPrinter().writeValueAsString(map);
        } catch (JsonProcessingException e) {
            return "{\"error\":\"结果序列化失败\"}";
        }
    }
}
