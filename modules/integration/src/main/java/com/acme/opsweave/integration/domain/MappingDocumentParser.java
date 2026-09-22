package com.acme.opsweave.integration.domain;

import com.acme.opsweave.telemetry.domain.MetricType;
import com.acme.opsweave.telemetry.domain.MetricValueType;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Reads the mapping document subset used under extensions/mappings.
 * Documents are indentation maps, scalar lists, and quoted scalars. Anchors and multiline values are rejected.
 */
public final class MappingDocumentParser {
    private MappingDocumentParser() {}

    public static MappingDefinition parse(String document) {
        Object root = parseNode(lines(document), new int[] {0});
        if (!(root instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Mapping document must be a map");
        }
        Map<String, Object> source = child(map, "source");
        Map<String, Object> target = child(map, "target");
        Map<String, Object> transform = child(map, "transform");
        String operation = text(transform, "operation");
        String factor = text(transform, "factor");
        return new MappingDefinition(
            text(map, "id"),
            text(source, "connector"),
            text(source, "itemKeyExact"),
            text(target, "metric"),
            text(target, "displayName"),
            metricType(text(target, "kind")),
            text(target, "unit"),
            valueType(text(target, "valueType")),
            stringList(target.get("dimensionSchema")),
            stringMap(target.get("dimensions")),
            operation + ":" + factor,
            revision(text(map, "version"))
        );
    }

    private static List<Raw> lines(String document) {
        List<Raw> parsed = new ArrayList<>();
        for (String raw : document.split("\n", -1)) {
            String stripped = stripComment(raw);
            if (stripped.isBlank()) {
                continue;
            }
            int indent = 0;
            while (indent < stripped.length() && stripped.charAt(indent) == ' ') {
                indent++;
            }
            if (stripped.charAt(indent) == '\t') {
                throw new IllegalArgumentException("Mapping documents must not use tabs");
            }
            parsed.add(new Raw(indent, stripped.substring(indent).trim()));
        }
        if (parsed.isEmpty()) {
            throw new IllegalArgumentException("Mapping document is empty");
        }
        return parsed;
    }

    private static Object parseNode(List<Raw> lines, int[] index) {
        if (index[0] >= lines.size()) {
            throw new IllegalArgumentException("Mapping document ended early");
        }
        Raw current = lines.get(index[0]);
        if (current.text.startsWith("- ")) {
            return parseList(lines, index, current.indent);
        }
        return parseMap(lines, index, current.indent);
    }

    private static List<String> parseList(List<Raw> lines, int[] index, int indent) {
        List<String> values = new ArrayList<>();
        while (index[0] < lines.size()) {
            Raw current = lines.get(index[0]);
            if (current.indent != indent || !current.text.startsWith("- ")) {
                break;
            }
            String value = current.text.substring(2).trim();
            if (value.isEmpty() || value.endsWith(":")) {
                throw new IllegalArgumentException("Mapping lists must be scalars");
            }
            values.add(unquote(value));
            index[0]++;
        }
        return values;
    }

    private static Map<String, Object> parseMap(List<Raw> lines, int[] index, int indent) {
        Map<String, Object> values = new LinkedHashMap<>();
        while (index[0] < lines.size()) {
            Raw current = lines.get(index[0]);
            if (current.indent != indent || current.text.startsWith("- ")) {
                break;
            }
            int colon = current.text.indexOf(':');
            if (colon < 1) {
                throw new IllegalArgumentException("Mapping entry must contain a key");
            }
            String key = current.text.substring(0, colon).trim();
            String value = current.text.substring(colon + 1).trim();
            index[0]++;
            if (value.isEmpty()) {
                values.put(key, parseNode(lines, index));
            } else {
                values.put(key, unquote(value));
            }
        }
        return values;
    }

    private static Map<String, Object> child(Map<?, ?> parent, String name) {
        Object value = parent.get(name);
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Mapping document is missing " + name);
        }
        Map<String, Object> copy = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            copy.put(String.valueOf(entry.getKey()), entry.getValue());
        }
        return copy;
    }

    private static String text(Map<?, ?> map, String name) {
        Object value = map.get(name);
        if (!(value instanceof String text) || text.isBlank()) {
            throw new IllegalArgumentException("Mapping document is missing " + name);
        }
        return text;
    }

    private static List<String> stringList(Object value) {
        if (!(value instanceof List<?> list) || list.isEmpty()) {
            throw new IllegalArgumentException("Mapping document is missing dimensionSchema");
        }
        List<String> names = new ArrayList<>();
        for (Object item : list) {
            if (!(item instanceof String text) || text.isBlank()) {
                throw new IllegalArgumentException("Dimension schema names must be present");
            }
            names.add(text);
        }
        return names;
    }

    private static Map<String, String> stringMap(Object value) {
        if (!(value instanceof Map<?, ?> map)) {
            throw new IllegalArgumentException("Mapping document is missing dimensions");
        }
        Map<String, String> dimensions = new LinkedHashMap<>();
        for (Map.Entry<?, ?> entry : map.entrySet()) {
            if (!(entry.getValue() instanceof String text)) {
                throw new IllegalArgumentException("Fixed dimensions must be scalars");
            }
            dimensions.put(String.valueOf(entry.getKey()), text);
        }
        return dimensions;
    }

    private static MetricType metricType(String kind) {
        return switch (kind) {
            case "gauge" -> MetricType.GAUGE;
            case "counter", "sum" -> MetricType.SUM;
            case "histogram" -> MetricType.HISTOGRAM;
            default -> throw new IllegalArgumentException("Unknown metric kind");
        };
    }

    private static MetricValueType valueType(String valueType) {
        return switch (valueType) {
            case "double" -> MetricValueType.DOUBLE;
            case "integer" -> MetricValueType.INTEGER;
            case "string" -> MetricValueType.STRING;
            default -> throw new IllegalArgumentException("Unknown metric value type");
        };
    }

    private static int revision(String version) {
        int dot = version.indexOf('.');
        String major = dot < 0 ? version : version.substring(0, dot);
        try {
            int revision = Integer.parseInt(major);
            if (revision < 1) {
                throw new IllegalArgumentException("Mapping revision must start at 1");
            }
            return revision;
        } catch (NumberFormatException failed) {
            throw new IllegalArgumentException("Mapping version must start with a revision");
        }
    }

    private static String stripComment(String line) {
        boolean quoted = false;
        char quote = 0;
        for (int i = 0; i < line.length(); i++) {
            char current = line.charAt(i);
            if (quoted) {
                if (current == quote) {
                    quoted = false;
                }
                continue;
            }
            if (current == '\'' || current == '"') {
                quoted = true;
                quote = current;
                continue;
            }
            if (current == '#') {
                return line.substring(0, i);
            }
        }
        return line;
    }

    private static String unquote(String value) {
        if (value.length() >= 2) {
            char first = value.charAt(0);
            char last = value.charAt(value.length() - 1);
            if ((first == '\'' && last == '\'') || (first == '"' && last == '"')) {
                return value.substring(1, value.length() - 1);
            }
        }
        return value;
    }

    private record Raw(int indent, String text) {}
}
