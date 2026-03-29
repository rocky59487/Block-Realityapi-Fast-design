package com.blockreality.fastdesign.client.node;

import com.google.gson.*;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.zip.GZIPInputStream;
import java.util.zip.GZIPOutputStream;

/**
 * 節點圖序列化/反序列化 — 設計報告 §12.1 N1-6
 *
 * 支援格式：
 *   - .brgraph（GZIP 壓縮 JSON）
 *   - .json（純文字 JSON，開發用）
 *
 * JSON 結構：
 * {
 *   "version": "1.1",
 *   "name": "My Graph",
 *   "author": "player",
 *   "nodes": [ { "id", "type", "posX", "posY", "inputs": {...}, ... } ],
 *   "wires": [ { "from": "nodeId.portName", "to": "nodeId.portName" } ],
 *   "groups": [ { "id", "name", "color", "nodeIds": [...] } ]
 * }
 */
public final class NodeGraphIO {

    private static final Logger LOGGER = LogManager.getLogger("NodeGraphIO");
    private static final String VERSION = "1.1";
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    private NodeGraphIO() {}

    // ─── 儲存 ───

    /**
     * 儲存節點圖到 .brgraph 檔案（GZIP JSON）。
     */
    public static void save(NodeGraph graph, Path path) throws IOException {
        JsonObject json = serializeGraph(graph);
        String content = GSON.toJson(json);

        if (path.toString().endsWith(".brgraph")) {
            try (OutputStream os = new GZIPOutputStream(Files.newOutputStream(path))) {
                os.write(content.getBytes(StandardCharsets.UTF_8));
            }
        } else {
            Files.writeString(path, content, StandardCharsets.UTF_8);
        }
        LOGGER.info("節點圖已儲存：{} ({} 節點, {} 連線)",
                path, graph.nodeCount(), graph.wireCount());
    }

    /**
     * 序列化為 JSON 字串（用於記憶體內操作）。
     */
    public static String toJson(NodeGraph graph) {
        return GSON.toJson(serializeGraph(graph));
    }

    // ─── 載入 ───

    /**
     * 從 .brgraph 或 .json 檔案載入節點圖。
     */
    public static NodeGraph load(Path path) throws IOException {
        String content;
        if (path.toString().endsWith(".brgraph")) {
            try (InputStream is = new GZIPInputStream(Files.newInputStream(path));
                 Reader reader = new InputStreamReader(is, StandardCharsets.UTF_8)) {
                content = readAll(reader);
            }
        } else {
            content = Files.readString(path, StandardCharsets.UTF_8);
        }
        return fromJson(content);
    }

    /**
     * 從 JSON 字串載入節點圖。
     */
    public static NodeGraph fromJson(String json) {
        JsonObject root = JsonParser.parseString(json).getAsJsonObject();
        return deserializeGraph(root);
    }

    // ─── 序列化實作 ───

    private static JsonObject serializeGraph(NodeGraph graph) {
        JsonObject root = new JsonObject();
        root.addProperty("version", VERSION);
        root.addProperty("name", graph.name());
        root.addProperty("author", graph.author());
        root.addProperty("lastModified", graph.lastModified());

        // 節點
        JsonArray nodesArr = new JsonArray();
        for (BRNode node : graph.allNodes()) {
            JsonObject nodeObj = new JsonObject();
            nodeObj.addProperty("id", node.nodeId());
            nodeObj.addProperty("type", node.typeId());
            nodeObj.addProperty("displayName", node.displayName());
            if (node.displayNameCN() != null) {
                nodeObj.addProperty("displayNameCN", node.displayNameCN());
            }
            nodeObj.addProperty("posX", node.posX());
            nodeObj.addProperty("posY", node.posY());
            nodeObj.addProperty("collapsed", node.isCollapsed());
            nodeObj.addProperty("enabled", node.isEnabled());

            // 輸入端口本地值
            JsonObject inputValues = new JsonObject();
            for (InputPort port : node.inputs()) {
                if (!port.isConnected()) {
                    serializePortValue(inputValues, port);
                }
            }
            nodeObj.add("inputs", inputValues);

            nodesArr.add(nodeObj);
        }
        root.add("nodes", nodesArr);

        // 連線
        JsonArray wiresArr = new JsonArray();
        for (Wire wire : graph.allWires()) {
            if (!wire.isConnected()) continue;
            JsonObject wireObj = new JsonObject();
            wireObj.addProperty("from", wire.serializeFromPath());
            wireObj.addProperty("to", wire.serializeToPath());
            wiresArr.add(wireObj);
        }
        root.add("wires", wiresArr);

        // 群組
        JsonArray groupsArr = new JsonArray();
        for (NodeGroup group : graph.allGroups()) {
            JsonObject groupObj = new JsonObject();
            groupObj.addProperty("id", group.groupId());
            groupObj.addProperty("name", group.name());
            groupObj.addProperty("color", group.color());
            groupObj.addProperty("collapsed", group.isCollapsed());
            JsonArray ids = new JsonArray();
            for (String nodeId : group.nodeIds()) ids.add(nodeId);
            groupObj.add("nodeIds", ids);
            groupsArr.add(groupObj);
        }
        root.add("groups", groupsArr);

        return root;
    }

    private static NodeGraph deserializeGraph(JsonObject root) {
        NodeGraph graph = new NodeGraph();

        if (root.has("name")) graph.setName(root.get("name").getAsString());
        if (root.has("author")) graph.setAuthor(root.get("author").getAsString());

        // 節點
        JsonArray nodesArr = root.getAsJsonArray("nodes");
        if (nodesArr != null) {
            for (JsonElement el : nodesArr) {
                JsonObject nodeObj = el.getAsJsonObject();
                String typeId = nodeObj.get("type").getAsString();

                BRNode node = NodeRegistry.create(typeId);
                if (node == null) {
                    LOGGER.warn("未知節點型別：{}，跳過", typeId);
                    continue;
                }

                // 還原位置和狀態
                float posX = nodeObj.has("posX") ? nodeObj.get("posX").getAsFloat() : 0;
                float posY = nodeObj.has("posY") ? nodeObj.get("posY").getAsFloat() : 0;
                node.setPosition(posX, posY);

                if (nodeObj.has("collapsed")) node.setCollapsed(nodeObj.get("collapsed").getAsBoolean());
                if (nodeObj.has("enabled")) node.setEnabled(nodeObj.get("enabled").getAsBoolean());
                if (nodeObj.has("displayName")) node.setDisplayName(nodeObj.get("displayName").getAsString());
                if (nodeObj.has("displayNameCN")) node.setDisplayNameCN(nodeObj.get("displayNameCN").getAsString());

                // 還原輸入值
                JsonObject inputValues = nodeObj.getAsJsonObject("inputs");
                if (inputValues != null) {
                    for (InputPort port : node.inputs()) {
                        if (inputValues.has(port.name())) {
                            Object val = deserializePortValue(inputValues, port);
                            if (val != null) port.setLocalValue(val);
                        }
                    }
                }

                graph.addNode(node);
            }
        }

        // 連線
        JsonArray wiresArr = root.getAsJsonArray("wires");
        if (wiresArr != null) {
            for (JsonElement el : wiresArr) {
                JsonObject wireObj = el.getAsJsonObject();
                String fromPath = wireObj.get("from").getAsString();
                String toPath = wireObj.get("to").getAsString();

                OutputPort from = graph.findOutputPort(fromPath);
                InputPort to = graph.findInputPort(toPath);

                if (from != null && to != null) {
                    graph.connect(from, to);
                } else {
                    LOGGER.warn("無法還原連線：{} -> {}（端口不存在）", fromPath, toPath);
                }
            }
        }

        // 群組
        JsonArray groupsArr = root.getAsJsonArray("groups");
        if (groupsArr != null) {
            for (JsonElement el : groupsArr) {
                JsonObject groupObj = el.getAsJsonObject();
                String name = groupObj.has("name") ? groupObj.get("name").getAsString() : "Group";
                int color = groupObj.has("color") ? groupObj.get("color").getAsInt() : 0x44FFFFFF;
                NodeGroup group = new NodeGroup(name, color);

                if (groupObj.has("nodeIds")) {
                    for (JsonElement idEl : groupObj.getAsJsonArray("nodeIds")) {
                        BRNode node = graph.getNode(idEl.getAsString());
                        if (node != null) group.addNode(node);
                    }
                }
                graph.addGroup(group);
            }
        }

        LOGGER.info("節點圖已載入：{} ({} 節點, {} 連線, {} 群組)",
                graph.name(), graph.nodeCount(), graph.wireCount(), graph.allGroups().size());
        return graph;
    }

    // ─── 端口值序列化工具 ───

    private static void serializePortValue(JsonObject obj, InputPort port) {
        Object value = port.getRawValue();
        if (value == null) return;
        String key = port.name();

        switch (port.type()) {
            case FLOAT   -> obj.addProperty(key, ((Number) value).floatValue());
            case INT     -> obj.addProperty(key, ((Number) value).intValue());
            case BOOL    -> obj.addProperty(key, (Boolean) value);
            case COLOR   -> obj.addProperty(key, (Integer) value);
            case TEXTURE -> obj.addProperty(key, (Integer) value);
            case VEC2, VEC3, VEC4, CURVE -> {
                float[] arr = (float[]) value;
                JsonArray jarr = new JsonArray();
                for (float v : arr) jarr.add(v);
                obj.add(key, jarr);
            }
            case ENUM -> {
                if (value instanceof Enum<?> e) obj.addProperty(key, e.name());
                else obj.addProperty(key, value.toString());
            }
            default -> {} // MATERIAL, BLOCK, SHAPE, STRUCT — 由連線提供
        }
    }

    private static Object deserializePortValue(JsonObject obj, InputPort port) {
        String key = port.name();
        if (!obj.has(key)) return null;

        return switch (port.type()) {
            case FLOAT   -> obj.get(key).getAsFloat();
            case INT     -> obj.get(key).getAsInt();
            case BOOL    -> obj.get(key).getAsBoolean();
            case COLOR   -> obj.get(key).getAsInt();
            case TEXTURE -> obj.get(key).getAsInt();
            case VEC2, VEC3, VEC4, CURVE -> {
                JsonArray arr = obj.getAsJsonArray(key);
                float[] result = new float[arr.size()];
                for (int i = 0; i < arr.size(); i++) result[i] = arr.get(i).getAsFloat();
                yield result;
            }
            case ENUM -> obj.get(key).getAsString();
            default   -> null;
        };
    }

    // ─── 工具 ───

    /**
     * 列出目錄下所有 .brgraph 檔案。
     */
    public static List<Path> listGraphFiles(Path directory) throws IOException {
        if (!Files.isDirectory(directory)) return List.of();
        try (var stream = Files.list(directory)) {
            return stream
                    .filter(p -> p.toString().endsWith(".brgraph") || p.toString().endsWith(".json"))
                    .sorted()
                    .toList();
        }
    }

    private static String readAll(Reader reader) throws IOException {
        StringBuilder sb = new StringBuilder();
        char[] buf = new char[8192];
        int read;
        while ((read = reader.read(buf)) != -1) {
            sb.append(buf, 0, read);
        }
        return sb.toString();
    }
}
