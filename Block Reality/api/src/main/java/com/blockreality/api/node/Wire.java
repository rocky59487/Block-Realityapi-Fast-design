package com.blockreality.api.node;

import java.util.UUID;

/**
 * A directed connection from an output port (source) to an input port (target).
 * Handles automatic type conversion when the two port types differ.
 */
public class Wire {
    private final String wireId;
    private final NodePort source;  // output port
    private final NodePort target;  // input port

    /**
     * Create a wire between {@code source} (output) and {@code target} (input).
     *
     * @throws IllegalArgumentException if the types are incompatible, if source
     *         is not an output port, or if target is not an input port.
     */
    public Wire(NodePort source, NodePort target) {
        if (source.isInput()) {
            throw new IllegalArgumentException("Source port must be an output port");
        }
        if (!target.isInput()) {
            throw new IllegalArgumentException("Target port must be an input port");
        }
        if (!source.getType().canConnectTo(target.getType())) {
            throw new IllegalArgumentException(
                    "Incompatible types: " + source.getType().id + " -> " + target.getType().id);
        }
        this.wireId = UUID.randomUUID().toString();
        this.source = source;
        this.target = target;
    }

    // ---- Value with auto-conversion ----

    /**
     * Pull the source port's value and convert it to the target port's type
     * when the two differ.
     */
    public Object getSourceValue() {
        Object val = source.getValue();
        PortType srcType = source.getType();
        PortType tgtType = target.getType();

        if (srcType == tgtType || val == null) {
            return val;
        }

        // FLOAT -> INT
        if (srcType == PortType.FLOAT && tgtType == PortType.INT) {
            return Math.round(toFloat(val));
        }
        // INT -> FLOAT
        if (srcType == PortType.INT && tgtType == PortType.FLOAT) {
            return (float) toInt(val);
        }
        // BOOL -> INT
        if (srcType == PortType.BOOL && tgtType == PortType.INT) {
            return toBool(val) ? 1 : 0;
        }
        // BOOL -> FLOAT
        if (srcType == PortType.BOOL && tgtType == PortType.FLOAT) {
            return toBool(val) ? 1.0f : 0.0f;
        }
        // VEC3 -> COLOR  (pack RGB float[3] 0..1 into 0xRRGGBB int)
        if (srcType == PortType.VEC3 && tgtType == PortType.COLOR) {
            float[] v = (float[]) val;
            int r = clamp8(Math.round(v[0] * 255));
            int g = clamp8(Math.round(v[1] * 255));
            int b = clamp8(Math.round(v[2] * 255));
            return (r << 16) | (g << 8) | b;
        }
        // COLOR -> VEC3  (unpack 0xRRGGBB int into float[3] 0..1)
        if (srcType == PortType.COLOR && tgtType == PortType.VEC3) {
            int c = toInt(val);
            return new float[]{
                    ((c >> 16) & 0xFF) / 255.0f,
                    ((c >> 8) & 0xFF) / 255.0f,
                    (c & 0xFF) / 255.0f
            };
        }
        // BLOCK -> MATERIAL  (pass-through; the object carries material data)
        if (srcType == PortType.BLOCK && tgtType == PortType.MATERIAL) {
            return val;
        }

        // Fallback — no conversion available
        return val;
    }

    // ---- Validation ----

    /**
     * Returns true if the source and target types are still compatible.
     */
    public boolean isValid() {
        return source.getType().canConnectTo(target.getType());
    }

    /**
     * Disconnect this wire from both its source and target ports.
     */
    public void disconnect() {
        target.disconnect();
    }

    // ---- Getters ----

    public String getWireId() {
        return wireId;
    }

    public NodePort getSource() {
        return source;
    }

    public NodePort getTarget() {
        return target;
    }

    // ---- Helpers ----

    private static float toFloat(Object v) {
        if (v instanceof Number) return ((Number) v).floatValue();
        return 0.0f;
    }

    private static int toInt(Object v) {
        if (v instanceof Number) return ((Number) v).intValue();
        return 0;
    }

    private static boolean toBool(Object v) {
        if (v instanceof Boolean) return (Boolean) v;
        if (v instanceof Number) return ((Number) v).intValue() != 0;
        return false;
    }

    private static int clamp8(int v) {
        return Math.max(0, Math.min(255, v));
    }

    @Override
    public String toString() {
        return "Wire[" + wireId.substring(0, 8) + " " + source + " -> " + target + "]";
    }
}
