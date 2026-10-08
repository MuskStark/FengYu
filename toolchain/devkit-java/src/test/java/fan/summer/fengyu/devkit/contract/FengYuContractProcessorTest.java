package fan.summer.fengyu.devkit.contract;

import com.google.gson.JsonArray;
import com.google.gson.JsonObject;
import com.google.gson.JsonParser;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import javax.tools.Diagnostic;
import javax.tools.DiagnosticCollector;
import javax.tools.JavaCompiler;
import javax.tools.JavaFileObject;
import javax.tools.StandardJavaFileManager;
import javax.tools.StandardLocation;
import javax.tools.ToolProvider;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Golden-fixture coverage for the code-first contract extractor: runs the REAL
 * {@link FengYuContractProcessor} through {@code javax.tools.JavaCompiler} (proc-only, explicit
 * processor) against a representative {@code @FengYuContract} interface and asserts the emitted
 * {@code fengyu-contract/contract.json} IR. Error cases (generic record components, invalid enum
 * defaults) must surface as clean compile diagnostics — a ClassCastException used to crash javac
 * instead.
 */
class FengYuContractProcessorTest {

    @TempDir Path work;

    private static final String HAPPY_SOURCE = """
            package demo;

            import fan.summer.fengyu.sdk.RpcContext;
            import fan.summer.fengyu.sdk.contract.FengYuAiTool;
            import fan.summer.fengyu.sdk.contract.FengYuContract;
            import fan.summer.fengyu.sdk.contract.FengYuField;
            import fan.summer.fengyu.sdk.contract.FengYuRpc;
            import fan.summer.fengyu.sdk.contract.FengYuSensitive;

            @FengYuContract
            public interface DemoContract {

                @FengYuRpc(name = "analyze", description = "Analyze a workbook", timeoutSeconds = 120)
                @FengYuAiTool(description = "Analyze the given workbook", effect = FengYuAiTool.ToolEffect.READ)
                AnalysisOutput analyze(AnalyzeInput input, RpcContext ctx);

                @FengYuRpc(description = "Health probe")
                void ping();
            }

            record AnalyzeInput(
                    @FengYuField(title = "Source", description = "The file to analyze", required = true) String file,
                    @FengYuField(format = "fengyu-file", fileAccess = "read") String attachment,
                    int sheetCount,
                    @FengYuSensitive String password,
                    Mode mode,
                    Nested options) {}

            enum Mode { FAST, THOROUGH }

            record Nested(@FengYuField(defaultValue = "3", minimum = 0) long retries) {}

            record AnalysisOutput(int sheets, String summary) {}
            """;

    /** A generic record component (T from record GenericInput&lt;T&gt;) has no schema mapping. */
    private static final String GENERIC_SOURCE = """
            package demo;

            import fan.summer.fengyu.sdk.contract.FengYuContract;
            import fan.summer.fengyu.sdk.contract.FengYuRpc;

            @FengYuContract
            public interface BadContract {
                @FengYuRpc
                DemoOutput run(GenericInput input);
            }

            record GenericInput<T>(T value) {}

            record DemoOutput(int n) {}
            """;

    /** An enum default must name one of the enum's own constants. */
    private static final String BAD_ENUM_DEFAULT_SOURCE = """
            package demo;

            import fan.summer.fengyu.sdk.contract.FengYuContract;
            import fan.summer.fengyu.sdk.contract.FengYuField;
            import fan.summer.fengyu.sdk.contract.FengYuRpc;

            @FengYuContract
            public interface BadDefaultContract {
                @FengYuRpc
                DemoOutput run(WithDefault input);
            }

            record WithDefault(@FengYuField(defaultValue = "TURBO") Mode mode) {}

            enum Mode { FAST, THOROUGH }

            record DemoOutput(int n) {}
            """;

    record CompileResult(boolean success, List<Diagnostic<? extends JavaFileObject>> diagnostics) {
        String messages() {
            StringBuilder sb = new StringBuilder();
            for (Diagnostic<? extends JavaFileObject> d : diagnostics) {
                sb.append(d.getKind()).append(": ").append(d.getMessage(Locale.ROOT)).append('\n');
            }
            return sb.toString();
        }
    }

    private CompileResult compile(String source, String fileName) throws IOException {
        Path sourceFile = work.resolve(fileName);
        Files.writeString(sourceFile, source, StandardCharsets.UTF_8);
        JavaCompiler compiler = ToolProvider.getSystemJavaCompiler();
        DiagnosticCollector<JavaFileObject> diagnostics = new DiagnosticCollector<>();
        boolean success;
        try (StandardJavaFileManager fileManager =
                compiler.getStandardFileManager(diagnostics, Locale.ROOT, StandardCharsets.UTF_8)) {
            fileManager.setLocation(StandardLocation.CLASS_OUTPUT, List.of(work.toFile()));
            JavaCompiler.CompilationTask task = compiler.getTask(
                null, fileManager, diagnostics,
                List.of("-proc:only",
                    "-encoding", StandardCharsets.UTF_8.name(),
                    "-processor", FengYuContractProcessor.class.getName(),
                    "-classpath", System.getProperty("java.class.path")),
                null,
                fileManager.getJavaFileObjects(sourceFile.toFile()));
            success = task.call();
        }
        return new CompileResult(success, diagnostics.getDiagnostics());
    }

    @Test void emitsTheGoldenContractIr() throws IOException {
        CompileResult result = compile(HAPPY_SOURCE, "DemoContract.java");
        assertTrue(result.success(), "the representative contract must compile cleanly:\n" + result.messages());

        Path irFile = work.resolve("fengyu-contract/contract.json");
        assertTrue(Files.exists(irFile), "the processor must write the IR to CLASS_OUTPUT");
        JsonObject ir = JsonParser.parseString(Files.readString(irFile, StandardCharsets.UTF_8))
            .getAsJsonObject();

        assertEquals(FengYuContractProcessor.IR_FORMAT_VERSION, ir.get("formatVersion").getAsInt());
        JsonObject methods = ir.getAsJsonObject("rpc").getAsJsonObject("methods");
        assertEquals(2, methods.size(), "analyze + ping: " + methods.keySet());

        JsonObject analyze = methods.getAsJsonObject("analyze");
        assertEquals("Analyze a workbook", analyze.get("description").getAsString());
        assertEquals(120, analyze.get("timeoutSeconds").getAsInt());

        JsonObject input = analyze.getAsJsonObject("inputSchema");
        assertEquals("object", input.get("type").getAsString());
        JsonObject props = input.getAsJsonObject("properties");
        assertEquals("Source", props.getAsJsonObject("file").get("title").getAsString());
        assertEquals("The file to analyze", props.getAsJsonObject("file").get("description").getAsString());
        // Primitives default to required; @FengYuField(required) forces it; optional refs stay out.
        String required = input.get("required").toString();
        assertTrue(required.contains("file") && required.contains("sheetCount"),
            "required carries the primitive and the forced field: " + required);
        assertFalse(required.contains("password"), "optional sensitive field is not required: " + required);
        assertTrue(props.getAsJsonObject("password").get("x-fengyu-sensitive").getAsBoolean());
        assertEquals("fengyu-file", props.getAsJsonObject("attachment").get("format").getAsString());
        assertEquals("read", props.getAsJsonObject("attachment").get("x-fengyu-file-access").getAsString());
        assertEquals("[\"FAST\",\"THOROUGH\"]", props.getAsJsonObject("mode").get("enum").getAsJsonArray().toString());
        JsonObject nested = props.getAsJsonObject("options");
        assertEquals("object", nested.get("type").getAsString());
        JsonObject retries = nested.getAsJsonObject("properties").getAsJsonObject("retries");
        assertEquals(3, retries.get("default").getAsInt(), "defaultValue '3' converts to the long 3");
        assertEquals(0, retries.get("minimum").getAsInt(), "whole doubles print without a trailing .0");

        JsonObject output = analyze.getAsJsonObject("outputSchema");
        assertTrue(output.getAsJsonObject("properties").has("sheets"));
        assertTrue(output.getAsJsonObject("properties").has("summary"));

        JsonArray tools = ir.getAsJsonArray("aiTools");
        assertEquals(1, tools.size());
        JsonObject tool = tools.get(0).getAsJsonObject();
        assertEquals("analyze", tool.get("name").getAsString());
        assertEquals("analyze", tool.get("method").getAsString());
        assertEquals("read", tool.get("effect").getAsString());

        JsonObject ping = methods.getAsJsonObject("ping");
        assertEquals("object", ping.getAsJsonObject("inputSchema").get("type").getAsString());
        assertEquals(0, ping.getAsJsonObject("inputSchema").getAsJsonObject("properties").size(),
            "a no-parameter method round-trips the empty properties shape");
        assertFalse(ping.has("outputSchema"), "a void method emits no output schema");

        assertTrue(ir.getAsJsonObject("origins").has("rpc.methods.analyze"),
            "source origins are recorded for the CLI cross-check");
    }

    @Test void genericRecordComponentIsACleanDiagnosticNotAJavacCrash() throws IOException {
        CompileResult result = compile(GENERIC_SOURCE, "BadContract.java");
        assertFalse(result.success(), "a generic record component must fail the build");
        assertTrue(result.messages().contains("unsupported type"),
            "the failure must be the intended diagnostic, not a ClassCastException:\n" + result.messages());
    }

    @Test void enumDefaultMustNameAConstantOfTheEnum() throws IOException {
        CompileResult result = compile(BAD_ENUM_DEFAULT_SOURCE, "BadDefaultContract.java");
        assertFalse(result.success(), "a typo'd enum default must fail the build");
        assertTrue(result.messages().contains("not one of the enum constants"),
            "the failure must name the violated constraint:\n" + result.messages());
    }
}
