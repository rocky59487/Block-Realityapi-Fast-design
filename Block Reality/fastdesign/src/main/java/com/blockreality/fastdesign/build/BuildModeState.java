package com.blockreality.fastdesign.build;

import com.blockreality.api.placement.BuildMode;
import net.minecraft.core.BlockPos;

public class BuildModeState {

    private static BuildMode currentMode = BuildMode.NORMAL;
    private static BlockPos anchor = null;
    private static BlockPos mirrorAnchor = null;
    private static BlockPos previewTarget = null;

    public static BuildMode getMode() {
        return currentMode;
    }

    public static BuildMode cycleMode() {
        currentMode = currentMode.next();
        reset();
        return currentMode;
    }

    public static boolean isMultiBlockMode() {
        return currentMode != BuildMode.NORMAL;
    }

    public static boolean hasAnchor() {
        return anchor != null;
    }

    public static BlockPos getAnchor() {
        return anchor;
    }

    public static void setAnchor(BlockPos pos) {
        anchor = pos;
    }

    public static BlockPos getMirrorAnchor() {
        return mirrorAnchor;
    }

    public static void setMirrorAnchor(BlockPos pos) {
        mirrorAnchor = pos;
    }

    public static void setPreviewTarget(BlockPos pos) {
        previewTarget = pos;
    }

    public static void reset() {
        anchor = null;
        mirrorAnchor = null;
        previewTarget = null;
    }

    public static String encodePayload(BlockPos pos) {
        StringBuilder sb = new StringBuilder();
        sb.append(currentMode.name()).append(";");
        if (anchor != null) {
            sb.append(anchor.getX()).append(",").append(anchor.getY()).append(",").append(anchor.getZ());
        }
        sb.append(";");
        sb.append(pos.getX()).append(",").append(pos.getY()).append(",").append(pos.getZ());
        sb.append(";");
        if (mirrorAnchor != null) {
            sb.append(mirrorAnchor.getX()).append(",").append(mirrorAnchor.getY()).append(",").append(mirrorAnchor.getZ());
        }
        return sb.toString();
    }

    public record DecodedPayload(BuildMode mode, BlockPos pos1, BlockPos pos2, BlockPos mirror) {}

    public static DecodedPayload decodePayload(String payload) {
        BuildMode mode = BuildMode.NORMAL;
        BlockPos pos1 = null;
        BlockPos pos2 = null;
        BlockPos mirror = null;
        try {
            String[] parts = payload.split(";", -1);
            if (parts.length > 0 && !parts[0].isEmpty()) {
                mode = BuildMode.valueOf(parts[0]);
            }
            if (parts.length > 1 && !parts[1].isEmpty()) {
                String[] coords = parts[1].split(",");
                pos1 = new BlockPos(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
            }
            if (parts.length > 2 && !parts[2].isEmpty()) {
                String[] coords = parts[2].split(",");
                pos2 = new BlockPos(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
            }
            if (parts.length > 3 && !parts[3].isEmpty()) {
                String[] coords = parts[3].split(",");
                mirror = new BlockPos(Integer.parseInt(coords[0]), Integer.parseInt(coords[1]), Integer.parseInt(coords[2]));
            }
        } catch (Exception e) {
            return new DecodedPayload(BuildMode.NORMAL, null, null, null); // safe default
        }
        return new DecodedPayload(mode, pos1, pos2, mirror);
    }
}
