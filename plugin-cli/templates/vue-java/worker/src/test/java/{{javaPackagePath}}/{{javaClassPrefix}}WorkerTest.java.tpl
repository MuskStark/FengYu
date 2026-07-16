package {{javaPackage}};

import fan.summer.fengyu.sdk.JsonRpcWorker;
import org.junit.jupiter.api.Test;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;

import static org.junit.jupiter.api.Assertions.assertTrue;

/** Verifies the worker answers the `hello` JSON-RPC method. */
class {{javaClassPrefix}}WorkerTest {
    @Test void helloGreetsByName() throws Exception {
        String request = "{\"jsonrpc\":\"2.0\",\"id\":\"1\",\"method\":\"hello\",\"params\":{\"name\":\"Ada\"}}\n";
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        // The worker main registers `hello`; here we reproduce its single handler.
        new JsonRpcWorker()
            .on("hello", params -> java.util.Map.of("message", "Hello, " + JsonRpcWorker.string(params, "name")))
            .run(new ByteArrayInputStream(request.getBytes(StandardCharsets.UTF_8)), out);
        assertTrue(out.toString(StandardCharsets.UTF_8).contains("Hello, Ada"));
    }
}
