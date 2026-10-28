package io.github.miaiplus;

import java.io.BufferedInputStream;
import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.CookieHandler;
import java.net.URL;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CancellationException;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.ScheduledThreadPoolExecutor;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Bounded tools-only client for the explicitly pinned, initialization-based MCP revision. */
public final class McpClient implements AutoCloseable {
    static final int MAX_RESPONSE_BYTES = 256 * 1024;
    static final int MAX_REQUEST_BYTES = 128 * 1024;
    static final int MAX_TOOLS = 64;
    static final int MAX_PAGES = 64;
    static final long BUDGET_MS = 60_000;
    private static final int IO_TIMEOUT_MS = 15_000;
    private final PluginSettings.Entry entry;
    private final ConnectionFactory connections;
    private final LongSupplier clock;
    private final AtomicBoolean cancelled = new AtomicBoolean();
    private final AtomicBoolean expired = new AtomicBoolean();
    private volatile HttpURLConnection connection;
    private volatile ScheduledFuture<?> timeoutTask;
    private long deadline;
    private boolean started, initialized;
    private String sessionId;

    @FunctionalInterface interface ConnectionFactory {
        HttpURLConnection open(URL url) throws IOException;
    }

    public McpClient(PluginSettings.Entry entry) {
        this(entry, url -> (HttpURLConnection) url.openConnection(), () -> System.nanoTime() / 1_000_000L);
    }

    McpClient(PluginSettings.Entry entry, ConnectionFactory connections, LongSupplier clock) {
        if (!"mcp".equals(entry.kind()) || !PluginSettings.supportsMcpProtocol(entry.protocolVersion()))
            throw new IllegalArgumentException("不支持的 MCP 插件配置");
        this.entry = entry;
        this.connections = connections;
        this.clock = clock;
    }

    public synchronized List<PluginToolSpec> listTools() throws Exception {
        try { return discoverTools(); }
        catch (CancellationException | McpException error) { throw error; }
        catch (Exception error) { throw malformed(); }
    }

    private List<PluginToolSpec> discoverTools() throws Exception {
        initialize();
        ArrayList<PluginToolSpec> tools = new ArrayList<>();
        HashSet<String> names = new HashSet<>(), cursors = new HashSet<>();
        String cursor = null;
        for (int page = 0; page < MAX_PAGES; page++) {
            JSONObject params = new JSONObject();
            if (cursor != null) params.put("cursor", cursor);
            JSONObject result = rpc("tools/list", params);
            JSONArray items = result.getJSONArray("tools");
            if (items.length() > MAX_TOOLS - tools.size()) throw failure("MCP 工具数量超过 64 个上限");
            for (int i = 0; i < items.length(); i++) {
                JSONObject item = items.getJSONObject(i);
                String name = string(item, "name");
                if (!names.add(name)) throw failure("MCP 返回了重复工具名称");
                String title = item.has("title") ? string(item, "title") : name;
                String description = item.has("description") ? string(item, "description") : "";
                JSONObject schema = item.getJSONObject("inputSchema");
                PluginToolSpec tool = new PluginToolSpec(entry.id(), "mcp", PluginToolSpec.mcpName(entry.id(), name),
                    name, title, description, schema.toString(), BUDGET_MS);
                rejectCredentialEcho(tool);
                tools.add(tool);
            }
            if (!result.has("nextCursor")) return List.copyOf(tools);
            cursor = string(result, "nextCursor");
            if (cursor.isEmpty() || cursor.length() > 2048 || !cursors.add(cursor))
                throw failure("MCP 工具分页无效或重复");
        }
        throw failure("MCP 工具分页超过上限");
    }

    public synchronized String callTool(String sourceName, JSONObject arguments) throws Exception {
        try { return executeTool(sourceName, arguments); }
        catch (CancellationException | McpException error) { throw error; }
        catch (Exception error) { throw malformed(); }
    }

    private String executeTool(String sourceName, JSONObject arguments) throws Exception {
        PluginToolSpec.requireText(sourceName, 128, "MCP 来源工具名称无效");
        if (arguments == null) throw failure("MCP 工具参数必须为对象");
        initialize();
        // A tools/call is sent at most once. In particular, no session/auth/connection failure replays it.
        JSONObject result = rpc("tools/call", new JSONObject().put("name", sourceName).put("arguments", arguments));
        if (result.has("isError") && !(result.get("isError") instanceof Boolean)) throw malformed();
        if (result.optBoolean("isError", false))
            throw new ToolException("MCP 工具报告执行失败；远端可能已产生副作用，请勿自动重试");
        StringBuilder text = new StringBuilder();
        if (result.has("content")) {
            JSONArray content = result.getJSONArray("content");
            for (int i = 0; i < content.length(); i++) {
                JSONObject block = content.getJSONObject(i);
                if (!"text".equals(block.opt("type")))
                    throw failure("MCP 返回了本版本不支持的非文本内容；远端可能已执行，请勿自动重试");
                if (text.length() > 0) text.append('\n');
                text.append(string(block, "text"));
            }
        }
        if (result.has("structuredContent")) {
            JSONObject structured = result.getJSONObject("structuredContent");
            if (text.length() > 0) text.append('\n');
            text.append(structured);
        }
        if (!result.has("content") && !result.has("structuredContent")) throw malformed();
        return text.length() == 0 ? "[MCP 工具返回空内容]" : text.toString();
    }

    private void initialize() throws Exception {
        check();
        if (initialized) return;
        JSONObject result = rpc("initialize", new JSONObject().put("protocolVersion", entry.protocolVersion())
            .put("capabilities", new JSONObject())
            .put("clientInfo", new JSONObject().put("name", "MiAIPlus").put("version", BuildConfig.VERSION_NAME)));
        if (!entry.protocolVersion().equals(result.opt("protocolVersion")))
            throw failure("MCP 服务不支持插件所选的 " + entry.protocolVersion() + " 协议；请检查配置，不会自动降级");
        if (!(result.getJSONObject("capabilities").opt("tools") instanceof JSONObject))
            throw failure("MCP 服务未声明 tools 能力");
        JSONObject serverInfo = result.getJSONObject("serverInfo");
        string(serverInfo, "name");
        string(serverInfo, "version");
        JSONObject notification = new JSONObject().put("jsonrpc", "2.0").put("method", "notifications/initialized");
        exchange(notification, null);
        initialized = true;
    }

    private JSONObject rpc(String method, JSONObject params) throws Exception {
        String id = UUID.randomUUID().toString();
        JSONObject message = new JSONObject().put("jsonrpc", "2.0").put("id", id)
            .put("method", method).put("params", params);
        JSONObject envelope = exchange(message, id);
        if (envelope.has("error")) throw failure("MCP 协议请求被服务拒绝；请检查插件配置，不要自动重试工具调用");
        if (!(envelope.opt("result") instanceof JSONObject result)) throw malformed();
        return result;
    }

    private JSONObject exchange(JSONObject message, String id) throws Exception {
        check();
        byte[] request = message.toString().getBytes(StandardCharsets.UTF_8);
        if (request.length > MAX_REQUEST_BYTES) throw failure("MCP 请求超过大小上限");
        // Android exposes a public getter for CookieHandler, but not for Authenticator. Never
        // inspect hidden fields or mutate another component's process-global authentication state.
        if (CookieHandler.getDefault() != null)
            throw failure("MCP 请求已停止：当前进程存在全局 Cookie 处理器");
        HttpURLConnection current = null;
        try {
            current = connections.open(new URL(entry.endpoint()));
            connection = current;
            check();
            current.setInstanceFollowRedirects(false);
            current.setAllowUserInteraction(false);
            current.setUseCaches(false);
            current.setConnectTimeout(timeout());
            current.setReadTimeout(timeout());
            current.setRequestMethod("POST");
            current.setDoOutput(true);
            current.setRequestProperty("Content-Type", "application/json; charset=utf-8");
            current.setRequestProperty("Accept", "application/json, text/event-stream");
            current.setRequestProperty("Accept-Encoding", "identity");
            current.setRequestProperty("User-Agent", "MiAIPlus/" + BuildConfig.VERSION_NAME);
            if (!"initialize".equals(message.opt("method")))
                current.setRequestProperty("MCP-Protocol-Version", entry.protocolVersion());
            if (sessionId != null && !"initialize".equals(message.opt("method")))
                current.setRequestProperty("MCP-Session-Id", sessionId);
            if (!entry.bearerToken().isEmpty())
                current.setRequestProperty("Authorization", "Bearer " + entry.bearerToken());
            current.setFixedLengthStreamingMode(request.length);
            try (var output = current.getOutputStream()) { check(); output.write(request); }
            check();
            int status = current.getResponseCode();
            check();
            if (status >= 300 && status < 400) throw failure("MCP 不接受重定向，请填写最终服务地址");
            if (status == 401 || status == 403) throw failure("MCP 认证失败，请在插件设置中更新凭证");
            if (status < 200 || status >= 300) throw failure("MCP 服务返回 HTTP 错误；工具调用不会自动重试");
            String encoding = current.getHeaderField("Content-Encoding");
            if (encoding != null && !encoding.isBlank() && !"identity".equalsIgnoreCase(encoding))
                throw failure("MCP 返回了不支持的内容编码");
            String session = current.getHeaderField("MCP-Session-Id");
            if (session != null) {
                validateSession(session);
                if (!"initialize".equals(message.opt("method")) && !session.equals(sessionId))
                    throw failure("MCP 服务在请求期间更改了会话身份");
            }
            try (InputStream input = current.getInputStream()) {
                BoundedInput body = new BoundedInput(input);
                if (id == null) {
                    if (status != 202 || body.read() != -1) throw malformed();
                    return null;
                }
                if (status != 200) throw malformed();
                String type = current.getContentType();
                if (type == null) throw malformed();
                type = type.split(";", 2)[0].trim();
                JSONObject envelope;
                if ("application/json".equalsIgnoreCase(type))
                    envelope = envelope(readJson(body), id);
                else if ("text/event-stream".equalsIgnoreCase(type))
                    envelope = readEvents(body, id);
                else throw failure("MCP 返回了不支持的响应格式");
                if ("initialize".equals(message.opt("method"))) sessionId = session;
                return envelope;
            }
        } catch (CancellationException error) {
            throw error;
        } catch (ToolException error) {
            throw error;
        } catch (McpException error) {
            throw error;
        } catch (Exception error) {
            check();
            // Neither response bodies, URLs nor credentials may escape through exception messages or causes.
            throw failure(error instanceof JSONException ? "MCP 返回的数据格式无效" : "MCP 连接失败或超时；工具调用不会自动重试");
        } finally {
            disconnect(current);
            connection = null;
        }
    }

    private JSONObject readJson(BoundedInput input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        byte[] buffer = new byte[4096];
        int count;
        while ((count = input.read(buffer)) >= 0) {
            if (count > 0) bytes.write(buffer, 0, count);
        }
        return parse(new String(bytes.toByteArray(), StandardCharsets.UTF_8));
    }

    private JSONObject readEvents(BoundedInput input, String id) throws Exception {
        StringBuilder data = new StringBuilder();
        boolean firstLine = true;
        String line;
        while ((line = line(input)) != null) {
            if (firstLine && line.startsWith("\uFEFF")) line = line.substring(1);
            firstLine = false;
            if (line.isEmpty()) {
                if (data.length() == 0) continue;
                JSONObject event = parse(data.toString());
                data.setLength(0);
                if (event.has("method")) {
                    if (event.has("id")) throw failure("MCP 请求了本版本未提供的客户端能力");
                    if (!"2.0".equals(event.opt("jsonrpc")) || !(event.opt("method") instanceof String)
                        || event.has("result") || event.has("error")) throw malformed();
                    continue;
                }
                return envelope(event, id);
            }
            if (line.startsWith(":")) continue;
            int colon = line.indexOf(':');
            String field = colon < 0 ? line : line.substring(0, colon);
            if (!"data".equals(field)) continue;
            String value = colon < 0 ? "" : line.substring(colon + 1);
            if (value.startsWith(" ")) value = value.substring(1);
            // Empty SSE priming data carries no JSON-RPC message.
            if (data.length() > 0) data.append('\n');
            data.append(value);
        }
        // A partial event or an EOF before the matching response is not a successful tool result.
        throw failure("MCP 流在完整结果前断开；远端可能已执行，请勿自动重试");
    }

    private String line(BoundedInput input) throws Exception {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        int value;
        while ((value = input.read()) != -1) {
            if (input.skipLf) {
                input.skipLf = false;
                if (value == '\n') continue;
            }
            if (value == '\n') break;
            if (value == '\r') {
                // CR already ends the line; do not block a complete event on another read.
                input.skipLf = true;
                break;
            }
            bytes.write(value);
        }
        if (value < 0 && bytes.size() == 0) return null;
        byte[] line = bytes.toByteArray();
        int length = line.length;
        if (length > 0 && line[length - 1] == '\r') length--;
        return new String(line, 0, length, StandardCharsets.UTF_8);
    }

    private static JSONObject parse(String text) throws Exception {
        // Bound recursive parsing before passing untrusted responses to Android's JSON parser.
        PluginToolSpec.validateNesting(text, 64);
        return PluginToolSpec.parseObject(text);
    }

    private void rejectCredentialEcho(PluginToolSpec tool) throws Exception {
        String token = entry.bearerToken();
        if (token.isEmpty()) return;
        String quoted = JSONObject.quote(token);
        String escaped = quoted.substring(1, quoted.length() - 1);
        for (String text : new String[]{tool.sourceName(), tool.displayName(), tool.description(), tool.inputSchema()}) {
            if (text.contains(token) || text.contains(escaped))
                throw failure("MCP 工具定义包含认证信息，已拒绝导入");
        }
    }

    private static JSONObject envelope(JSONObject value, String id) throws IOException {
        if (!"2.0".equals(value.opt("jsonrpc")) || !id.equals(value.opt("id"))
            || value.has("method") || value.has("result") == value.has("error")) throw malformed();
        if (value.has("error") && !(value.opt("error") instanceof JSONObject)) throw malformed();
        return value;
    }

    private static String string(JSONObject value, String key) throws Exception {
        Object field = value.get(key);
        if (!(field instanceof String text)) throw malformed();
        return text;
    }

    private static void validateSession(String session) throws IOException {
        if (session.isEmpty() || session.length() > 256) throw failure("MCP 会话身份格式无效");
        for (int i = 0; i < session.length(); i++) {
            char value = session.charAt(i);
            if (value < 0x21 || value > 0x7e) throw failure("MCP 会话身份格式无效");
        }
    }

    private void check() throws IOException {
        if (expired.get()) throw failure("MCP 请求超过 60 秒时限；远端可能已执行，请勿自动重试");
        if (cancelled.get() || Thread.currentThread().isInterrupted())
            throw new CancellationException("MCP 请求已停止；远端可能已执行，请勿自动重试");
        long now = clock.getAsLong();
        if (!started) {
            deadline = now + BUDGET_MS;
            started = true;
            timeoutTask = DeadlineScheduler.EXECUTOR.schedule(() -> {
                expired.set(true);
                HttpURLConnection active = connection;
                disconnect(active);
            }, BUDGET_MS, TimeUnit.MILLISECONDS);
            if (cancelled.get()) timeoutTask.cancel(false);
        }
        if (now >= deadline) throw failure("MCP 请求超过 60 秒时限；远端可能已执行，请勿自动重试");
    }

    private int timeout() throws IOException {
        check();
        return (int) Math.max(1, Math.min(IO_TIMEOUT_MS, deadline - clock.getAsLong()));
    }

    /** No retry, DELETE, or other new network operation is started while cancelling/closing. */
    public void cancel() {
        cancelled.set(true);
        ScheduledFuture<?> task = timeoutTask;
        if (task != null) task.cancel(false);
        HttpURLConnection active = connection;
        disconnect(active);
    }

    @Override public void close() { cancel(); }

    private static McpException failure(String message) { return new McpException(message); }
    private static McpException malformed() { return failure("MCP 返回的数据格式无效"); }

    private static void disconnect(HttpURLConnection connection) {
        if (connection == null) return;
        try { connection.disconnect(); }
        catch (RuntimeException ignored) { /* Cleanup must not expose an implementation's exception body. */ }
    }

    public static class McpException extends IOException {
        McpException(String message) { super(message); }
    }

    public static final class ToolException extends McpException {
        ToolException(String message) { super(message); }
    }

    private final class BoundedInput {
        private final InputStream input;
        private int count;
        private boolean skipLf;

        BoundedInput(InputStream input) { this.input = new BufferedInputStream(input, 4096); }

        int read() throws IOException {
            check();
            int value = input.read();
            check();
            if (value >= 0 && ++count > MAX_RESPONSE_BYTES) throw failure("MCP 响应超过大小上限");
            return value;
        }

        int read(byte[] buffer) throws IOException {
            check();
            int size = input.read(buffer, 0, Math.min(buffer.length, MAX_RESPONSE_BYTES - count + 1));
            check();
            if (size > 0 && (count += size) > MAX_RESPONSE_BYTES) throw failure("MCP 响应超过大小上限");
            return size;
        }
    }

    private static final class DeadlineScheduler {
        static final ScheduledThreadPoolExecutor EXECUTOR;
        static {
            EXECUTOR = new ScheduledThreadPoolExecutor(1, task -> {
                Thread thread = new Thread(task, "miaiplus-mcp-deadline");
                thread.setDaemon(true);
                return thread;
            });
            EXECUTOR.setRemoveOnCancelPolicy(true);
        }
    }
}
