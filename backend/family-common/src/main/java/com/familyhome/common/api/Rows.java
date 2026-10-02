package com.familyhome.common.api;

import java.util.*;

public final class Rows {
    private Rows() {}

    public static Map<String, Object> camel(Map<String, Object> row) {
        Map<String, Object> result = new LinkedHashMap<>();
        row.forEach(
                (key, value) -> {
                    String[] parts = key.split("_");
                    StringBuilder name = new StringBuilder(parts[0]);
                    for (int i = 1; i < parts.length; i++)
                        name.append(Character.toUpperCase(parts[i].charAt(0))).append(parts[i].substring(1));
                    result.put(name.toString(), value);
                });
        return result;
    }

    public static List<Map<String, Object>> camel(List<Map<String, Object>> rows) {
        return rows.stream().map(Rows::camel).toList();
    }
}
