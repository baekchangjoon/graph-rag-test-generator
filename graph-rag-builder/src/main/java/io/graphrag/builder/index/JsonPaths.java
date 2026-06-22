package io.graphrag.builder.index;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.node.ObjectNode;

/** dot-path("a.b.c") 인지 JSON put/remove. 중간이 없거나 non-Object면 새 ObjectNode로 교체(.with() 금지). */
public final class JsonPaths {
    private JsonPaths() {}

    private static ObjectNode descend(ObjectNode root, String path) {
        String[] seg = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < seg.length - 1; i++) {
            JsonNode child = node.get(seg[i]);
            if (!(child instanceof ObjectNode)) {
                ObjectNode created = node.objectNode();
                node.set(seg[i], created);
                child = created;
            }
            node = (ObjectNode) child;
        }
        return node;
    }
    private static String leaf(String path) {
        int dot = path.lastIndexOf('.');
        return dot < 0 ? path : path.substring(dot + 1);
    }

    public static void putPath(ObjectNode root, String path, long value)    { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, int value)     { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, double value)  { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, boolean value) { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, String value)  { descend(root, path).put(leaf(path), value); }
    public static void putNullPath(ObjectNode root, String path) { descend(root, path).putNull(leaf(path)); }
    public static void removePath(ObjectNode root, String path) {
        String[] seg = path.split("\\.");
        ObjectNode node = root;
        for (int i = 0; i < seg.length - 1; i++) {
            JsonNode child = node.get(seg[i]);
            if (!(child instanceof ObjectNode)) { return; }
            node = (ObjectNode) child;
        }
        node.remove(leaf(path));
    }

    /** 최상위 키 중 '.'를 포함하는 것을 중첩 객체 경로로 이동(노드 타입 보존). 점 없는 키는 불변. */
    public static void nestDottedKeys(ObjectNode body) {
        java.util.List<String> dotted = new java.util.ArrayList<>();
        body.fieldNames().forEachRemaining(n -> { if (n.contains(".")) dotted.add(n); });
        for (String name : dotted) {
            JsonNode value = body.remove(name);
            String[] seg = name.split("\\.");
            ObjectNode node = body;
            for (int i = 0; i < seg.length - 1; i++) {
                JsonNode child = node.get(seg[i]);
                if (!(child instanceof ObjectNode)) { ObjectNode c = node.objectNode(); node.set(seg[i], c); child = c; }
                node = (ObjectNode) child;
            }
            node.set(seg[seg.length - 1], value);
        }
    }
}
