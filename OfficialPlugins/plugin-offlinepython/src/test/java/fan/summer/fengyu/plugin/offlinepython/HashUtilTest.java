package fan.summer.fengyu.plugin.offlinepython;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import fan.summer.fengyu.plugin.offlinepython.infra.HashUtil;

import java.nio.file.Path;

import static org.junit.jupiter.api.Assertions.assertEquals;

class HashUtilTest {

    @Test
    void sha256OfBytesMatchesKnownVector() {
        // SHA256("abc") == ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad
        assertEquals(
            "ba7816bf8f01cfea414140de5dae2223b00361a396177a9cb410ff61f20015ad",
            HashUtil.sha256Hex("abc".getBytes()));
    }

    @Test
    void sha256OfFileMatchesBytes(@TempDir Path tmp) throws Exception {
        Path f = tmp.resolve("data.bin");
        java.nio.file.Files.writeString(f, "hello world");
        String fileHash = HashUtil.sha256Hex(f);
        String bytesHash = HashUtil.sha256Hex("hello world".getBytes());
        assertEquals(bytesHash, fileHash);
    }
}
