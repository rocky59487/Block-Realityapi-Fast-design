package com.blockreality.api.node;

import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;
import java.util.function.Function;

/**
 * 節點圖 JSON 序列化 / 反序列化。
 *
 * ★ review-fix ICReM-7: API 層只提供序列化引擎和可插拔的節點工廠註冊。
 *   具體節點類型（渲染、材質等）由模組層（fastdesign/architect）註冊。
 *   API 不直接依賴任何具體節點實作。
 *
 * 格式版本 1.1 — 支援節點位置、折疊狀態、端口值、連線。
 */
public final class NodeGraphIO {

    private NodeGraphIO() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-NodeIO");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Node factory registry: type name → constructor (由模組層註冊) */
    private static final Map<String, Supplier<BRNode>> nodeFactories = new HashMap<>();

    /** Node type name resolver: node → type string (由模組層註冊) */
    private static final List<Function<BRNode, String>> typeNameResolvers = new ArrayList<>();

    /** Preset factory: preset name → graph builder (由模組層註冊) */
    private static final Map<String, Supplier<NodeGraph>> presetFactories = new LinkedHashMap<>();

    // ★ ICReM-7: 不再有 static {} 硬編碼節點類型
    // 模組層在初始化時呼叫 registerNodeType() 和 registerPreset() 註冊

    /**
     * 註冊節點類型（用於反序列化）。
     * 由模組層（fastdesign）在初始化時呼叫。
     *
     * @param typeName 存入 JSON 的類型識別名
     * @param factory  建立該類型新實例的工廠函數
     */
    public static void registerNodeType(String typeName, Supplier<BRNode> factory) {
        nodeFactories.put(typeName, factory);
    }

    /**
     * ★ ICReM-7: 註冊類型名稱解析器。
     * 序列化時，依序呼叫解析器直到有一個返回非 null 值。
     * 由模組層註冊特殊的類型名稱邏輯（如 EffectToggleNode_ssao）。
     *
     * @param resolver 函數：node → typeName（返回 null 表示不處理）
     */
    public static void registerTypeNameResolver(Function<BRNode, String> resolver) {
        typeNameResolvers.add(resolver);
    }

    /**
     * ★ ICReM-7: 註冊品質預設工廠。
     * 由模組層註冊預設圖（Potato, Low, Medium, High, Ultra）。
     */
    public static void registerPreset(String presetName, Supplier<NodeGraph> factory) {
        presetFactories.put(presetName.toLowerCase(), factory);
    }

    /**
     * 取得所有已註冊的預設名稱。
     */
    public static Set<String> getRegisteredPresetNames() {
        return Collections.unmodifiableSet(presetFactories.keySet());
    }

    /**
     * 取得已註冊的節點類型數量。
     */
    public static int getRegisteredTypeCount() {
        return nodeFactories.size();
    }

    // ═══════════════════════════════════════════════════════
    //  序列化
    // ═══════════════════════════════════════════════════════

    /**
     * Serialize a node graph to JSON string.
     */
    public static String serialize(NodeGraph graph) {
        JsonObject root = new JsonObject();
        root.addProperty("version", "1.1");
        root.addProperty("name", graph.getName());

        // Nodes array
        JsonArray nodesArray = new JsonArray();
        for (BRNode node : graph.getAllNodes()) {
            JsonObject nodeObj = new JsonObject();
            nodeObj.addProperty("id", node.getNodeId());
            nodeObj.addProperty("type", resolveTypeName(node));
            nodeObj.addProperty("displayName", node.getDisplayName());
            nodeObj.addProperty("category", node.getCategory());
            nodeObj.addProperty("posX", node.getPosX());
            nodeObj.addProperty("posY", node.getPosY());
            nodeObj.addProperty("enabled", node.isEnabled());
            nodeObj.addProperty("collapsed", node.isCollapsed());

            // Save input port values
            JsonObject portsObj = new JsonObject();
            for (NodePort port : node.getInputs()) {
                if (port.getValue() != null) {
                    portsObj.add(port.getName(), serializeValue(port));
                }
            }
            nodeObj.add("inputs", portsObj);
            nodesArray.add(nodeObj);
        }
        root.add("nodes", nodesArray);

        // Wires array
        JsonArray wiresArray = new JsonArray();
        for (BRNode node : graph.getAllNodes()) {
            for (NodePort input : node.getInputs()) {
                Wire wire = input.getConnectedWire();
                if (wire != null) {
                    NodePort source = wire.getSource();
                    JsonObject wireObj = new JsonObject();
                    wireObj.addProperty("fromNode", source.getOwner().getNodeId());
                    wireObj.addProperty("fromPort", source.getName());
                    wireObj.addProperty("toNode", node.getNodeId());
                    wireObj.addProperty("toPort", input.getName());
                    wiresArray.add(wireObj);
                }
            }
        }
        root.add("wires", wiresArray);

        return GSON.toJson(root);
    }

    // ═══════════════════════════════════════════════════════
    //  反序列化
    // ═══════════════════════════════════════════════════════

    /**
     * Deserialize a node graph from JSON string.
     */
    public static NodeGraph deserialize(String json) {
        JsonObject root = GSON.fromJson(json, JsonObject.class);
        String version = root.has("version") ? root.get("version").getAsString() : "1.0";
        String name = root.has("name") ? root.get("name").getAsString() : "Untitled";

        NodeGraph graph = new NodeGraph(name);
        Map<String, BRNode> nodeById = new HashMap<>();
        Map<String, String> oldIdToNewId = new HashMap<>();

        JsonArray nodesArray = root.getAsJsonArray("nodes");
        if (nodesArray != null) {
            for (JsonElement elem : nodesArray) {
                JsonObject nodeObj = elem.getAsJsonObject();
                if (!nodeObj.has("type") || !nodeObj.has("id")) {
                    LOG.warn("[NodeIO] 跳過無效節點條目（缺少 'type' 或 'id' 欄位）");
                    continue;
                }
                String typeName = nodeObj.get("type").getAsString();
                String savedId = nodeObj.get("id").getAsString();

                Supplier<BRNode> factory = nodeFactories.get(typeName);
                if (factory == null) {
                    LOG.warn("[NodeIO] 未知節點類型 '{}' (id={})，跳過。是否忘記註冊？", typeName, savedId);
                    continue;
                }

                BRNode node = factory.get();
                oldIdToNewId.put(savedId, node.getNodeId());

                if (nodeObj.has("posX")) node.setPosX(nodeObj.get("posX").getAsFloat());
                if (nodeObj.has("posY")) node.setPosY(nodeObj.get("posY").getAsFloat());
                if (nodeObj.has("enabled")) node.setEnabled(nodeObj.get("enabled").getAsBoolean());
                if (nodeObj.has("collapsed")) node.setCollapsed(nodeObj.get("collapsed").getAsBoolean());

                if (nodeObj.has("inputs")) {
                    JsonObject portsObj = nodeObj.getAsJsonObject("inputs");
                    for (Map.Entry<String, JsonElement> entry : portsObj.entrySet()) {
                        NodePort port = node.getInput(entry.getKey());
                        if (port != null) {
                            Object value = deserializeValue(entry.getValue(), port.getType());
                            if (value != null) {
                                port.setValue(value);
                            }
                        }
                    }
                }

                graph.addNode(node);
                nodeById.put(node.getNodeId(), node);
            }
        }

        // Deserialize wires
        JsonArray wiresArray = root.getAsJsonArray("wires");
        if (wiresArray != null) {
            for (JsonElement elem : wiresArray) {
                JsonObject wireObj = elem.getAsJsonObject();
                String savedFromNodeId = wireObj.get("fromNode").getAsString();
                String fromPortName = wireObj.get("fromPort").getAsString();
                String savedToNodeId = wireObj.get("toNode").getAsString();
                String toPortName = wireObj.get("toPort").getAsString();

                String newFromNodeId = oldIdToNewId.getOrDefault(savedFromNodeId, savedFromNodeId);
                String newToNodeId = oldIdToNewId.getOrDefault(savedToNodeId, savedToNodeId);

                BRNode fromNode = nodeById.get(newFromNodeId);
                BRNode toNode = nodeById.get(newToNodeId);

                if (fromNode == null || toNode == null) {
                    LOG.warn("[NodeIO] 連線參照不存在的節點: {} → {}", newFromNodeId, newToNodeId);
                    continue;
                }

                NodePort fromPort = fromNode.getOutput(fromPortName);
                NodePort toPort = toNode.getInput(toPortName);

                if (fromPort == null || toPort == null) {
                    LOG.warn("[NodeIO] 連線參照不存在的端口: {}.{} → {}.{}",
                        newFromNodeId, fromPortName, newToNodeId, toPortName);
                    continue;
                }

                graph.connect(fromPort, toPort);
            }
        }

        LOG.info("[NodeIO] 反序列化完成 — '{}' (v{}) {} 節點, {} 連線",
            name, version,
            nodesArray != null ? nodesArray.size() : 0,
            wiresArray != null ? wiresArray.size() : 0);

        return graph;
    }

    // ═══════════════════════════════════════════════════════
    //  檔案 I/O
    // ═══════════════════════════════════════════════════════

    public static void saveToFile(NodeGraph graph, Path path) throws IOException {
        String json = serialize(graph);
        Files.createDirectories(path.getParent());
        Files.writeString(path, json, StandardCharsets.UTF_8);
        LOG.info("[NodeIO] 節點圖儲存至: {}", path);
    }

    public static NodeGraph loadFromFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("節點圖檔案不存在: " + path);
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        LOG.info("[NodeIO] 從檔案載入節點圖: {}", path);
        return deserialize(json);
    }

    /**
     * ★ ICReM-7: 從已註冊的預設工廠載入預設。
     * 預設由模組層透過 registerPreset() 註冊。
     */
    public static NodeGraph loadPreset(String presetName) {
        Supplier<NodeGraph> factory = presetFactories.get(presetName.toLowerCase());
        if (factory != null) {
            return factory.get();
        }
        LOG.warn("[NodeIO] 未知預設名稱 '{}', 已註冊: {}", presetName, presetFactories.keySet());
        // 回退：嘗試 "high"
        Supplier<NodeGraph> fallback = presetFactories.get("high");
        if (fallback != null) return fallback.get();
        // 最終回退：空圖
        return new NodeGraph("Default");
    }

    // ═══════════════════════════════════════════════════════
    //  值序列化 / 反序列化
    // ═══════════════════════════════════════════════════════

    private static JsonElement serializeValue(NodePort port) {
        Object value = port.getValue();
        if (value == null) return JsonNull.INSTANCE;

        return switch (port.getType()) {
            case FLOAT -> new JsonPrimitive(((Number) value).floatValue());
            case INT, COLOR, TEXTURE -> new JsonPrimitive(((Number) value).intValue());
            case BOOL -> new JsonPrimitive((Boolean) value);
            case VEC2 -> {
                float[] v = (float[]) value;
                JsonArray arr = new JsonArray();
                arr.add(v.length > 0 ? v[0] : 0f);
                arr.add(v.length > 1 ? v[1] : 0f);
                yield arr;
            }
            case VEC3 -> {
                float[] v = (float[]) value;
                JsonArray arr = new JsonArray();
                arr.add(v.length > 0 ? v[0] : 0f);
                arr.add(v.length > 1 ? v[1] : 0f);
                arr.add(v.length > 2 ? v[2] : 0f);
                yield arr;
            }
            case VEC4 -> {
                float[] v = (float[]) value;
                JsonArray arr = new JsonArray();
                arr.add(v.length > 0 ? v[0] : 0f);
                arr.add(v.length > 1 ? v[1] : 0f);
                arr.add(v.length > 2 ? v[2] : 0f);
                arr.add(v.length > 3 ? v[3] : 0f);
                yield arr;
            }
            case CURVE -> {
                float[] v = (float[]) value;
                JsonArray arr = new JsonArray();
                for (float f : v) arr.add(f);
                yield arr;
            }
            case ENUM -> new JsonPrimitive(value.toString());
            default -> new JsonPrimitive(value.toString());
        };
    }

    private static Object deserializeValue(JsonElement elem, PortType type) {
        if (elem == null || elem.isJsonNull()) return null;

        try {
            return switch (type) {
                case FLOAT -> elem.getAsFloat();
                case INT, COLOR, TEXTURE -> elem.getAsInt();
                case BOOL -> elem.getAsBoolean();
                case VEC2 -> {
                    JsonArray arr = elem.getAsJsonArray();
                    yield new float[]{
                        arr.size() > 0 ? arr.get(0).getAsFloat() : 0f,
                        arr.size() > 1 ? arr.get(1).getAsFloat() : 0f
                    };
                }
                case VEC3 -> {
                    JsonArray arr = elem.getAsJsonArray();
                    yield new float[]{
                        arr.size() > 0 ? arr.get(0).getAsFloat() : 0f,
                        arr.size() > 1 ? arr.get(1).getAsFloat() : 0f,
                        arr.size() > 2 ? arr.get(2).getAsFloat() : 0f
                    };
                }
                case VEC4 -> {
                    JsonArray arr = elem.getAsJsonArray();
                    yield new float[]{
                        arr.size() > 0 ? arr.get(0).getAsFloat() : 0f,
                        arr.size() > 1 ? arr.get(1).getAsFloat() : 0f,
                        arr.size() > 2 ? arr.get(2).getAsFloat() : 0f,
                        arr.size() > 3 ? arr.get(3).getAsFloat() : 0f
                    };
                }
                case CURVE -> {
                    JsonArray arr = elem.getAsJsonArray();
                    float[] vals = new float[arr.size()];
                    for (int i = 0; i < arr.size(); i++) vals[i] = arr.get(i).getAsFloat();
                    yield vals;
                }
                case ENUM -> elem.getAsString();
                default -> elem.getAsString();
            };
        } catch (Exception e) {
            LOG.warn("[NodeIO] 值反序列化失敗 (type={}, elem={}): {}", type, elem, e.getMessage());
            return null;
        }
    }

    /**
     * ★ ICReM-7: 可插拔的類型名稱解析。
     * 先嘗試模組層註冊的解析器，再退回到 class.getSimpleName()。
     */
    private static String resolveTypeName(BRNode node) {
        for (Function<BRNode, String> resolver : typeNameResolvers) {
            String name = resolver.apply(node);
            if (name != null) return name;
        }
        return node.getClass().getSimpleName();
    }
}
