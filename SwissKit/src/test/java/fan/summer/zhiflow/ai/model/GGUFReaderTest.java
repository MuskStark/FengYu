package fan.summer.zhiflow.ai.model;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import java.io.IOException;
import java.nio.ByteBuffer;
import java.nio.ByteOrder;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertThrows;

/**
 * Regression tests for {@link GGUFReader} hardening (v3.0.1 stability work).
 *
 * <p>A GGUF model file is untrusted, user-supplied input (often a partially
 * downloaded or corrupt file). Previously the parser trusted length fields read
 * straight from the file header, so a malformed file could trigger
 * {@link NegativeArraySizeException}, {@link OutOfMemoryError}, or
 * {@link ArithmeticException} inside the host process — including the native
 * backend, which reads chat-template metadata in-process. These tests pin the
 * contract that every malformation surfaces as a clean {@link IOException}.
 */
class GGUFReaderTest {

    private static final int GGUF_MAGIC = 0x46554747; // "GGUF" little-endian

    /** Builds a GGUF header (magic + version=3 + tensorCount + metaCount); position ends at 24. */
    private static ByteBuffer header(int tensorCount, long metaCount) {
        ByteBuffer b = ByteBuffer.allocate(1 << 16).order(ByteOrder.LITTLE_ENDIAN);
        b.putInt(GGUF_MAGIC);
        b.putInt(3);                 // version
        b.putLong(tensorCount);
        b.putLong(metaCount);
        return b;
    }

    private static void putString(ByteBuffer b, String s) {
        byte[] bytes = s.getBytes(StandardCharsets.UTF_8);
        b.putLong(bytes.length);
        b.put(bytes);
    }

    /** Extracts the bytes written so far and writes them to a temp file. */
    private static Path write(ByteBuffer b, Path dir, String name) throws IOException {
        byte[] out = new byte[b.position()];
        b.rewind();
        b.get(out);
        Path file = dir.resolve(name);
        Files.write(file, out);
        return file;
    }

    // ── readString: negative length ─────────────────────────────
    @Test
    void loadMetadata_negativeStringLength_throwsIOException(@TempDir Path dir) throws Exception {
        ByteBuffer b = header(0, 1);
        b.putLong(-1L);   // corrupt key-string length
        Path file = write(b, dir, "neg-len.gguf");
        assertThrows(IOException.class, () -> GGUFReader.loadMetadata(file));
    }

    // ── readString: length exceeds remaining bytes ──────────────
    @Test
    void loadMetadata_stringLengthExceedsRemaining_throwsIOException(@TempDir Path dir) throws Exception {
        ByteBuffer b = header(0, 1);
        b.putLong(50L);   // declares a 50-byte string but the file ends right after
        b.put((byte) 'x');
        Path file = write(b, dir, "oversized-len.gguf");
        assertThrows(IOException.class, () -> GGUFReader.loadMetadata(file));
    }

    // ── readArray: negative element count ───────────────────────
    @Test
    void loadMetadata_arrayNegativeLength_throwsIOException(@TempDir Path dir) throws Exception {
        ByteBuffer b = header(0, 1);
        putString(b, "x");   // metadata key
        b.putInt(9);         // value type = ARRAY
        b.putInt(8);         // array element type = STRING
        b.putLong(-1L);      // corrupt array length
        Path file = write(b, dir, "neg-arr.gguf");
        assertThrows(IOException.class, () -> GGUFReader.loadMetadata(file));
    }

    // ── truncated mid-parse (field boundary underflow) ──────────
    @Test
    void loadMetadata_truncatedMidEntry_throwsIOException(@TempDir Path dir) throws Exception {
        // 24-byte header + 8 bytes (an empty key string), then the file ends before the type id.
        ByteBuffer b = header(0, 1);
        b.putLong(0L);       // empty key string
        Path file = write(b, dir, "truncated.gguf");
        assertThrows(IOException.class, () -> GGUFReader.loadMetadata(file));
    }

    // ── alignUp: alignment == 0 (division by zero) ──────────────
    @Test
    void load_alignmentZero_throwsIOException(@TempDir Path dir) throws Exception {
        ByteBuffer b = header(0, 1);           // tensorCount = 0
        putString(b, "general.alignment");
        b.putInt(4);   // value type = UINT32
        b.putInt(0);   // value = 0  → would divide by zero in alignUp
        Path file = write(b, dir, "zero-align.gguf");
        assertThrows(IOException.class, () -> GGUFReader.load(file));
    }
}
