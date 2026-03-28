package com.blockreality.api.sidecar;

import org.apache.logging.log4j.LogManager;
import org.apache.logging.log4j.Logger;

import java.io.Closeable;
import java.io.IOException;
import java.io.RandomAccessFile;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.MappedByteBuffer;
import java.nio.channels.FileChannel;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * 共享記憶體橋接器 — Java 17 MappedByteBuffer 實作
 *
 * 使用作業系統 mmap 在 Java 與 Sidecar 之間傳輸大型體素數據，
 * 避免 stdio 序列化/反序列化的開銷。
 *
 * 記憶體佈局：
 *   [Header: 64 bytes]
 *     - magic (4B): 0x42524D4D ("BRMM")
 *     - version (4B): 1
 *     - writerPid (4B): 寫入者 PID
 *     - sequenceNumber (8B): 單調遞增的序號
 *     - dataOffset (4B): 數據區偏移（固定 64）
 *     - dataLength (4B): 數據長度
 *     - flags (4B): 位元旗標（dirty, ready, error）
 *     - reserved (32B): 保留
 *   [Data: N bytes]
 *     - RLE 壓縮的體素數據 or FEM 結果矩陣
 *
 * 同步機制：
 *   - 使用 sequenceNumber 做樂觀鎖（SeqLock 語意）
 *   - 寫入者先遞增為奇數（寫入中），完成後再遞增為偶數（完成）
 *   - 讀取者比較前後值確認一致性，不一致則重試或返回 null
 */
public class SharedMemoryBridge implements Closeable {

    private static final Logger LOGGER = LogManager.getLogger("BR/SharedMem");

    /** 共享記憶體標頭魔術數 */
    private static final int MAGIC = 0x42524D4D; // "BRMM"
    private static final int VERSION = 1;

    /** 標頭大小 */
    private static final int HEADER_SIZE = 64;

    // 標頭偏移量
    private static final int OFF_MAGIC = 0;
    private static final int OFF_VERSION = 4;
    private static final int OFF_WRITER_PID = 8;
    private static final int OFF_SEQUENCE = 12; // 8 bytes (long)
    private static final int OFF_DATA_OFFSET = 20;
    private static final int OFF_DATA_LENGTH = 24;
    private static final int OFF_FLAGS = 28;
    // 32-63: reserved

    /** 旗標位元 */
    public static final int FLAG_DIRTY = 0x01;
    public static final int FLAG_READY = 0x02;
    public static final int FLAG_ERROR = 0x04;

    /** 預設共享區大小（64 MB） */
    private static final long DEFAULT_SIZE = 64L * 1024 * 1024;

    /** SeqLock 讀取最大重試次數 */
    private static final int MAX_READ_RETRIES = 5;

    private final String name;
    private final long size;
    private volatile boolean opened = false;

    // MappedByteBuffer 實作
    private Path mappedFilePath;
    private RandomAccessFile mappedFile;
    private FileChannel fileChannel;
    private MappedByteBuffer buffer;

    public SharedMemoryBridge(String name, long size) {
        this.name = name;
        this.size = size;
    }

    public SharedMemoryBridge(String name) {
        this(name, DEFAULT_SIZE);
    }

    /**
     * 開啟或建立共享記憶體區段（MappedByteBuffer 實作）。
     */
    public void open() throws IOException {
        if (opened) return;

        openMappedByteBuffer();
        opened = true;

        LOGGER.info("[SharedMem] 已開啟共享記憶體 '{}' ({}MB, Java {})",
            name, size / (1024 * 1024), Runtime.version().feature());
    }

    /**
     * 寫入體素數據到共享記憶體（SeqLock 寫入端）。
     *
     * @param data RLE 壓縮的體素數據
     * @param sequenceNumber 序號（必須為偶數，代表完成狀態）
     */
    public void writeVoxelData(byte[] data, long sequenceNumber) throws IOException {
        if (!opened) throw new IOException("SharedMemoryBridge 未開啟");
        if (data.length + HEADER_SIZE > size) {
            throw new IOException("數據超過共享記憶體大小: " +
                (data.length + HEADER_SIZE) + " > " + size);
        }

        // SeqLock 寫入協議
        // 1. 設定 sequenceNumber 為奇數（表示寫入中）
        long writeSeq = sequenceNumber | 1L;
        buffer.putLong(OFF_SEQUENCE, writeSeq);
        buffer.putInt(OFF_FLAGS, FLAG_DIRTY);

        // 2. 寫入數據描述
        buffer.putInt(OFF_DATA_OFFSET, HEADER_SIZE);
        buffer.putInt(OFF_DATA_LENGTH, data.length);

        // 3. 複製數據到數據區
        buffer.position(HEADER_SIZE);
        buffer.put(data, 0, data.length);

        // 4. 設定完成旗標 + 偶數序號
        long doneSeq = (sequenceNumber + 2) & ~1L; // 確保偶數
        buffer.putInt(OFF_FLAGS, FLAG_READY);
        buffer.putLong(OFF_SEQUENCE, doneSeq);

        // 強制刷入磁碟（確保 sidecar 可見）
        buffer.force();

        LOGGER.debug("[SharedMem] writeVoxelData: {} bytes, seq={}", data.length, doneSeq);
    }

    /**
     * 從共享記憶體讀取體素數據（SeqLock 讀取端）。
     *
     * @return RLE 壓縮的體素數據；如果資料不一致或未就緒則返回 null
     */
    public byte[] readVoxelData() throws IOException {
        if (!opened) throw new IOException("SharedMemoryBridge 未開啟");

        for (int retry = 0; retry < MAX_READ_RETRIES; retry++) {
            // 1. 讀取 sequenceNumber (seq1)
            long seq1 = buffer.getLong(OFF_SEQUENCE);

            // 如果 seq1 是奇數，表示正在寫入，跳過
            if ((seq1 & 1L) != 0) {
                Thread.onSpinWait();
                continue;
            }

            // 2. 檢查旗標
            int flags = buffer.getInt(OFF_FLAGS);
            if ((flags & FLAG_READY) == 0) {
                return null; // 沒有就緒的數據
            }
            if ((flags & FLAG_ERROR) != 0) {
                LOGGER.warn("[SharedMem] 寫入端報告錯誤");
                return null;
            }

            // 3. 讀取數據描述
            int dataOffset = buffer.getInt(OFF_DATA_OFFSET);
            int dataLength = buffer.getInt(OFF_DATA_LENGTH);

            if (dataOffset < HEADER_SIZE || dataLength <= 0 ||
                dataOffset + dataLength > size) {
                LOGGER.warn("[SharedMem] 數據描述無效: offset={}, length={}", dataOffset, dataLength);
                return null;
            }

            // 4. 讀取數據
            byte[] data = new byte[dataLength];
            buffer.position(dataOffset);
            buffer.get(data, 0, dataLength);

            // 5. 再次讀取 sequenceNumber (seq2) 驗證一致性
            long seq2 = buffer.getLong(OFF_SEQUENCE);
            if (seq1 == seq2) {
                LOGGER.debug("[SharedMem] readVoxelData: {} bytes, seq={}", dataLength, seq1);
                return data; // 一致！
            }

            // 不一致，重試
            LOGGER.debug("[SharedMem] SeqLock 不一致（seq1={}, seq2={}），重試 {}/{}",
                seq1, seq2, retry + 1, MAX_READ_RETRIES);
        }

        LOGGER.warn("[SharedMem] 讀取重試耗盡，返回 null");
        return null;
    }

    /**
     * 檢查是否有新數據可讀。
     *
     * @param lastKnownSequence 上次已知的序號
     * @return true 如果 header 中的 sequenceNumber > lastKnownSequence 且為偶數
     */
    public boolean hasNewData(long lastKnownSequence) {
        if (!opened || buffer == null) return false;
        long currentSeq = buffer.getLong(OFF_SEQUENCE);
        // 偶數 = 寫入完成，且比已知序號新
        return (currentSeq & 1L) == 0 && currentSeq > lastKnownSequence;
    }

    // ─── 內部實作 ───

    private void openMappedByteBuffer() throws IOException {
        // 使用系統暫存目錄建立共享檔案
        Path tmpDir = Path.of(System.getProperty("java.io.tmpdir"), "blockreality");
        Files.createDirectories(tmpDir);
        mappedFilePath = tmpDir.resolve("shm_" + name + ".brshm");

        mappedFile = new RandomAccessFile(mappedFilePath.toFile(), "rw");
        mappedFile.setLength(size);

        fileChannel = mappedFile.getChannel();
        buffer = fileChannel.map(FileChannel.MapMode.READ_WRITE, 0, size);
        buffer.order(ByteOrder.LITTLE_ENDIAN);

        // 初始化 header（僅首次建立時）
        if (buffer.getInt(OFF_MAGIC) != MAGIC) {
            buffer.putInt(OFF_MAGIC, MAGIC);
            buffer.putInt(OFF_VERSION, VERSION);
            buffer.putInt(OFF_WRITER_PID, (int) ProcessHandle.current().pid());
            buffer.putLong(OFF_SEQUENCE, 0L);
            buffer.putInt(OFF_DATA_OFFSET, HEADER_SIZE);
            buffer.putInt(OFF_DATA_LENGTH, 0);
            buffer.putInt(OFF_FLAGS, 0);
            buffer.force();
        }

        LOGGER.info("[SharedMem] MappedByteBuffer 開啟: {}", mappedFilePath);
    }

    @Override
    public void close() throws IOException {
        if (!opened) return;
        opened = false;

        // MappedByteBuffer 無法直接 unmap（Java 限制），
        // 但關閉 channel/file 後 GC 會清理
        if (fileChannel != null) {
            fileChannel.close();
            fileChannel = null;
        }
        if (mappedFile != null) {
            mappedFile.close();
            mappedFile = null;
        }
        buffer = null;

        LOGGER.info("[SharedMem] 已關閉共享記憶體 '{}'", name);
    }

    public boolean isOpened() { return opened; }
    public String getName() { return name; }
    public long getSize() { return size; }

    /** 取得共享檔案路徑（供 sidecar 端開啟同一檔案） */
    public Path getMappedFilePath() { return mappedFilePath; }

    /** 取得目前的 sequenceNumber */
    public long getCurrentSequence() {
        if (!opened || buffer == null) return -1;
        return buffer.getLong(OFF_SEQUENCE);
    }
}
