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

    public static void putPath(ObjectNode root, String path, long value)   { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, int value)    { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, double value) { descend(root, path).put(leaf(path), value); }
    public static void putPath(ObjectNode root, String path, String value) { descend(root, path).put(leaf(path), value); }
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
}
