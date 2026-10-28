package io.github.miaiplus;

import static org.junit.Assert.*;
import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.io.OutputStream;
import java.net.HttpURLConnection;
import java.net.CookieHandler;
import java.net.CookieManager;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CancellationException;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class McpClientTest {
    private static final String ID = "a38503b3-5ada-4d7d-b66c-b7e321e81c01";
    private static final String ENDPOINT = "https://mcp.example.test/exact/mcp/";
    private static final String TOKEN = "fixture-token-never-echo";

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

    @Test public void paginatedDiscoveryRetainsSourceNamesAndCursorOrder() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.result(new JSONObject().put("tools", new JSONArray().put(tool("one"))).put("nextCursor", "opaque-1"));
        script.result(new JSONObject().put("tools", new JSONArray().put(tool("two"))));
        try (McpClient client = client(script)) {
            assertEquals(List.of("one", "two"), client.listTools().stream().map(PluginToolSpec::sourceName).toList());
            assertEquals("opaque-1", script.connections.get(3).request.getJSONObject("params").getString("cursor"));
        }
    }

    @Test public void toolCallPreservesTextAndStructuredData() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.result(new JSONObject().put("content", new JSONArray().put(text("answer")))
            .put("structuredContent", new JSONObject().put("count", 2)));
        try (McpClient client = client(script)) {
            String response = client.callTool("echo", new JSONObject().put("value", "fixture"));
            assertEquals("answer\n{\"count\":2}", response);
            JSONObject params = script.connections.get(2).request.getJSONObject("params");
            assertEquals("echo", params.getString("name"));
            assertEquals("fixture", params.getJSONObject("arguments").getString("value"));
        }
    }

    @Test public void toolExecutionErrorIsNotReportedAsSuccessOrEchoed() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.result(new JSONObject().put("isError", true).put("content", new JSONArray().put(text(TOKEN))));
        try (McpClient client = client(script)) {
            McpClient.ToolException error = assertThrows(McpClient.ToolException.class,
                () -> client.callTool("echo", new JSONObject()));
            assertFalse(error.getMessage().contains(TOKEN));
            assertNull(error.getCause());
            assertEquals(3, script.connections.size());
        }
    }

    @Test public void responseIdMismatchStopsWithoutReplayingTool() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.add(200, "application/json", Map.of(), request -> reply("wrong-id", new JSONObject().put("content", new JSONArray())));
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
            assertEquals(3, script.connections.size());
        }
    }

    @Test public void rejectedProtocolVersionNeverSendsInitializedOrTool() throws Exception {
        Script script = new Script();
        script.result(new JSONObject().put("protocolVersion", "2026-07-28").put("capabilities", new JSONObject().put("tools", new JSONObject())));
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
            assertEquals(1, script.connections.size());
        }
    }

    @Test public void explicitlySelectedOlderProtocolUsesItsHandshakeAndSubsequentHeaders() throws Exception {
        Script script = new Script();
        script.initialize("older-session", "2025-06-18");
        script.result(new JSONObject().put("content", new JSONArray().put(text("older-text")))
            .put("structuredContent", new JSONObject().put("exit_code", 0)));
        try (McpClient client = client(script, "2025-06-18")) {
            assertEquals("older-text\n{\"exit_code\":0}", client.callTool("shell", new JSONObject().put("command", "printf fixture")));
            assertEquals(3, script.connections.size());
            assertEquals("2025-06-18", script.connections.get(0).request.getJSONObject("params").getString("protocolVersion"));
            for (FakeConnection connection : script.connections.subList(1, 3)) {
                assertEquals("2025-06-18", connection.getRequestProperty("MCP-Protocol-Version"));
                assertEquals("older-session", connection.getRequestProperty("MCP-Session-Id"));
            }
        }
    }

    @Test public void selectedProtocolNeverSilentlySwitchesToAnotherSupportedVersion() throws Exception {
        for (String selected : PluginSettings.MCP_PROTOCOL_VERSIONS) {
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

    @Test public void missingToolsCapabilityDoesNotInvokeTool() throws Exception {
        Script script = new Script();
        script.result(new JSONObject().put("protocolVersion", "2025-11-25").put("capabilities", new JSONObject()));
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, client::listTools);
            assertEquals(1, script.connections.size());
        }
    }

    @Test public void redirectIsRejectedBeforeAnySecondEndpointOrBodyRead() throws Exception {
        Script script = new Script();
        script.add(307, "application/json", Map.of("Location", "https://other.example.test/secret"), request -> TOKEN);
        try (McpClient client = client(script)) {
            Exception error = assertThrows(McpClient.McpException.class, client::listTools);
            assertFalse(error.getMessage().contains(TOKEN));
            assertFalse(error.getMessage().contains("other.example"));
            assertEquals(1, script.connections.size());
            assertFalse(script.connections.get(0).bodyRead);
        }
    }

    @Test public void sseSupportsCommentsPrimingNotificationsAndMultilineData() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.add(200, "text/event-stream; charset=utf-8", Map.of(), request -> {
            String finalJson = reply(request.opt("id"), new JSONObject().put("content", new JSONArray().put(text("streamed"))));
            return "\uFEFF: keepalive\r\nid: cursor\r\ndata:\r\n\r\n"
                + "event: message\ndata: {\"jsonrpc\":\"2.0\",\"method\":\"notifications/progress\",\"params\":{}}\n\n"
                + "data: " + finalJson.substring(0, 1) + "\rdata: " + finalJson.substring(1) + "\r\r";
        });
        try (McpClient client = client(script)) {
            assertEquals("streamed", client.callTool("echo", new JSONObject()));
        }
    }

    @Test public void sseEofWithoutFinalDelimitedResponseIsFailure() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.add(200, "text/event-stream", Map.of(), request -> "data: "
            + reply(request.opt("id"), new JSONObject().put("content", new JSONArray())));
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
            assertEquals(3, script.connections.size());
        }
    }

    @Test public void sseCarriageReturnResultDoesNotWaitForMoreBytesOrEof() throws Exception {
        assertSseCompletedWithoutEof("\r");
    }

    @Test public void sseCrLfResultDoesNotWaitForMoreBytesOrEof() throws Exception {
        assertSseCompletedWithoutEof("\r\n");
    }

    private static void assertSseCompletedWithoutEof(String separator) throws Exception {
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

    @Test public void serverInitiatedClientRequestFailsClosed() throws Exception {
        Script script = new Script(); script.initialize(null);
        script.add(200, "text/event-stream", Map.of(), request ->
            "data: {\"jsonrpc\":\"2.0\",\"id\":\"server-id\",\"method\":\"sampling/createMessage\",\"params\":{}}\n\n");
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
            assertEquals(3, script.connections.size());
        }
    }

    @Test public void repeatedCursorCannotLoopDiscovery() throws Exception {
        Script script = new Script(); script.initialize(null);
        JSONObject page = new JSONObject().put("tools", new JSONArray()).put("nextCursor", "same");
        script.result(page); script.result(page);
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, client::listTools);
            assertEquals(4, script.connections.size());
        }
    }

    @Test public void duplicateNamesAndMoreThan64ToolsRejectWholeCatalog() throws Exception {
        for (boolean overflow : new boolean[]{false, true}) {
            Script script = new Script(); script.initialize(null);
            JSONArray tools = new JSONArray();
            for (int i = 0; i < (overflow ? 65 : 2); i++) tools.put(tool(overflow ? "tool" + i : "same"));
            script.result(new JSONObject().put("tools", tools));
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, client::listTools);
            }
        }
    }

    @Test public void responseByteLimitAndNestingLimitRejectUntrustedInput() throws Exception {
        for (String payload : List.of("x".repeat(McpClient.MAX_RESPONSE_BYTES + 1),
            "{\"value\":".repeat(66) + "{}" + "}".repeat(66))) {
            Script script = new Script();
            script.add(200, "application/json", Map.of(), request -> payload);
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, client::listTools);
                assertEquals(1, script.connections.size());
            }
        }
    }

    @Test public void malformedJsonAndProtocolErrorsNeverExposeBodyOrCause() throws Exception {
        for (boolean protocolError : new boolean[]{false, true}) {
            Script script = new Script();
            script.add(200, "application/json", Map.of(), request -> protocolError
                ? new JSONObject().put("jsonrpc", "2.0").put("id", request.opt("id"))
                    .put("error", new JSONObject().put("code", -32000).put("message", TOKEN + ENDPOINT)).toString()
                : "not-json-" + TOKEN);
            try (McpClient client = client(script)) {
                Exception error = assertThrows(McpClient.McpException.class, client::listTools);
                assertFalse(error.getMessage().contains(TOKEN));
                assertFalse(error.getMessage().contains(ENDPOINT));
                assertNull(error.getCause());
            }
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

    @Test public void sessionIdentityCannotChangeDuringToolCall() throws Exception {
        Script script = new Script(); script.initialize("one");
        script.add(200, "application/json", Map.of("MCP-Session-Id", "two"), request -> reply(request.opt("id"), new JSONObject()));
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
            assertEquals(3, script.connections.size());
        }
    }

    @Test public void sessionIdentityRejectsHeaderInjectionAndOversizedValues() throws Exception {
        for (String session : List.of("invalid\r\nHeader: value", "x".repeat(257))) {
            Script script = new Script(); script.initialize(session);
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, client::listTools);
                assertEquals(1, script.connections.size());
            }
        }
    }

    @Test public void authenticationAndExpiredSessionErrorsNeverRetryTool() throws Exception {
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

    @Test public void imageAndResourceContentAreNotFetchedOrMisreportedAsText() throws Exception {
        for (String type : List.of("image", "resource_link", "resource", "audio")) {
            Script script = new Script(); script.initialize(null);
            script.result(new JSONObject().put("content", new JSONArray()
                .put(new JSONObject().put("type", type).put("uri", "https://untrusted.example.test/resource"))));
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, () -> client.callTool("echo", new JSONObject()));
                assertEquals(3, script.connections.size());
            }
        }
    }

    @Test public void malformedToolFieldsAndOversizedRequestsAreSanitized() throws Exception {
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

    @Test public void initializedRequiresAnEmptyAcceptedResponse() throws Exception {
        Script script = new Script();
        script.result(initializeResult());
        script.add(202, "application/json", Map.of(), request -> TOKEN);
        try (McpClient client = client(script)) {
            assertThrows(McpClient.McpException.class, client::listTools);
            assertEquals(2, script.connections.size());
        }
    }

    @Test public void trailingJsonAndNonObjectEnvelopesAreRejected() throws Exception {
        for (String suffix : List.of(" trailing", "{}", "[]")) {
            Script script = new Script();
            script.add(200, "application/json", Map.of(), request -> reply(request.opt("id"), initializeResult()) + suffix);
            try (McpClient client = client(script)) {
                assertThrows(McpClient.McpException.class, client::listTools);
                assertEquals(1, script.connections.size());
            }
        }
        Script array = new Script();
        array.add(200, "application/json", Map.of(), request -> "[]");
        try (McpClient client = client(array)) {
            assertThrows(McpClient.McpException.class, client::listTools);
        }
    }

    @Test public void missingServerIdentityAndNonObjectSchemaAreRejected() throws Exception {
        Script identity = new Script();
        JSONObject result = initializeResult(); result.remove("serverInfo");
        identity.result(result);
        try (McpClient client = client(identity)) {
            assertThrows(McpClient.McpException.class, client::listTools);
            assertEquals(1, identity.connections.size());
        }
        Script schema = new Script(); schema.initialize(null);
        schema.result(new JSONObject().put("tools", new JSONArray().put(tool("echo")
            .put("inputSchema", new JSONObject().put("type", "array")))));
        try (McpClient client = client(schema)) {
            assertThrows(McpClient.McpException.class, client::listTools);
            assertEquals(3, schema.connections.size());
        }
    }

    @Test public void discoveryCannotEchoBearerIntoModelVisibleDescriptionsOrSchema() throws Exception {
        for (boolean schema : new boolean[]{false, true}) {
            Script script = new Script(); script.initialize(null);
            JSONObject tool = tool("echo");
            if (schema) tool.getJSONObject("inputSchema").put("description", TOKEN);
            else tool.put("description", TOKEN);
            script.result(new JSONObject().put("tools", new JSONArray().put(tool)));
            try (McpClient client = client(script)) {
                Exception error = assertThrows(McpClient.McpException.class, client::listTools);
                assertFalse(error.getMessage().contains(TOKEN));
                assertNull(error.getCause());
            }
        }
    }

    @Test public void processGlobalCookieHandlerIsNotInheritedOrModified() throws Exception {
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
