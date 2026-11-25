package io.github.miaiplus;

import static org.junit.Assert.*;
import java.io.BufferedInputStream;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.EOFException;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.SocketException;
import java.net.SocketTimeoutException;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.CancellationException;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

/** Fake-transport edge cases plus desktop loopback wire fixtures (real HttpURLConnection, no device). */
public class McpClientTest {
    private static final String ID = "a38503b3-5ada-4d7d-b66c-b7e321e81c01";
    private static final String ENDPOINT = "https://mcp.example.test/exact/mcp/";
    private static final String TOKEN = "fixture-token-never-echo";
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
            List<WireRequest> requests = server.requests();
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
                return new WireResponse(200, "text/event-stream; charset=utf-8", Map.of(), events);
            })); McpClient client = new McpClient(entry(server))) {
            assertEquals("wire-sse-ok", client.callTool("echo", new JSONObject()));
            server.awaitExpected();
            assertEquals(3, server.requests().size());
            assertWireHeaders(server.requests());
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

    @Test(timeout = 30000) public void managedTermuxKeepsNonzeroDiagnosticsOnceAndDeduplicatesEquivalentJson() throws Exception {
        var digest = new JSONObject().put("ok", false).put("exit", 7)
            .put("excerpt", "fixture stderr").put("out_ref", "abc123");
        String raw = "{\"out\":\"a\\/b\",\"facts\":{\"items\":[\"中文\",2]},\"ok\":true}";
        JSONObject structured = new JSONObject().put("ok", true).put("facts",
            new JSONObject().put("items", new JSONArray().put("中文").put(2))).put("out", "a/b");
        try (WireServer server = new WireServer(List.of(initialize(TermuxMcp.PROTOCOL), accepted(),
                request -> json(request, new JSONObject().put("isError", true)
                    .put("content", new JSONArray().put(text(digest.toString()))).put("structuredContent", digest)),
                request -> json(request, new JSONObject().put("content", new JSONArray().put(text(raw)))
                    .put("structuredContent", structured))));
             McpClient client = new McpClient(new PluginSettings.Entry(PluginSettings.TERMUX_ID,
                 PluginSettings.TERMUX_KIND, "Termux fixture", true, "http://127.0.0.1:" + server.port() + "/mcp",
                 "a".repeat(64), TermuxMcp.PROTOCOL, List.of()))) {
            String result = client.callTool("run_digest", new JSONObject().put("cmd", "exit 7"));
            assertTrue(result.contains("Termux 工具报告失败"));
            assertTrue(result.contains(digest.toString()));
            assertEquals(result.indexOf("fixture stderr"), result.lastIndexOf("fixture stderr"));
            assertEquals(raw, client.callTool("run_digest", new JSONObject().put("cmd", "fixture")));
            server.awaitExpected();
            assertEquals(2L, server.requests().stream().filter(r -> "tools/call".equals(r.body().optString("method"))).count());
        }
    }

    @Test public void handshakeDiscoversCredentialFreeToolsAtExactEndpoint() throws Exception {
        Script script = new Script();
        script.initialize("session-one");
        script.result(new JSONObject().put("tools", new JSONArray().put(tool("echo"))));
        try (McpClient client = client(script)) {
            var tools = client.listTools();
            assertEquals(1, tools.size());
            assertEquals(ID, tools.get(0).pluginId());
            assertEquals(PluginToolSpec.mcpName(ID, "echo"), tools.get(0).name());
            assertFalse(tools.get(0).toJson().toString().contains(TOKEN));
            assertFalse(tools.get(0).toJson().toString().contains(ENDPOINT));
            assertEquals(3, script.connections.size());
            FakeConnection initialize = script.connections.get(0), initialized = script.connections.get(1);
            assertEquals("initialize", initialize.request.getString("method"));
            assertEquals("2025-11-25", initialize.request.getJSONObject("params").getString("protocolVersion"));
            assertEquals(0, initialize.request.getJSONObject("params").getJSONObject("capabilities").length());
            assertNull(initialize.getRequestProperty("MCP-Session-Id"));
            assertFalse(initialized.request.has("id"));
            assertEquals("notifications/initialized", initialized.request.getString("method"));
            for (FakeConnection connection : script.connections) {
                assertEquals(ENDPOINT, connection.getURL().toString());
                assertFalse(connection.getInstanceFollowRedirects());
                assertEquals("Bearer " + TOKEN, connection.getRequestProperty("Authorization"));
                assertEquals("application/json, text/event-stream", connection.getRequestProperty("Accept"));
                assertTrue(connection.getConnectTimeout() <= 15000);
                assertTrue(connection.getReadTimeout() <= 15000);
                assertTrue(connection.disconnected);
            }
            for (FakeConnection connection : script.connections.subList(1, 3)) {
                assertEquals("session-one", connection.getRequestProperty("MCP-Session-Id"));
                assertEquals("2025-11-25", connection.getRequestProperty("MCP-Protocol-Version"));
            }
        }
        assertEquals(3, script.connections.size()); // close must not open a DELETE or other request.
    }

    @Test public void requestsStopClosedWhenAProcessGlobalCookieHandlerIsInstalled() throws Exception {
        CookieHandler originalCookies = CookieHandler.getDefault();
        try {
            CookieManager cookies = new CookieManager();
            CookieHandler.setDefault(cookies);
            Script script = new Script();
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, client::listTools);
                assertTrue(script.connections.isEmpty());
                assertSame(cookies, CookieHandler.getDefault());
            }
        } finally {
            CookieHandler.setDefault(originalCookies);
        }
    }

    @Test public void invalidHandshakeStopsBeforeAnyToolRequest() throws Exception {
        JSONObject unknownVersion = initializeResult().put("protocolVersion", "2026-07-28");
        JSONObject noTools = initializeResult().put("capabilities", new JSONObject());
        JSONObject noIdentity = initializeResult(); noIdentity.remove("serverInfo");
        List<Script> cases = new ArrayList<>();
        for (JSONObject result : List.of(unknownVersion, noTools, noIdentity)) {
            Script script = new Script(); script.result(result); cases.add(script);
        }
        for (String session : List.of("invalid\r\nHeader: value", "x".repeat(257))) {
            Script script = new Script(); script.initialize(session); cases.add(script);
        }
        Script nonEmptyAccepted = new Script();
        nonEmptyAccepted.result(initializeResult());
        nonEmptyAccepted.add(202, "application/json", Map.of(), request -> TOKEN);
        cases.add(nonEmptyAccepted);
        for (boolean wrapped : new boolean[]{false, true}) {
            for (String part : wrapped ? List.of(" trailing", "{}", "[]")
                : List.of("x".repeat(McpClient.MAX_RESPONSE_BYTES + 1), "{\"value\":".repeat(66) + "{}" + "}".repeat(66), "[]")) {
                Script script = new Script();
                script.add(200, "application/json", Map.of(), request -> wrapped
                    ? reply(request.opt("id"), initializeResult()) + part : part);
                cases.add(script);
            }
        }
        for (Script script : cases) {
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
                assertEquals(script == nonEmptyAccepted ? 2 : 1, script.connections.size());
            }
        }
        for (String selected : PluginSettings.MCP_PROTOCOL_VERSIONS) {
            // A selected protocol never silently switches to another supported version.
            String other = "2025-11-25".equals(selected) ? "2025-06-18" : "2025-11-25";
            Script script = new Script(); script.initialize(null, other);
            try (McpClient client = client(script, selected)) {
                Exception error = assertThrows(McpClient.McpException.class,
                    () -> client.callTool("shell", new JSONObject()));
                assertTrue(error.getMessage().contains(selected));
                assertEquals(1, script.connections.size());
            }
        }
    }

    @Test public void unexpectedToolResponseFailsClosedWithoutReplayingTool() throws Exception {
        List<Script> cases = new ArrayList<>();
        Script wrongId = new Script(); wrongId.initialize(null);
        wrongId.add(200, "application/json", Map.of(), request -> reply("wrong-id", new JSONObject().put("content", new JSONArray())));
        cases.add(wrongId);
        Script serverRequest = new Script(); serverRequest.initialize(null);
        serverRequest.add(200, "text/event-stream", Map.of(), request ->
            "data: {\"jsonrpc\":\"2.0\",\"id\":\"server-id\",\"method\":\"sampling/createMessage\",\"params\":{}}\n\n");
        cases.add(serverRequest);
        Script changedSession = new Script(); changedSession.initialize("one");
        changedSession.add(200, "application/json", Map.of("MCP-Session-Id", "two"), request -> reply(request.opt("id"), new JSONObject()));
        cases.add(changedSession);
        Script sseEof = new Script(); sseEof.initialize(null);
        sseEof.add(200, "text/event-stream", Map.of(), request -> "data: "
            + reply(request.opt("id"), new JSONObject().put("content", new JSONArray())));
        cases.add(sseEof);
        for (String type : List.of("image", "resource_link", "resource", "audio")) {
            Script script = new Script(); script.initialize(null);
            script.result(new JSONObject().put("content", new JSONArray()
                .put(new JSONObject().put("type", type).put("uri", "https://untrusted.example.test/resource"))));
            cases.add(script);
        }
        for (Script script : cases) {
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
                assertEquals(3, script.connections.size());
            }
        }
    }

    @Test public void invalidCatalogRejectsWholeDiscovery() throws Exception {
        Script looping = new Script(); looping.initialize(null);
        JSONObject page = new JSONObject().put("tools", new JSONArray()).put("nextCursor", "same");
        looping.result(page); looping.result(page);
        try (McpClient client = client(looping)) {
            assertThrows(McpClient.McpException.class, client::listTools);
            assertEquals(4, looping.connections.size());
        }
        for (int variant = 0; variant < 3; variant++) {
            Script script = new Script(); script.initialize(null);
            JSONArray tools = new JSONArray();
            if (variant == 2) tools.put(tool("echo").put("inputSchema", new JSONObject().put("type", "array")));
            else for (int i = 0; i < (variant == 1 ? 65 : 2); i++) tools.put(tool(variant == 1 ? "tool" + i : "same"));
            script.result(new JSONObject().put("tools", tools));
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, client::listTools);
                assertEquals(3, script.connections.size());
            }
        }
    }

    @Test public void malformedProtocolErrorsAndEchoedBearerNeverExposeBodyOrCause() throws Exception {
        List<Script> cases = new ArrayList<>();
        for (boolean protocolError : new boolean[]{false, true}) {
            Script script = new Script();
            script.add(200, "application/json", Map.of(), request -> protocolError
                ? new JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id"))
                    .put("error", new JSONObject().put("code", -32000).put("message", TOKEN + ENDPOINT)).toString()
                : "not-json-" + TOKEN);
            cases.add(script);
        }
        for (boolean schema : new boolean[]{false, true}) {
            Script script = new Script(); script.initialize(null);
            JSONObject tool = tool("echo");
            if (schema) tool.getJSONObject("inputSchema").put("description", TOKEN);
            else tool.put("description", TOKEN);
            script.result(new JSONObject().put("tools", new JSONArray().put(tool)));
            cases.add(script);
        }
        for (Script script : cases) {
            try (McpClient client = client(script)) {
                Exception error = assertThrows(McpClient.McpException.class, client::listTools);
                assertFalse(error.getMessage().contains(TOKEN));
                assertFalse(error.getMessage().contains(ENDPOINT));
                assertNull(error.getCause());
            }
        }
    }

    @Test public void sseStripsLeadingBomAndJoinsCrSeparatedMultilineData() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.add(200, "text/event-stream; charset=utf-8", Map.of(), request -> {
            String finalJson = reply(request.opt("id"), new JSONObject().put("content", new JSONArray().put(text("streamed"))));
            return "\uFEFF: keepalive\r\nid: cursor\r\ndata:\r\n\r\n"
                + "data: " + finalJson.substring(0, 1) + "\rdata: " + finalJson.substring(1) + "\r\r";
        });
        try (McpClient client = client(script)) {
            assertEquals("streamed", client.callTool("echo", new JSONObject()));
        }
    }

    @Test public void sseResultDoesNotWaitForMoreBytesOrEofWithCrOrCrLfSeparators() throws Exception {
        for (String separator : List.of("\r", "\r\n")) {
            Script script = new Script(); script.initialize(null);
            script.openEventStream(request -> {
                String response = reply(request.opt("id"), new JSONObject().put("content", new JSONArray().put(text("complete"))));
                return ": keepalive" + separator
                    + "data: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}" + separator + separator
                    + "data: " + response + separator + separator;
            });
            try (McpClient client = client(script)) {
                assertEquals("complete", client.callTool("echo", new JSONObject()));
                assertEquals(3, script.connections.size());
            }
        }
    }

    @Test public void toolErrorsMalformedFieldsAndOversizedRequestsAreSanitized() throws Exception {
        Script toolError = new Script(); toolError.initialize(null);
        toolError.result(new JSONObject().put("isError", true).put("content", new JSONArray().put(text(TOKEN))));
        try (McpClient client = client(toolError)) {
            McpClient.ToolException error = assertThrows(McpClient.ToolException.class,
                () -> client.callTool("echo", new JSONObject()));
            assertFalse(error.getMessage().contains(TOKEN));
            assertNull(error.getCause());
            assertEquals(3, toolError.connections.size());
        }
        Script wrongField = new Script(); wrongField.initialize(null);
        wrongField.result(new JSONObject().put("content", TOKEN));
        try (McpClient client = client(wrongField)) {
            Exception error = assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
            assertFalse(error.getMessage().contains(TOKEN));
            assertNull(error.getCause());
        }
        Script oversize = new Script(); oversize.initialize(null);
        try (McpClient client = client(oversize)) {
            assertThrows(McpClient.McpException.class,
                () -> client.callTool("echo", new JSONObject().put("text", "a".repeat(McpClient.MAX_REQUEST_BYTES))));
            assertEquals(2, oversize.connections.size());
        }
    }

    @Test public void cancellationBeforeOpenAndDuringResponseDisconnectsWithoutReplay() throws Exception {
        Script neverOpened = new Script();
        try (McpClient client = client(neverOpened)) {
            client.cancel();
            assertThrows(CancellationException.class, client::listTools);
            assertTrue(neverOpened.connections.isEmpty());
        }
        Script script = new Script();
        AtomicReference<McpClient> holder = new AtomicReference<>();
        script.add(200, "application/json", Map.of(), request -> {
            holder.get().cancel();
            return TOKEN;
        });
        try (McpClient client = client(script)) {
            holder.set(client);
            assertThrows(CancellationException.class, client::listTools);
            assertTrue(script.connections.get(0).disconnected);
            assertEquals(1, script.connections.size());
        }
    }

    @Test public void totalDeadlineAppliesAcrossHandshakeAndProgress() throws Exception {
        Script script = new Script(); AtomicLong clock = new AtomicLong();
        script.add(200, "application/json", Map.of(), request -> {
            clock.set(McpClient.BUDGET_MS);
            return reply(request.opt("id"), new JSONObject());
        });
        try (McpClient client = new McpClient(entry(), script, clock::get)) {
            Exception error = assertThrows(McpClient.McpException.class, client::listTools);
            assertTrue(error.getMessage().contains("60 秒"));
            assertTrue(script.connections.get(0).disconnected);
            assertEquals(1, script.connections.size());
        }
    }

    @Test public void redirectAuthenticationAndExpiredSessionErrorsNeverFollowReadOrRetry() throws Exception {
        Script redirect = new Script();
        redirect.add(307, "application/json", Map.of("Location", "https://other.example.test/secret"), request -> TOKEN);
        try (McpClient client = client(redirect)) {
            Exception error = assertThrows(McpClient.McpException.class, client::listTools);
            assertFalse(error.getMessage().contains(TOKEN));
            assertFalse(error.getMessage().contains("other.example"));
            assertEquals(1, redirect.connections.size());
            assertFalse(redirect.connections.get(0).bodyRead);
        }
        for (int status : new int[]{401, 403, 404, 500}) {
            Script script = new Script(); script.initialize("one");
            script.add(status, "application/json", Map.of(), request -> TOKEN);
            try (McpClient client = client(script)) {
                Exception error = assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
                assertFalse(error.getMessage().contains(TOKEN));
                assertEquals(3, script.connections.size());
                assertFalse(script.connections.get(2).bodyRead);
            }
        }
    }

    private static PluginSettings.Entry entry(WireServer server) {
        return entry(server, "2025-11-25");
    }

    private static PluginSettings.Entry entry(WireServer server, String protocol) {
        return new PluginSettings.Entry(ID, "mcp", "Wire fixture", false,
            "http://127.0.0.1:" + server.port() + PATH, TOKEN, protocol, List.of());
    }

    private static void assertWireHeaders(List<WireRequest> requests) throws Exception {
        assertWireHeaders(requests, "2025-11-25");
    }

    private static void assertWireHeaders(List<WireRequest> requests, String protocol) throws Exception {
        for (int i = 0; i < requests.size(); i++) {
            WireRequest request = requests.get(i);
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
        return request -> new WireResponse(200, "application/json", Map.of("MCP-Session-Id", SESSION),
            envelope(request, new JSONObject().put("protocolVersion", protocol)
                .put("capabilities", new JSONObject().put("tools", new JSONObject()))
                .put("serverInfo", new JSONObject().put("name", "WireFixture").put("version", "1"))).toString());
    }

    private static Step accepted() {
        return request -> {
            assertEquals("notifications/initialized", request.body().getString("method"));
            return new WireResponse(202, null, Map.of(), "");
        };
    }

    private static WireResponse json(WireRequest request, JSONObject result) throws Exception {
        return new WireResponse(200, "application/json; charset=utf-8", Map.of(), envelope(request, result).toString());
    }

    private static JSONObject envelope(WireRequest request, JSONObject result) throws Exception {
        return new JSONObject().put("jsonrpc", "2.0").put("id", request.body().get("id")).put("result", result);
    }

    @FunctionalInterface private interface Step { WireResponse respond(WireRequest request) throws Exception; }
    private record WireRequest(String method, String path, Map<String, String> headers, JSONObject body) { }
    private record WireResponse(int status, String type, Map<String, String> headers, String body) { }

    private static final class WireServer implements AutoCloseable {
        private final ServerSocket listener;
        private final ExecutorService executor;
        private final Future<?> worker;
        private final ArrayDeque<Step> steps;
        private final List<WireRequest> requests = Collections.synchronizedList(new ArrayList<>());
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

        List<WireRequest> requests() {
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
                        WireRequest request = read(socket.getInputStream());
                        requests.add(request);
                        Step step = steps.pollFirst();
                        WireResponse response;
                        if (step == null) {
                            failure.compareAndSet(null, new AssertionError("Unexpected extra network request"));
                            response = new WireResponse(500, "text/plain", Map.of(), "Unexpected request");
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

        private static WireRequest read(InputStream raw) throws Exception {
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
            return new WireRequest(first[0], first[1], Collections.unmodifiableMap(headers),
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

        private static void write(Socket socket, WireResponse response) throws IOException {
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

    private static PluginSettings.Entry entry() {
        return new PluginSettings.Entry(ID, "mcp", "Fixture", false, ENDPOINT, TOKEN, "2025-11-25", List.of());
    }

    private static McpClient client(Script script) { return new McpClient(entry(), script, () -> 1000); }

    private static McpClient client(Script script, String protocol) {
        var configured = new PluginSettings.Entry(ID, "mcp", "Fixture", false, ENDPOINT, TOKEN, protocol, List.of());
        return new McpClient(configured, script, () -> 1000);
    }

    private static JSONObject tool(String name) throws Exception {
        return new JSONObject().put("name", name).put("description", "Fixture tool")
            .put("inputSchema", new JSONObject().put("type", "object").put("properties", new JSONObject()));
    }

    private static JSONObject text(String text) throws Exception { return new JSONObject().put("type", "text").put("text", text); }

    private static JSONObject initializeResult() throws Exception {
        return new JSONObject().put("protocolVersion", "2025-11-25")
            .put("capabilities", new JSONObject().put("tools", new JSONObject()))
            .put("serverInfo", new JSONObject().put("name", "Fixture").put("version", "1"));
    }

    private static String reply(Object id, JSONObject result) {
        try { return new JSONObject().put("jsonrpc", "2.0").put("id", id).put("result", result).toString(); }
        catch (Exception error) { throw new AssertionError(error); }
    }

    @FunctionalInterface private interface ReplyBody { String apply(JSONObject request) throws Exception; }

    private record Response(int status, String type, Map<String, String> headers, ReplyBody body, boolean remainsOpen) { }

    private static final class Script implements McpClient.ConnectionFactory {
        final ArrayDeque<Response> responses = new ArrayDeque<>();
        final ArrayList<FakeConnection> connections = new ArrayList<>();

        void initialize(String session) throws Exception {
            initialize(session, "2025-11-25");
        }

        void initialize(String session, String protocol) throws Exception {
            add(200, "application/json", session == null ? Map.of() : Map.of("MCP-Session-Id", session),
                request -> { try { return reply(request.opt("id"), initializeResult().put("protocolVersion", protocol)); } catch (Exception e) { throw new AssertionError(e); } });
            add(202, "application/json", Map.of(), request -> "");
        }

        void result(JSONObject result) { add(200, "application/json", Map.of(), request -> reply(request.opt("id"), result)); }

        void add(int status, String type, Map<String, String> headers, ReplyBody body) {
            responses.add(new Response(status, type, headers, body, false));
        }

        void openEventStream(ReplyBody body) {
            responses.add(new Response(200, "text/event-stream", Map.of(), body, true));
        }

        @Override public HttpURLConnection open(URL url) {
            if (responses.isEmpty()) throw new AssertionError("Unexpected additional network request");
            FakeConnection connection = new FakeConnection(url, responses.removeFirst());
            connections.add(connection);
            return connection;
        }
    }

    private static final class FakeConnection extends HttpURLConnection {
        final Response response;
        final ByteArrayOutputStream sent = new ByteArrayOutputStream();
        JSONObject request;
        byte[] responseBody;
        boolean disconnected, bodyRead;

        FakeConnection(URL url, Response response) { super(url); this.response = response; }
        @Override public void connect() { }
        @Override public boolean usingProxy() { return false; }
        @Override public void disconnect() { disconnected = true; }
        @Override public OutputStream getOutputStream() { return sent; }
        @Override public int getResponseCode() throws IOException {
            try {
                request = new JSONObject(new String(sent.toByteArray(), StandardCharsets.UTF_8));
                responseBody = response.body().apply(request).getBytes(StandardCharsets.UTF_8);
                return response.status();
            } catch (Exception error) { throw new IOException(error); }
        }
        @Override public String getHeaderField(String name) { return response.headers().get(name); }
        @Override public String getContentType() { return response.type(); }
        @Override public InputStream getInputStream() {
            bodyRead = true;
            if (!response.remainsOpen()) return new ByteArrayInputStream(responseBody);
            // A live SSE connection has no EOF after its final event. Fail immediately if the
            // parser tries another read, rather than making the regression test wait for a timeout.
            return new ByteArrayInputStream(responseBody) {
                @Override public synchronized int read() {
                    if (available() == 0) throw new AssertionError("Read after a complete SSE event");
                    return super.read();
                }
                @Override public synchronized int read(byte[] buffer, int offset, int length) {
                    if (available() == 0) throw new AssertionError("Read after a complete SSE event");
                    return super.read(buffer, offset, length);
                }
            };
        }
    }
}
