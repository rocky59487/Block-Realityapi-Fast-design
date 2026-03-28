package com.blockreality.api.node;

import com.blockreality.api.node.nodes.render.EffectToggleNode;
import com.blockreality.api.node.nodes.render.QualityPresetNode;
import com.google.gson.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import java.util.function.Supplier;

/**
 * 節點圖 JSON 序列化 / 反序列化。
 *
 * 格式版本 1.1 — 支援節點位置、折疊狀態、端口值、連線。
 * 內建品質預設可直接載入（Potato / Low / Medium / High / Ultra）。
 */
public final class NodeGraphIO {

    private NodeGraphIO() {}

    private static final Logger LOG = LoggerFactory.getLogger("BR-NodeIO");
    private static final Gson GSON = new GsonBuilder().setPrettyPrinting().create();

    /** Node factory registry: type name → constructor */
    private static final Map<String, Supplier<BRNode>> nodeFactories = new HashMap<>();

    static {
        // Register built-in node types
        registerNodeType("QualityPresetNode", QualityPresetNode::new);
        registerNodeType("EffectToggleNode_ssao", EffectToggleNode::createSSAO);
        registerNodeType("EffectToggleNode_ssr", EffectToggleNode::createSSR);
        registerNodeType("EffectToggleNode_taa", EffectToggleNode::createTAA);
        registerNodeType("EffectToggleNode_bloom", EffectToggleNode::createBloom);
        registerNodeType("EffectToggleNode_volumetric", EffectToggleNode::createVolumetric);
        registerNodeType("EffectToggleNode_dof", EffectToggleNode::createDOF);
        registerNodeType("EffectToggleNode_motion_blur", EffectToggleNode::createMotionBlur);
        registerNodeType("EffectToggleNode_contact_shadow", EffectToggleNode::createContactShadow);
        registerNodeType("EffectToggleNode_ssgi", EffectToggleNode::createSSGI);
        registerNodeType("EffectToggleNode_vct", EffectToggleNode::createVCT);
        registerNodeType("EffectToggleNode_sss", EffectToggleNode::createSSS);
        registerNodeType("EffectToggleNode_anisotropic", EffectToggleNode::createAnisotropic);
        registerNodeType("EffectToggleNode_pom", EffectToggleNode::createPOM);
    }

    /**
     * Register a node type for deserialization.
     *
     * @param typeName the type identifier stored in JSON
     * @param factory  supplier that creates a fresh instance of that node type
     */
    public static void registerNodeType(String typeName, Supplier<BRNode> factory) {
        nodeFactories.put(typeName, factory);
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

        // Map from serialized ID → deserialized node for wire reconnection
        // Also map new node IDs back to old IDs to reconnect wires
        Map<String, BRNode> nodeById = new HashMap<>();
        Map<String, String> oldIdToNewId = new HashMap<>();

        // Deserialize nodes
        JsonArray nodesArray = root.getAsJsonArray("nodes");
        if (nodesArray != null) {
            for (JsonElement elem : nodesArray) {
                JsonObject nodeObj = elem.getAsJsonObject();
                String typeName = nodeObj.get("type").getAsString();
                String savedId = nodeObj.get("id").getAsString();

                Supplier<BRNode> factory = nodeFactories.get(typeName);
                if (factory == null) {
                    LOG.warn("[NodeIO] 未知節點類型 '{}' (id={}), 跳過", typeName, savedId);
                    continue;
                }

                BRNode node = factory.get();
                // Note: nodeId is immutable (final) and set via UUID in constructor.
                // We track the mapping: oldId → newNodeId for wire reconnection.
                oldIdToNewId.put(savedId, node.getNodeId());

                // Restore position
                if (nodeObj.has("posX")) node.setPosX(nodeObj.get("posX").getAsFloat());
                if (nodeObj.has("posY")) node.setPosY(nodeObj.get("posY").getAsFloat());

                // Restore state
                if (nodeObj.has("enabled")) node.setEnabled(nodeObj.get("enabled").getAsBoolean());
                if (nodeObj.has("collapsed")) node.setCollapsed(nodeObj.get("collapsed").getAsBoolean());

                // Restore input port values
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

                // Map saved IDs to new node IDs (since nodes get new UUIDs on deserialization)
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

    /**
     * Save a node graph to a JSON file.
     */
    public static void saveToFile(NodeGraph graph, Path path) throws IOException {
        String json = serialize(graph);
        Files.createDirectories(path.getParent());
        Files.writeString(path, json, StandardCharsets.UTF_8);
        LOG.info("[NodeIO] 節點圖儲存至: {}", path);
    }

    /**
     * Load a node graph from a JSON file.
     */
    public static NodeGraph loadFromFile(Path path) throws IOException {
        if (!Files.exists(path)) {
            throw new IOException("節點圖檔案不存在: " + path);
        }
        String json = Files.readString(path, StandardCharsets.UTF_8);
        LOG.info("[NodeIO] 從檔案載入節點圖: {}", path);
        return deserialize(json);
    }

    /**
     * Load a preset from embedded resources.
     *
     * @param presetName one of "potato", "low", "medium", "high", "ultra"
     * @return the preset node graph, or a default High preset if not found
     */
    public static NodeGraph loadPreset(String presetName) {
        return switch (presetName.toLowerCase()) {
            case "potato" -> createPotatoPreset();
            case "low" -> createLowPreset();
            case "medium" -> createMediumPreset();
            case "high" -> createHighPreset();
            case "ultra" -> createUltraPreset();
            default -> {
                LOG.warn("[NodeIO] 未知預設名稱 '{}', 使用 High", presetName);
                yield createHighPreset();
            }
        };
    }

    // ═══════════════════════════════════════════════════════
    //  內建品質預設
    // ═══════════════════════════════════════════════════════

    /** Create a Potato quality preset — minimal effects for lowest-end hardware. */
    public static NodeGraph createPotatoPreset() {
        NodeGraph g = new NodeGraph("Potato Quality");
        QualityPresetNode qp = new QualityPresetNode();
        qp.getInput("preset").setValue(0);
        g.addNode(qp);
        qp.evaluate();
        return g;
    }

    /** Create a Low quality preset — basic effects for low-end hardware. */
    public static NodeGraph createLowPreset() {
        NodeGraph g = new NodeGraph("Low Quality");
        QualityPresetNode qp = new QualityPresetNode();
        qp.getInput("preset").setValue(1);
        g.addNode(qp);
        qp.evaluate();
        return g;
    }

    /** Create a Medium quality preset — balanced quality and performance. */
    public static NodeGraph createMediumPreset() {
        NodeGraph g = new NodeGraph("Medium Quality");
        QualityPresetNode qp = new QualityPresetNode();
        qp.getInput("preset").setValue(2);
        g.addNode(qp);
        qp.evaluate();
        return g;
    }

    /** Create a High quality preset — high quality, recommended for most users. */
    public static NodeGraph createHighPreset() {
        NodeGraph g = new NodeGraph("High Quality");
        QualityPresetNode qp = new QualityPresetNode();
        qp.getInput("preset").setValue(3);
        g.addNode(qp);
        qp.evaluate();
        return g;
    }

    /** Create an Ultra quality preset — maximum quality, all effects enabled. */
    public static NodeGraph createUltraPreset() {
        NodeGraph g = new NodeGraph("Ultra Quality");
        QualityPresetNode qp = new QualityPresetNode();
        qp.getInput("preset").setValue(4);
        g.addNode(qp);
        qp.evaluate();
        return g;
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
     * Resolve the serialization type name for a node.
     * EffectToggleNode instances include their effect name suffix.
     */
    private static String resolveTypeName(BRNode node) {
        if (node instanceof EffectToggleNode effectNode) {
            return "EffectToggleNode_" + effectNode.getEffectName();
        }
        return node.getClass().getSimpleName();
    }
}
