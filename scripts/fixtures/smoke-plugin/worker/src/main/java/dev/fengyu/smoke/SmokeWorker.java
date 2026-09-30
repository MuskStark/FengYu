package dev.fengyu.smoke;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;

import java.io.BufferedReader;
import java.io.FileDescriptor;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.InputStreamReader;
import java.io.PrintStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;

/**
 * Zero-SDK JSON-RPC 2.0 stdio worker for the e2e smoke fixture. Implements the
 * protocol-v1 handshake ({@code $/fengyu/initialize} must answer with the protocol
 * version and runtime) plus the fixture's four methods. Stdout carries ONLY JSON-RPC
 * response frames — anything else would desynchronize the host's line reader.
 */
public final class SmokeWorker {

    private static final ObjectMapper JSON = new ObjectMapper();

    public static void main(String[] args) throws IOException {
        BufferedReader in = new BufferedReader(new InputStreamReader(System.in, StandardCharsets.UTF_8));
        PrintStream out = new PrintStream(new FileOutputStream(FileDescriptor.out), false, StandardCharsets.UTF_8);
        String line;
        while ((line = in.readLine()) != null) {
            if (line.isBlank()) {
                continue;
            }
            JsonNode request;
            try {
                request = JSON.readTree(line);
            } catch (IOException malformed) {
                continue;
            }
            JsonNode id = request.get("id");
            String method = request.path("method").asText("");
            if (id == null || method.isEmpty()) {
                continue; // notifications ($/cancelRequest, $/fengyu/logging/setLevel) need no reply
            }
            ObjectNode response = JSON.createObjectNode();
            response.set("id", id);
            try {
                response.set("result", dispatch(method, request.path("params")));
            } catch (Exception failure) {
                ObjectNode error = JSON.createObjectNode();
                error.put("code", -32000);
                error.put("message", String.valueOf(failure.getMessage()));
                response.set("error", error);
                response.remove("result");
            }
            out.println(JSON.writeValueAsString(response));
            out.flush();
        }
    }

    private static JsonNode dispatch(String method, JsonNode params) throws IOException {
        switch (method) {
            case "$/fengyu/initialize" -> {
                ObjectNode handshake = JSON.createObjectNode();
                handshake.put("protocolVersion", 1);
                handshake.put("runtime", "java");
                return handshake;
            }
            case "$/fengyu/logging/setLevel" -> {
                return JSON.createObjectNode();
            }
            case "echo" -> {
                ObjectNode result = JSON.createObjectNode();
                result.put("text", params.path("text").asText(""));
                return result;
            }
            case "file_read" -> {
                Path file = resolve(params);
                ObjectNode result = JSON.createObjectNode();
                result.put("content", Files.readString(file, StandardCharsets.UTF_8));
                return result;
            }
            case "file_write" -> {
                Path file = resolve(params);
                byte[] bytes = params.path("content").asText("").getBytes(StandardCharsets.UTF_8);
                Files.write(file, bytes);
                ObjectNode result = JSON.createObjectNode();
                result.put("bytes", bytes.length);
                return result;
            }
            case "db_env" -> {
                ObjectNode result = JSON.createObjectNode();
                result.put("dbUrl", String.valueOf(System.getenv("FENGYU_DB_URL")));
                return result;
            }
            default -> throw new IllegalArgumentException("Method not found: " + method);
        }
    }

    private static Path resolve(JsonNode params) {
        return Path.of(params.path("dir").asText()).resolve(params.path("name").asText());
    }

    private SmokeWorker() {
    }
}
