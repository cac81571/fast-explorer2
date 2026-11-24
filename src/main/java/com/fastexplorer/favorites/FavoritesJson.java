package com.fastexplorer.favorites;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/** JSON codec for a favorites tree. UTF-8, no extra libraries. */
public final class FavoritesJson {
    private FavoritesJson() {
    }

    public static String write(FavoriteTree tree) {
        StringBuilder out = new StringBuilder();
        out.append("{\n  \"items\": ");
        writeNodes(out, tree.roots(), 2);
        out.append("\n}\n");
        return out.toString();
    }

    public static FavoriteTree read(String json) {
        if (json == null || json.isBlank()) {
            return new FavoriteTree();
        }
        String text = json.charAt(0) == '\uFEFF' ? json.substring(1) : json;
        Parser parser = new Parser(text);
        Object value = parser.parseValue();
        parser.skip();
        if (parser.position() != text.length()) {
            throw new IllegalArgumentException("お気に入り JSON の末尾に余分な文字があります");
        }
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException("お気に入り JSON はオブジェクトである必要があります");
        }
        Object items = object.get("items");
        FavoriteTree tree = new FavoriteTree();
        if (items == null) {
            return tree;
        }
        if (!(items instanceof List<?> list)) {
            throw new IllegalArgumentException("items は配列である必要があります");
        }
        for (Object item : list) {
            FavoriteNode node = readNode(item);
            if (node != null) {
                tree.addCopy(node);
            }
        }
        return tree;
    }

    private static void writeNodes(StringBuilder out, List<FavoriteNode> nodes, int indent) {
        if (nodes.isEmpty()) {
            out.append("[]");
            return;
        }
        out.append("[\n");
        for (int i = 0; i < nodes.size(); i++) {
            indent(out, indent + 1);
            writeNode(out, nodes.get(i), indent + 1);
            if (i < nodes.size() - 1) {
                out.append(',');
            }
            out.append('\n');
        }
        indent(out, indent);
        out.append(']');
    }

    private static void writeNode(StringBuilder out, FavoriteNode node, int indent) {
        out.append("{\n");
        field(out, indent + 1, "id", node.id(), true);
        field(out, indent + 1, "name", node.name(), true);
        indent(out, indent + 1);
        out.append("\"group\": ").append(node.isGroup());
        if (!node.isGroup()) {
            out.append(",\n");
            field(out, indent + 1, "path", node.path() == null ? "" : node.path(), false);
        } else {
            out.append(",\n");
            indent(out, indent + 1);
            out.append("\"children\": ");
            writeNodes(out, node.children(), indent + 1);
            out.append('\n');
        }
        indent(out, indent);
        out.append('}');
    }

    private static void field(StringBuilder out, int indent, String key, String value, boolean comma) {
        indent(out, indent);
        out.append('"').append(key).append("\": ");
        quote(out, value);
        if (comma) {
            out.append(",\n");
        } else {
            out.append('\n');
        }
    }

    private static void indent(StringBuilder out, int level) {
        out.append("  ".repeat(level));
    }

    static void quote(StringBuilder out, String value) {
        out.append('"');
        for (int i = 0; i < value.length(); i++) {
            char c = value.charAt(i);
            switch (c) {
                case '"' -> out.append("\\\"");
                case '\\' -> out.append("\\\\");
                case '\n' -> out.append("\\n");
                case '\r' -> out.append("\\r");
                case '\t' -> out.append("\\t");
                default -> {
                    if (c < 0x20) {
                        out.append(String.format("\\u%04x", (int) c));
                    } else {
                        out.append(c);
                    }
                }
            }
        }
        out.append('"');
    }

    private static FavoriteNode readNode(Object value) {
        if (!(value instanceof Map<?, ?> object)) {
            throw new IllegalArgumentException("お気に入りの項目はオブジェクトである必要があります");
        }
        String id = stringField(object, "id");
        if (id == null || id.isBlank()) {
            id = java.util.UUID.randomUUID().toString();
        }
        String name = stringField(object, "name");
        boolean group = Boolean.TRUE.equals(object.get("group"));
        if (group) {
            FavoriteNode node = new FavoriteNode(id, name == null || name.isBlank() ? "名称未設定" : name, true, null);
            Object children = object.get("children");
            if (children instanceof List<?> list) {
                for (Object child : list) {
                    FavoriteNode nested = readNode(child);
                    if (nested != null) {
                        node.childList().add(nested);
                    }
                }
            }
            return node;
        }
        String path = stringField(object, "path");
        if (path == null || path.isBlank()) {
            return null;
        }
        return new FavoriteNode(id, name == null || name.isBlank() ? FavoriteNames.displayName(path) : name, false, path);
    }

    private static String stringField(Map<?, ?> object, String key) {
        Object value = object.get(key);
        return value instanceof String text ? text : null;
    }

    private static final class Parser {
        private final String text;
        private int index;

        private Parser(String text) {
            this.text = text;
        }

        int position() {
            return index;
        }

        Object parseValue() {
            skip();
            if (index >= text.length()) {
                throw error("値がありません");
            }
            char c = text.charAt(index);
            if (c == '{') {
                return parseObject();
            }
            if (c == '[') {
                return parseArray();
            }
            if (c == '"') {
                return parseString();
            }
            if (c == 't' || c == 'f') {
                return parseBoolean();
            }
            if (c == 'n') {
                expect("null");
                return null;
            }
            if (c == '-' || (c >= '0' && c <= '9')) {
                parseNumber();
                return null;
            }
            throw error("想定外の文字です: " + c);
        }

        private Map<String, Object> parseObject() {
            expect("{");
            Map<String, Object> object = new LinkedHashMap<>();
            skip();
            if (peek('}')) {
                index++;
                return object;
            }
            while (true) {
                skip();
                String key = parseString();
                skip();
                expect(":");
                object.put(key, parseValue());
                skip();
                if (peek('}')) {
                    index++;
                    return object;
                }
                expect(",");
            }
        }

        private List<Object> parseArray() {
            expect("[");
            List<Object> list = new ArrayList<>();
            skip();
            if (peek(']')) {
                index++;
                return list;
            }
            while (true) {
                list.add(parseValue());
                skip();
                if (peek(']')) {
                    index++;
                    return list;
                }
                expect(",");
            }
        }

        private String parseString() {
            skip();
            expect("\"");
            StringBuilder value = new StringBuilder();
            while (index < text.length()) {
                char c = text.charAt(index++);
                if (c == '"') {
                    return value.toString();
                }
                if (c != '\\') {
                    value.append(c);
                    continue;
                }
                if (index >= text.length()) {
                    throw error("文字列が閉じていません");
                }
                char escaped = text.charAt(index++);
                switch (escaped) {
                    case '"', '\\', '/' -> value.append(escaped);
                    case 'b' -> value.append('\b');
                    case 'f' -> value.append('\f');
                    case 'n' -> value.append('\n');
                    case 'r' -> value.append('\r');
                    case 't' -> value.append('\t');
                    case 'u' -> {
                        if (index + 4 > text.length()) {
                            throw error("\\u が途中で終わっています");
                        }
                        int code = Integer.parseInt(text.substring(index, index + 4), 16);
                        value.append((char) code);
                        index += 4;
                    }
                    default -> throw error("不明なエスケープです");
                }
            }
            throw error("文字列が閉じていません");
        }

        private boolean parseBoolean() {
            if (text.startsWith("true", index)) {
                index += 4;
                return true;
            }
            if (text.startsWith("false", index)) {
                index += 5;
                return false;
            }
            throw error("真偽値ではありません");
        }

        private void parseNumber() {
            if (peek('-')) {
                index++;
            }
            while (index < text.length()) {
                char c = text.charAt(index);
                if ((c >= '0' && c <= '9') || c == '.' || c == 'e' || c == 'E' || c == '+' || c == '-') {
                    index++;
                } else {
                    break;
                }
            }
        }

        private void skip() {
            while (index < text.length() && Character.isWhitespace(text.charAt(index))) {
                index++;
            }
        }

        private boolean peek(char c) {
            return index < text.length() && text.charAt(index) == c;
        }

        private void expect(String token) {
            skip();
            if (!text.startsWith(token, index)) {
                throw error(token + " が必要です");
            }
            index += token.length();
        }

        private IllegalArgumentException error(String message) {
            return new IllegalArgumentException(message + " (" + index + ")");
        }
    }
}
