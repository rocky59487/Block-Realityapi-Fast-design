package com.blockreality.api.sidecar;

import com.google.gson.Gson;
import com.google.gson.JsonObject;
import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.*;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;

/**
 * Sidecar 二進制 RPC 編解碼器 — D-4a
 *
 * 在現有 JSON-RPC 2.0 語意之上，提供 length-prefixed 二進制框架，
 * 減少 JSON 解析開銷與 UTF-8 編碼成本。
 *
 * 線路格式：
 *   [4 bytes: payload length (big-endian)] [N bytes: payload]
 *
 * Payload 格式（兩種模式）：
 *   - JSON 模式 (magic=0x00): payload = UTF-8 JSON 字串（向後相容）
 *   - MessagePack 模式 (magic=0x01): payload = msgpack(header) + RLE 壓縮體素數據
 *
 * 協議握手：
 *   啟動後 Sidecar 發送 capabilities JSON，Java 端回應選擇的編碼模式。
 *   若 Sidecar 不支援二進制，自動降級為 JSON 模式。
 *
 * 使用方式：
 *   BinaryRpcCodec codec = new BinaryRpcCodec(process.getInputStream(), process.getOutputStream());
 *   codec.writeRequest(jsonObject);
 *   JsonObject response = codec.readResponse();
 */
public class BinaryRpcCodec implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger("BR/BinaryRPC");
    private static final Gson GSON = new Gson();

    /** 魔術位元組：JSON 模式 */
    public static final byte MODE_JSON = 0x00;
    /** 魔術位元組：MessagePack 模式（預留） */
    public static final byte MODE_MSGPACK = 0x01;

    /** 最大訊息長度（16 MB — 支援大型體素快照） */
    private static final int MAX_PAYLOAD_SIZE = 16 * 1024 * 1024;

    /** Header 大小：4 bytes length + 1 byte mode */
    private static final int HEADER_SIZE = 5;

    private final DataInputStream in;
    private final DataOutputStream out;
    private final Object writeLock = new Object();

    private volatile boolean useBinaryMode = false;

    public BinaryRpcCodec(InputStream inputStream, OutputStream outputStream) {
        this.in = new DataInputStream(new BufferedInputStream(inputStream, 65536));
        this.out = new DataOutputStream(new BufferedOutputStream(outputStream, 65536));
    }

    // ═══════════════════════════════════════════════════════
    //  寫入
    // ═══════════════════════════════════════════════════════

    /**
     * 寫入 JSON-RPC 請求（length-prefixed 框架）。
     */
    public void writeRequest(JsonObject request) throws IOException {
        byte[] payload = GSON.toJson(request).getBytes(StandardCharsets.UTF_8);
        writeFrame(MODE_JSON, payload);
    }

    /**
     * 寫入原始二進制體素數據（RLE 壓縮）。
     * 用於大型結構快照傳輸。
     *
     * @param rpcHeader JSON header（method, id, params metadata）
     * @param voxelData RLE 壓縮的體素二進制數據
     */
    public void writeVoxelRequest(JsonObject rpcHeader, byte[] voxelData) throws IOException {
        byte[] headerBytes = GSON.toJson(rpcHeader).getBytes(StandardCharsets.UTF_8);

        // 組合：[4B headerLen][headerBytes][voxelData]
        ByteBuffer combined = ByteBuffer.allocate(4 + headerBytes.length + voxelData.length);
        combined.order(ByteOrder.BIG_ENDIAN);
        combined.putInt(headerBytes.length);
        combined.put(headerBytes);
        combined.put(voxelData);

        writeFrame(MODE_MSGPACK, combined.array());
    }

    private void writeFrame(byte mode, byte[] payload) throws IOException {
        if (payload.length > MAX_PAYLOAD_SIZE) {
            throw new IOException("Payload exceeds maximum size: " + payload.length + " > " + MAX_PAYLOAD_SIZE);
        }

        synchronized (writeLock) {
            out.writeInt(payload.length + 1); // +1 for mode byte
            out.writeByte(mode);
            out.write(payload);
            out.flush();
        }
    }

    // ═══════════════════════════════════════════════════════
    //  讀取
    // ═══════════════════════════════════════════════════════

    /**
     * 讀取一個完整的 RPC 回應訊息。
     *
     * @return 解析後的 JSON 回應
     */
    public JsonObject readResponse() throws IOException {
        int totalLen = in.readInt();
        if (totalLen <= 0 || totalLen > MAX_PAYLOAD_SIZE) {
            throw new IOException("Invalid frame length: " + totalLen);
        }

        byte mode = in.readByte();
        int payloadLen = totalLen - 1;
        byte[] payload = new byte[payloadLen];
        in.readFully(payload);

        if (mode == MODE_JSON) {
            String json = new String(payload, StandardCharsets.UTF_8);
            return GSON.fromJson(json, JsonObject.class);
        } else if (mode == MODE_MSGPACK) {
            // MessagePack 模式：前 4 bytes = header 長度，後面 = header JSON + binary data
            ByteBuffer buf = ByteBuffer.wrap(payload).order(ByteOrder.BIG_ENDIAN);
            int headerLen = buf.getInt();
            String headerJson = new String(payload, 4, headerLen, StandardCharsets.UTF_8);
            JsonObject response = GSON.fromJson(headerJson, JsonObject.class);
            // 二進制數據附加在 response 的 _binaryOffset/_binaryLength 標記
            response.addProperty("_binaryOffset", 4 + headerLen);
            response.addProperty("_binaryLength", payloadLen - 4 - headerLen);
            return response;
        } else {
            throw new IOException("Unknown frame mode: " + mode);
        }
    }

    /**
     * 從 MessagePack 回應中擷取二進制體素數據。
     */
    public static byte[] extractBinaryData(JsonObject response, byte[] fullPayload) {
        int offset = response.get("_binaryOffset").getAsInt();
        int length = response.get("_binaryLength").getAsInt();
        byte[] data = new byte[length];
        System.arraycopy(fullPayload, offset, data, 0, length);
        return data;
    }

    // ═══════════════════════════════════════════════════════
    //  RLE 壓縮工具（體素數據用）
    // ═══════════════════════════════════════════════════════

    /**
     * RLE 壓縮 int 陣列（體素 material ID）。
     * 格式：[count (varint)][value (int32)] 重複
     */
    public static byte[] rleEncode(int[] data) {
        if (data.length == 0) return new byte[0];

        ByteArrayOutputStream bos = new ByteArrayOutputStream(data.length);
        DataOutputStream dos = new DataOutputStream(bos);

        try {
            int i = 0;
            while (i < data.length) {
                int value = data[i];
                int count = 1;
                while (i + count < data.length && data[i + count] == value && count < 0x7FFF) {
                    count++;
                }
                // varint-style count: 1 byte if <128, 2 bytes otherwise
                if (count < 128) {
                    dos.writeByte(count);
                } else {
                    dos.writeByte((count >> 8) | 0x80);
                    dos.writeByte(count & 0xFF);
                }
                dos.writeInt(value);
                i += count;
            }
            dos.flush();
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return bos.toByteArray();
    }

    /**
     * RLE 解壓縮。
     */
    public static int[] rleDecode(byte[] encoded, int expectedLength) {
        int[] result = new int[expectedLength];
        DataInputStream dis = new DataInputStream(new ByteArrayInputStream(encoded));

        try {
            int pos = 0;
            while (pos < expectedLength) {
                int first = dis.readUnsignedByte();
                int count;
                if ((first & 0x80) != 0) {
                    int second = dis.readUnsignedByte();
                    count = ((first & 0x7F) << 8) | second;
                } else {
                    count = first;
                }
                int value = dis.readInt();
                for (int j = 0; j < count && pos < expectedLength; j++) {
                    result[pos++] = value;
                }
            }
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }

        return result;
    }

    // ═══════════════════════════════════════════════════════
    //  協議管理
    // ═══════════════════════════════════════════════════════

    /**
     * 是否使用二進制模式。
     */
    public boolean isUsingBinaryMode() {
        return useBinaryMode;
    }

    /**
     * 設定二進制模式（協議握手後呼叫）。
     */
    public void setBinaryMode(boolean enabled) {
        this.useBinaryMode = enabled;
        LOGGER.info("[BinaryRPC] Binary mode {}", enabled ? "啟用" : "停用");
    }

    @Override
    public void close() throws IOException {
        in.close();
        out.close();
    }
}
