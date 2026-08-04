package fan.summer.fengyu.plugin.store;

import java.io.IOException;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;

/**
 * Bounded HTTP body reader used by catalog adapters. Catalog JSON is fetched from third-party
 * sources and must never be buffered into memory unbounded — a malicious or broken catalog URL
 * could otherwise OOM the backend. {@link #readAtMost(InputStream, int)} reads at most {@code max}
 * bytes and throws if the stream has more, so the caller can safely decode the result.
 *
 * @since 4.0.0
 */
final class BoundedHttp {

    /** Hard cap for a catalog response (16 MiB — comfortably above any real marketplace JSON). */
    static final int MAX_CATALOG_BYTES = 16 * 1024 * 1024;

    private BoundedHttp() {}

    /**
     * Reads up to {@code max} bytes from {@code in} as UTF-8, then ensures the stream is exhausted
     * within the cap. Throws {@link IOException} if the stream exceeds {@code max} bytes.
     */
    static String readAtMost(InputStream in, int max) throws IOException {
        byte[] buf = new byte[Math.min(8192, max + 1)];
        byte[] out = new byte[Math.min(max, 8192)];
        int total = 0;
        int n;
        while ((n = in.read(buf)) != -1) {
            if (total + n > max) {
                throw new IOException("Response body exceeds the " + max + "-byte limit");
            }
            if (total + n > out.length) {
                byte[] grown = new byte[Math.min(max, out.length * 2)];
                System.arraycopy(out, 0, grown, 0, total);
                out = grown;
            }
            System.arraycopy(buf, 0, out, total, n);
            total += n;
        }
        return new String(out, 0, total, StandardCharsets.UTF_8);
    }
}
