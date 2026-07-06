package fan.summer.zhiflow.ai.model;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.IOException;
import java.nio.BufferUnderflowException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.channels.FileChannel;
import java.nio.charset.StandardCharsets;
import java.nio.file.Path;
import java.nio.file.StandardOpenOption;
import java.util.*;

/**
 * Reads GGUF model files (llama.cpp format) into memory.
 * Uses memory-mapped I/O for zero-copy tensor access.
 *
 * <p>A GGUF file is untrusted, user-supplied input (frequently a partially
 * downloaded or truncated file). Every length field read from the file is
 * validated before any allocation, and stream truncation is converted to a
 * clean {@link IOException}, so a malformed file can never destabilise the host
 * process with {@link NegativeArraySizeException}, {@link OutOfMemoryError},
 * or {@link ArithmeticException}.
 */
public class GGUFReader {

    private static final Logger log = LoggerFactory.getLogger(GGUFReader.class);
    private static final int GGUF_MAGIC = 0x46554747; // "GGUF" in little-endian

    public static GGUFModel load(String modelPath) throws IOException {
        return load(Path.of(modelPath));
    }

    /**
     * Read only the GGUF header + scalar/string metadata, skipping all array values
     * (vocab, scores, etc.) and tensor info. Useful when the caller only needs a
     * handful of small metadata entries — e.g. the native backend reading the
     * chat template without paying for full vocab parsing.
     */
    public static Map<String, Object> loadMetadata(Path path) throws IOException {
        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 32) throw new IOException("File too small to be a GGUF model");

            var buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            try {
                int magic = buffer.getInt();
                if (magic != GGUF_MAGIC) {
                    throw new IOException("Not a GGUF file (magic: 0x" + Integer.toHexString(magic) + ")");
                }
                int version = buffer.getInt();
                if (version < 2) throw new IOException("Unsupported GGUF version: " + version);
                buffer.getLong(); // tensorCount, unused
                long metaCount = buffer.getLong();

                return parseMetadataLite(buffer, metaCount);
            } catch (BufferUnderflowException e) {
                throw new IOException("Malformed or truncated GGUF file: stream ended unexpectedly", e);
            }
        }
    }

    private static Map<String, Object> parseMetadataLite(ByteBuffer buf, long count) throws IOException {
        var meta = new LinkedHashMap<String, Object>();
        for (long i = 0; i < count; i++) {
            String key = readString(buf);
            int typeId = buf.getInt();
            var type = ValueType.fromId(typeId);
            if (type == ValueType.ARRAY) {
                skipArray(buf);
            } else {
                meta.put(key, readValue(buf, type));
            }
        }
        return meta;
    }

    private static void skipArray(ByteBuffer buf) throws IOException {
        int elemTypeId = buf.getInt();
        long len = buf.getLong();
        if (len < 0) {
            throw new IOException("Malformed GGUF: negative array length " + len);
        }
        var elemType = ValueType.fromId(elemTypeId);
        for (long i = 0; i < len; i++) skipValue(buf, elemType);
    }

    private static void skipValue(ByteBuffer buf, ValueType type) throws IOException {
        switch (type) {
            case UINT8, INT8, BOOL -> buf.get();
            case UINT16, INT16     -> buf.getShort();
            case UINT32, INT32, FLOAT32 -> buf.getInt();
            case UINT64, INT64, FLOAT64 -> buf.getLong();
            case STRING -> {
                long len = buf.getLong();
                if (len < 0 || len > buf.remaining()) {
                    throw new IOException("Malformed GGUF: string length " + len
                            + " is negative or exceeds remaining " + buf.remaining() + " bytes");
                }
                buf.position(buf.position() + (int) len);
            }
            case ARRAY -> skipArray(buf);
        }
    }

    public static GGUFModel load(Path path) throws IOException {
        long t0 = System.nanoTime();
        log.info("Loading GGUF model: {}", path.toAbsolutePath());

        try (var channel = FileChannel.open(path, StandardOpenOption.READ)) {
            long fileSize = channel.size();
            if (fileSize < 32) throw new IOException("File too small to be a GGUF model");

            var buffer = channel.map(FileChannel.MapMode.READ_ONLY, 0, fileSize);
            buffer.order(ByteOrder.LITTLE_ENDIAN);

            try {
                // 1. Parse header
                int magic = buffer.getInt();
                if (magic != GGUF_MAGIC) {
                    throw new IOException("Not a GGUF file (magic: 0x" + Integer.toHexString(magic) + ")");
                }
                int version = buffer.getInt();
                if (version < 2) throw new IOException("Unsupported GGUF version: " + version);
                long tensorCount = buffer.getLong();
                long metaCount = buffer.getLong();

                log.debug("GGUF version={}, tensors={}, metadata={}", version, tensorCount, metaCount);

                // 2. Parse metadata
                Map<String, Object> metadata = parseMetadata(buffer, metaCount);

                // 3. Parse tensor infos
                List<TensorInfo> tensorInfos = parseTensorInfos(buffer, tensorCount);

                // 4. Align to data section
                long alignment = (long) metadata.getOrDefault("general.alignment", 32L);
                if (alignment <= 0) {
                    throw new IOException("Malformed GGUF: invalid alignment " + alignment);
                }
                long dataOffset = alignUp(buffer.position(), alignment);

                long loadMs = (System.nanoTime() - t0) / 1_000_000;
                log.info("GGUF model loaded in {}ms, {} tensors, data at offset {}",
                         loadMs, tensorInfos.size(), dataOffset);

                // Log tensor type distribution for debugging
                var typeCounts = new java.util.TreeMap<String, Integer>();
                for (var ti : tensorInfos) {
                    typeCounts.merge(ti.type.name(), 1, Integer::sum);
                }
                log.info("Tensor types: {}", typeCounts);

                return new GGUFModel(metadata, tensorInfos, buffer, dataOffset, path.getFileName().toString());
            } catch (BufferUnderflowException e) {
                throw new IOException("Malformed or truncated GGUF file: stream ended unexpectedly", e);
            }
        }
    }

    // ── Metadata parsing ──────────────────────────────────────

    private static Map<String, Object> parseMetadata(ByteBuffer buf, long count) throws IOException {
        var meta = new LinkedHashMap<String, Object>();
        for (long i = 0; i < count; i++) {
            String key = readString(buf);
            int typeId = buf.getInt();
            var type = ValueType.fromId(typeId);
            Object value = readValue(buf, type);
            meta.put(key, value);
        }
        return meta;
    }

    private enum ValueType {
        UINT8(0), INT8(1), UINT16(2), INT16(3),
        UINT32(4), INT32(5), FLOAT32(6), BOOL(7),
        STRING(8), ARRAY(9), UINT64(10), INT64(11), FLOAT64(12);

        final int id;
        ValueType(int id) { this.id = id; }

        static ValueType fromId(int id) {
            for (ValueType t : values()) if (t.id == id) return t;
            throw new IllegalArgumentException("Unknown GGUF value type: " + id);
        }
    }

    private static Object readValue(ByteBuffer buf, ValueType type) throws IOException {
        return switch (type) {
            case UINT8   -> Byte.toUnsignedInt(buf.get());
            case INT8    -> (int) buf.get();
            case UINT16  -> Short.toUnsignedInt(buf.getShort());
            case INT16   -> (int) buf.getShort();
            case UINT32  -> Integer.toUnsignedLong(buf.getInt());
            case INT32   -> buf.getInt();
            case FLOAT32 -> buf.getFloat();
            case BOOL    -> buf.get() != 0;
            case STRING  -> readString(buf);
            case ARRAY   -> readArray(buf);
            case UINT64  -> buf.getLong();
            case INT64   -> buf.getLong();
            case FLOAT64 -> buf.getDouble();
        };
    }

    private static Object readArray(ByteBuffer buf) throws IOException {
        int elemTypeId = buf.getInt();
        long len = buf.getLong();
        if (len < 0) {
            throw new IOException("Malformed GGUF: negative array length " + len);
        }
        var elemType = ValueType.fromId(elemTypeId);
        var list = new ArrayList<>(len > 1000 ? 1000 : (int) Math.min(len, Integer.MAX_VALUE));
        for (long i = 0; i < len; i++) {
            list.add(readValue(buf, elemType));
        }
        // Return typed arrays for common types for efficiency
        return switch (elemType) {
            case STRING  -> list.toArray(String[]::new);
            case FLOAT32 -> {
                float[] arr = new float[(int) len];
                for (int i = 0; i < (int) len; i++) arr[i] = (Float) list.get(i);
                yield arr;
            }
            case INT32   -> {
                int[] arr = new int[(int) len];
                for (int i = 0; i < (int) len; i++) arr[i] = (Integer) list.get(i);
                yield arr;
            }
            default -> list;
        };
    }

    private static String readString(ByteBuffer buf) throws IOException {
        long len = buf.getLong();
        if (len < 0 || len > buf.remaining()) {
            throw new IOException("Malformed GGUF: string length " + len
                    + " is negative or exceeds remaining " + buf.remaining() + " bytes");
        }
        byte[] bytes = new byte[(int) len];
        buf.get(bytes);
        return new String(bytes, StandardCharsets.UTF_8);
    }

    // ── Tensor info parsing ───────────────────────────────────

    private static List<TensorInfo> parseTensorInfos(ByteBuffer buf, long count) throws IOException {
        int capacity = (int) Math.min(Math.max(count, 0L), Integer.MAX_VALUE);
        var infos = new ArrayList<TensorInfo>(capacity);
        for (long i = 0; i < count; i++) {
            String name = readString(buf);
            int nDims = buf.getInt();
            var dims = new ArrayList<Long>(nDims);
            for (int d = 0; d < nDims; d++) dims.add(buf.getLong());
            int typeId = buf.getInt();
            long offset = buf.getLong();
            infos.add(new TensorInfo(name, dims, GGMLType.fromId(typeId), offset));
        }
        return infos;
    }

    // ── Alignment ─────────────────────────────────────────────

    private static long alignUp(long offset, long alignment) {
        return ((offset + alignment - 1) / alignment) * alignment;
    }
}
