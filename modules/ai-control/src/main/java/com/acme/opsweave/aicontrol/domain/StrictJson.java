package com.acme.opsweave.aicontrol.domain;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * A strict, dependency-free JSON reader for the Skill registry (ADR-105).
 *
 * <p>The ai-control domain compiles with the JDK alone, so the deterministic Skill checks cannot
 * borrow a JSON library. This reader accepts exactly RFC 8259 JSON — no comments, no trailing
 * commas, no single quotes, no NaN — and returns plain {@link Map}/{@link List}/{@link String}/
 * {@link Double}/{@link Boolean}/{@code null} values. Anything else fails closed.
 *
 * <p>It only reads; it never executes content, resolves references or fetches anything. Depth and
 * input size are bounded so a hostile document cannot exhaust the stack or the heap.
 */
final class StrictJson {

    /** Nesting depth bound; a document deeper than this is rejected, not truncated. */
    private static final int MAX_DEPTH = 32;

    private final String text;
    private int at;

    private StrictJson(String text) {
        this.text = text;
    }

    /** Parse one complete JSON value; trailing content or malformed input fails closed. */
    static Object parse(String text) {
        if (text == null || text.length() > 131072) throw new IllegalArgumentException("JSON input out of bounds");
        var reader = new StrictJson(text);
        reader.skipWhitespace();
        Object value = reader.value(0);
        reader.skipWhitespace();
        if (reader.at != text.length()) throw new IllegalArgumentException("Trailing JSON content");
        return value;
    }

    /** Parse a JSON object; arrays and scalars are rejected because callers need named members. */
    static Map<String, Object> parseObject(String text) {
        Object value = parse(text);
        if (!(value instanceof Map<?, ?>)) throw new IllegalArgumentException("JSON object expected");
        @SuppressWarnings("unchecked")
        var object = (Map<String, Object>) value;
        return object;
    }

    private Object value(int depth) {
        if (depth > MAX_DEPTH) throw new IllegalArgumentException("JSON nesting too deep");
        if (at >= text.length()) throw new IllegalArgumentException("Truncated JSON");
        char c = text.charAt(at);
        return switch (c) {
            case '{' -> object(depth);
            case '[' -> array(depth);
            case '"' -> string();
            case 't' -> literal("true", Boolean.TRUE);
            case 'f' -> literal("false", Boolean.FALSE);
            case 'n' -> literal("null", null);
            default -> number();
        };
    }

    private Map<String, Object> object(int depth) {
        var members = new LinkedHashMap<String, Object>();
        at++; // consume '{'
        skipWhitespace();
        if (peek() == '}') {
            at++;
            return members;
        }
        while (true) {
            skipWhitespace();
            if (peek() != '"') throw new IllegalArgumentException("JSON object key expected");
            String key = string();
            if (members.containsKey(key)) throw new IllegalArgumentException("Duplicate JSON key");
            skipWhitespace();
            if (peek() != ':') throw new IllegalArgumentException("JSON ':' expected");
            at++;
            skipWhitespace();
            members.put(key, value(depth + 1));
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                at++;
                continue;
            }
            if (c == '}') {
                at++;
                return members;
            }
            throw new IllegalArgumentException("JSON object member boundary expected");
        }
    }

    private List<Object> array(int depth) {
        var items = new ArrayList<Object>();
        at++; // consume '['
        skipWhitespace();
        if (peek() == ']') {
            at++;
            return items;
        }
        while (true) {
            skipWhitespace();
            items.add(value(depth + 1));
            skipWhitespace();
            char c = peek();
            if (c == ',') {
                at++;
                continue;
            }
            if (c == ']') {
                at++;
                return items;
            }
            throw new IllegalArgumentException("JSON array item boundary expected");
        }
    }

    private String string() {
        at++; // consume opening quote
        var out = new StringBuilder();
        while (true) {
            if (at >= text.length()) throw new IllegalArgumentException("Unterminated JSON string");
            char c = text.charAt(at++);
            if (c == '"') return out.toString();
            if (c == '\\') {
                if (at >= text.length()) throw new IllegalArgumentException("Unterminated JSON escape");
                char escape = text.charAt(at++);
                switch (escape) {
                    case '"' -> out.append('"');
                    case '\\' -> out.append('\\');
                    case '/' -> out.append('/');
                    case 'b' -> out.append('\b');
                    case 'f' -> out.append('\f');
                    case 'n' -> out.append('\n');
                    case 'r' -> out.append('\r');
                    case 't' -> out.append('\t');
                    case 'u' -> out.append(unicode());
                    default -> throw new IllegalArgumentException("Unknown JSON escape");
                }
            } else if (c < 0x20) {
                throw new IllegalArgumentException("Control character in JSON string");
            } else {
                out.append(c);
            }
        }
    }

    private char unicode() {
        if (at + 4 > text.length()) throw new IllegalArgumentException("Truncated JSON escape");
        int value = 0;
        for (int i = 0; i < 4; i++) {
            char c = text.charAt(at++);
            int digit = Character.digit(c, 16);
            if (digit < 0) throw new IllegalArgumentException("Invalid JSON escape");
            value = value * 16 + digit;
        }
        return (char) value;
    }

    private Object literal(String spelling, Object value) {
        if (!text.startsWith(spelling, at)) throw new IllegalArgumentException("Invalid JSON literal");
        at += spelling.length();
        return value;
    }

    private Double number() {
        int start = at;
        if (peek() == '-') at++;
        if (at >= text.length() || !Character.isDigit(text.charAt(at))) throw new IllegalArgumentException("Invalid JSON number");
        if (text.charAt(at) == '0') {
            at++;
        } else {
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
        }
        if (at < text.length() && text.charAt(at) == '.') {
            at++;
            if (at >= text.length() || !Character.isDigit(text.charAt(at))) throw new IllegalArgumentException("Invalid JSON number");
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
        }
        if (at < text.length() && (text.charAt(at) == 'e' || text.charAt(at) == 'E')) {
            at++;
            if (at < text.length() && (text.charAt(at) == '+' || text.charAt(at) == '-')) at++;
            if (at >= text.length() || !Character.isDigit(text.charAt(at))) throw new IllegalArgumentException("Invalid JSON number");
            while (at < text.length() && Character.isDigit(text.charAt(at))) at++;
        }
        return Double.parseDouble(text.substring(start, at));
    }

    private char peek() {
        if (at >= text.length()) throw new IllegalArgumentException("Truncated JSON");
        return text.charAt(at);
    }

    private void skipWhitespace() {
        while (at < text.length()) {
            char c = text.charAt(at);
            if (c == ' ' || c == '\t' || c == '\n' || c == '\r') at++;
            else break;
        }
    }
}
