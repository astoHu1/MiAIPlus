package io.github.miaiplus;

import static org.junit.Assert.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Desktop loopback wire fixtures only: real HttpURLConnection, no Android device or external service. */
public class McpClientWireTest {
    private static final String ID = "f14888bd-b7ab-4bb5-8876-7ccde975b028";
    private static final String TOKEN = "wire-fixture-bearer-never-real";
    private static final String SESSION = "wire-fixture-session";
    private static final String PATH = "/fixture/exact-mcp/";

    @Test(timeout = 30000) public void actualHttpDiscoversPagesAndCallsJsonAtExactConfiguredPath() throws Exception {
        try (WireServer server = new WireServer(List.of(
                initialize(), accepted(),
                request -> json(request, new JSONObject().put("tools", new JSONArray().put(tool("weather")))
                    .put("nextCursor", "opaque-page-two")),
                request -> json(request, new JSONObject().put("tools", new JSONArray().put(tool("echo")))),
                request -> json(request, new JSONObject().put("content", new JSONArray().put(text("wire-json-ok"))))));
             McpClient client = new McpClient(entry(server))) {
            var tools = client.listTools();
            assertEquals(List.of("weather", "echo"), tools.stream().map(PluginToolSpec::sourceName).toList());
            assertEquals("object", new JSONObject(tools.get(1).inputSchema()).getString("type"));
            assertEquals("wire-json-ok", client.callTool("echo", new JSONObject().put("text", "中文 fixture")));
            server.awaitExpected();
            List<Request> requests = server.requests();
            assertEquals(5, requests.size());
            assertEquals(List.of("initialize", "notifications/initialized", "tools/list", "tools/list", "tools/call"),
                requests.stream().map(request -> request.body().optString("method")).toList());
            assertEquals("opaque-page-two", requests.get(3).body().getJSONObject("params").getString("cursor"));
            assertEquals("中文 fixture", requests.get(4).body().getJSONObject("params")
                .getJSONObject("arguments").getString("text"));
            assertWireHeaders(requests);
        }
    }

    @Test(timeout = 30000) public void actualHttpConsumesMultilineSseToolResultAfterEmptyAcceptedNotification() throws Exception {
        try (WireServer server = new WireServer(List.of(initialize(), accepted(), request -> {
                String response = envelope(request, new JSONObject().put("content", new JSONArray().put(text("wire-sse-ok"))))
                    .toString();
                String events = ": fixture keepalive\r\nid: prime\r\ndata:\r\n\r\n"
                    + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                    + "event: message\ndata: " + response.substring(0, 1)
                    + "\ndata: " + response.substring(1) + "\n\n";
                return new Response(200, "text/event-stream; charset=utf-8", Map.of(), events);
            })); McpClient client = new McpClient(entry(server))) {
            assertEquals("wire-sse-ok", client.callTool("echo", new JSONObject()));
            server.awaitExpected();
            assertEquals(3, server.requests().size());
            assertWireHeaders(server.requests());
        }
    }

    @Test(timeout = 30000) public void actualHttpFailureAfterSideEffectDoesNotReinitializeOrReplayCall() throws Exception {
        var sideEffects = new java.util.concurrent.atomic.AtomicInteger();
        try (WireServer server = new WireServer(List.of(initialize(), accepted(), request -> {
                assertEquals("tools/call", request.body().getString("method"));
                sideEffects.incrementAndGet();
                return new Response(500, "application/json", Map.of(), "private-fixture-error-" + TOKEN);
            })); McpClient client = new McpClient(entry(server))) {
            Exception error = assertThrows(McpClient.McpException.class,
                () -> client.callTool("write_fixture", new JSONObject()));
            server.awaitExpected();
            assertEquals(1, sideEffects.get());
            assertEquals(3, server.requests().size());
            assertFalse(error.getMessage().contains(TOKEN));
            assertFalse(error.getMessage().contains("private-fixture-error"));
            assertNull(error.getCause());
        }
    }

    @Test(timeout = 30000) public void actualHttpUsesExplicitOlderProtocolForDiscoveryAndShellResult() throws Exception {
        String selected = "2025-06-18";
        try (WireServer server = new WireServer(List.of(initialize(selected), accepted(),
                request -> json(request, new JSONObject().put("tools", new JSONArray().put(tool("shell")))),
                request -> json(request, new JSONObject().put("content", new JSONArray().put(text("older-wire-ok")))
                    .put("structuredContent", new JSONObject().put("exit_code", 0)))));
             McpClient client = new McpClient(entry(server, selected))) {
            assertEquals("shell", client.listTools().get(0).sourceName());
            assertEquals("older-wire-ok\n{\"exit_code\":0}", client.callTool("shell", new JSONObject().put("command", "printf fixture")));
            server.awaitExpected();
            assertWireHeaders(server.requests(), selected);
            assertEquals(4, server.requests().size());
        }
    }

    private static PluginSettings.Entry entry(WireServer server) {
        return entry(server, "2025-11-25");
    }

    private static PluginSettings.Entry entry(WireServer server, String protocol) {
        return new PluginSettings.Entry(ID, "mcp", "Wire fixture", false,
            "http://127.0.0.1:" + server.port() + PATH, TOKEN, protocol, List.of());
    }

    private static void assertWireHeaders(List<Request> requests) throws Exception {
        assertWireHeaders(requests, "2025-11-25");
    }

    private static void assertWireHeaders(List<Request> requests, String protocol) throws Exception {
        for (int i = 0; i < requests.size(); i++) {
            Request request = requests.get(i);
            assertEquals("POST", request.method());
            assertEquals(PATH, request.path());
            assertEquals("Bearer " + TOKEN, request.headers().get("Authorization"));
            assertEquals("application/json, text/event-stream", request.headers().get("Accept"));
            assertEquals("MiAIPlus/" + BuildConfig.VERSION_NAME, request.headers().get("User-Agent"));
            assertNull(request.headers().get("Cookie"));
            if (i == 0) {
                assertNull(request.headers().get("MCP-Session-Id"));
                assertEquals(protocol, request.body().getJSONObject("params").getString("protocolVersion"));
            } else {
                assertEquals(SESSION, request.headers().get("MCP-Session-Id"));
                assertEquals(protocol, request.headers().get("MCP-Protocol-Version"));
            }
        }
        assertFalse(requests.get(1).body().has("id"));
    }

    private static Step initialize() {
        return initialize("2025-11-25");
    }

    private static Step initialize(String protocol) {
        return request -> new Response(200, "application/json", Map.of("MCP-Session-Id", SESSION),
            envelope(request, new JSONObject().put("protocolVersion", protocol)
                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                .put("serverInfo", new JSONObject().put("name", "WireFixture").put("version", "1"))).toString());
    }

    private static Step accepted() {
        return request -> {
            assertEquals("notifications/initialized", request.body().getString("method"));
            return new Response(202, null, Map.of(), "");
        };
    }

    private static Response json(Request request, JSONObject result) throws Exception {
        return new Response(200, "application/json; charset=utf-8", Map.of(), envelope(request, result).toString());
    }

    private static JSONObject envelope(Request request, JSONObject result) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", request.body().get("id")).put("result", result);
    }

    private static JSONObject tool(String name) throws Exception {
        return new JSONObject().put("name", name).put("description", "Fixture only")
            .put("inputSchema", new JSONObject().put("type", "object").put("properties", new JSONObject()));
    }

    private static JSONObject text(String value) throws Exception { return new JSONObject().put("type", "text").put("text", value); }

    @FunctionalInterface private interface Step { Response respond(Request request) throws Exception; }
    private record Request(String method, String path, Map<String, String> headers, JSONObject body) { }
    private record Response(int status, String type, Map<String, String> headers, String body) { }

    private static final class WireServer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService executor;
        private final Future<?> worker;
        private final ArrayDeque<Step> steps;
        private final List<Request> requests = Collections.synchronizedList(new ArrayList<>());
        private final CountDownLatch expected;
        private final AtomicReference<Throwable> failure = new AtomicReference<>();
        private volatile boolean closed;
        private volatile Socket active;

        WireServer(List<Step> steps) throws IOException {
            this.steps = new ArrayDeque<>(steps);
            expected = new CountDownLatch(steps.size());
            listener = new ServerSocket(0, 8, InetAddress.getByName("127.0.0.1"));
            listener.setSoTimeout(5000);
            executor = Executors.newSingleThreadExecutor(task -> {
                Thread thread = new Thread(task, "miaiplus-mcp-wire-fixture");
                thread.setDaemon(true);
                return thread;
            });
            worker = executor.submit(this::serve);
        }

        int port() { return listener.getLocalPort(); }

        List<Request> requests() {
            synchronized (requests) { return List.copyOf(requests); }
        }

        void awaitExpected() throws Exception {
            assertTrue("Fixture requests timed out", expected.await(10, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Loopback fixture failed", failure.get());
            assertTrue("Unexpected extra network request", steps.isEmpty());
        }

        private void serve() {
            try {
                while (!closed) {
                    try (Socket socket = listener.accept()) {
                        active = socket;
                        socket.setSoTimeout(5000);
                        Request request = read(socket.getInputStream());
                        requests.add(request);
                        Step step = steps.pollFirst();
                        Response response;
                        if (step == null) {
                            failure.compareAndSet(null, new AssertionError("Unexpected extra network request"));
                            response = new Response(500, "text/plain", Map.of(), "Unexpected request");
                        } else response = step.respond(request);
                        write(socket, response);
                        expected.countDown();
                    } catch (SocketTimeoutException error) {
                        if (expected.getCount() > 0) throw error;
                    } finally { active = null; }
                }
            } catch (Throwable error) {
                if (!closed || !(error instanceof SocketException)) failure.compareAndSet(null, error);
                while (expected.getCount() > 0) expected.countDown();
            }
        }

        private static Request read(InputStream raw) throws Exception {
            InputStream input = new BufferedInputStream(raw);
            String[] first = line(input).split(" ", 3);
            if (first.length != 3) throw new IOException("Invalid fixture request line");
            TreeMap<String, String> headers = new TreeMap<>(String.CASE_INSENSITIVE_ORDER);
            String line;
            while (!(line = line(input)).isEmpty()) {
                int colon = line.indexOf(':');
                if (colon <= 0 || headers.size() >= 64) throw new IOException("Invalid fixture headers");
                headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
            }
            int length = Integer.parseInt(headers.getOrDefault("Content-Length", "-1"));
            if (length < 0 || length > McpClient.MAX_REQUEST_BYTES) throw new IOException("Invalid fixture body size");
            byte[] body = input.readNBytes(length);
            if (body.length != length) throw new EOFException("Incomplete fixture body");
            return new Request(first[0], first[1], Collections.unmodifiableMap(headers),
                new JSONObject(new String(body, StandardCharsets.UTF_8)));
        }

        private static String line(InputStream input) throws IOException {
            ByteArrayOutputStream bytes = new ByteArrayOutputStream();
            for (int value; (value = input.read()) >= 0;) {
                if (value == '\n') return new String(bytes.toByteArray(), StandardCharsets.US_ASCII).replaceAll("\r$", "");
                bytes.write(value);
                if (bytes.size() > 16384) throw new IOException("Fixture header line too long");
            }
            throw new EOFException("Incomplete fixture headers");
        }

        private static void write(Socket socket, Response response) throws IOException {
            byte[] body = response.body().getBytes(StandardCharsets.UTF_8);
            StringBuilder headers = new StringBuilder("HTTP/1.1 ").append(response.status()).append(" Fixture\r\n")
                .append("Content-Length: ").append(body.length).append("\r\nConnection: close\r\n");
            if (response.type() != null) headers.append("Content-Type: ").append(response.type()).append("\r\n");
            for (var header : response.headers().entrySet()) headers.append(header.getKey()).append(": ")
                .append(header.getValue()).append("\r\n");
            headers.append("\r\n");
            socket.getOutputStream().write(headers.toString().getBytes(StandardCharsets.US_ASCII));
            socket.getOutputStream().write(body);
            socket.getOutputStream().flush();
        }

        @Override public void close() throws Exception {
            closed = true;
            listener.close();
            Socket socket = active;
            if (socket != null) socket.close();
            executor.shutdownNow();
            worker.get(2, TimeUnit.SECONDS);
            assertTrue("Fixture thread did not exit", executor.awaitTermination(2, TimeUnit.SECONDS));
            if (failure.get() != null) throw new AssertionError("Loopback fixture failed", failure.get());
        }
    }
}
