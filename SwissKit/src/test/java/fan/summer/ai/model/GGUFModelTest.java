package fan.summer.ai.model;

import org.junit.jupiter.api.Test;

import java.nio.ByteBuffer;

import static org.junit.jupiter.api.Assertions.assertDoesNotThrow;

/**
 * Unit tests for {@link GGUFModel#unmapBuffer} (v3.0.1 stability work).
 *
 * <p>{@link GGUFModel} now implements {@link AutoCloseable} and best-effort
 * releases its mapped weight buffer via {@code sun.misc.Unsafe.invokeCleaner}.
 * The release must <strong>never throw</strong>: if Unsafe is unreachable it must
 * degrade silently to ordinary GC. These tests pin that no-throw contract across
 * buffer kinds (null, heap, direct) and on a double-release.
 */
class GGUFModelTest {

    @Test
    void unmapBuffer_null_isSafe() {
        assertDoesNotThrow(() -> GGUFModel.unmapBuffer(null));
    }

    @Test
    void unmapBuffer_heapBuffer_isSafeNoOp() {
        ByteBuffer heap = ByteBuffer.allocate(32);
        assertDoesNotThrow(() -> GGUFModel.unmapBuffer(heap));
    }

    @Test
    void unmapBuffer_directBuffer_isSafe() {
        ByteBuffer direct = ByteBuffer.allocateDirect(32);
        assertDoesNotThrow(() -> GGUFModel.unmapBuffer(direct));
    }

    @Test
    void unmapBuffer_directBufferTwice_isSafe() {
        ByteBuffer direct = ByteBuffer.allocateDirect(32);
        GGUFModel.unmapBuffer(direct);
        // Second release of an already-cleaned buffer must not throw.
        assertDoesNotThrow(() -> GGUFModel.unmapBuffer(direct));
    }
}
