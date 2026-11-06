package io.github.miaiplus;

import android.app.KeyguardManager;
import android.content.ComponentName;
import android.content.Context;
import android.content.pm.PackageManager;
import android.os.SystemClock;
import android.os.UserManager;
import android.util.Base64;
import java.io.File;
import java.io.IOException;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.InetAddress;
import java.net.InetSocketAddress;
import java.net.Proxy;
import java.net.ServerSocket;
import java.net.Socket;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.SecureRandom;
import java.util.Arrays;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.locks.ReentrantLock;
import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import org.json.JSONException;
import org.json.JSONObject;

/** Managed Termux endpoint. Root only asks Android to start the fixed Termux service job. */
final class TermuxMcp {
    static final String PROTOCOL = "2025-06-18";
    static final Set<String> TOOLS = Set.of("run", "run_digest", "out_read");
    private static final String PACKAGE = "com.termux";
    private static final String PREFIX = "/data/data/com.termux/files/usr";
    private static final String SERVICE = PACKAGE + "/.app.TermuxService";
    private static final String ASSET = "termux-mcp/";
    private static final String LABEL = "Termux MCP";
    private static final SecureRandom RANDOM = new SecureRandom();
    private static final ReentrantLock STARTUP = new ReentrantLock();
    private static final int START_WAIT_MS = 9_000;
    private static final int START_COMMAND_MS = 12_000;
    private static final int SETUP_WAIT_MS = 180_000;

    private TermuxMcp() { }

    static final class Unavailable extends IOException {
        Unavailable(String message) { super(message); }
    }

    static boolean hasExpectedTools(List<PluginToolSpec> tools) {
        Set<String> names = new HashSet<>();
        for (PluginToolSpec tool : tools) {
            if (!PluginSettings.TERMUX_ID.equals(tool.pluginId()) || !names.add(tool.sourceName())) return false;
        }
        return names.equals(TOOLS);
    }

    static PluginSettings.Entry find(PluginSettings.Snapshot settings) {
        if (settings == null) return null;
        for (PluginSettings.Entry entry : settings.entries()) {
            if (PluginSettings.TERMUX_ID.equals(entry.id())) return entry;
        }
        return null;
    }

    static boolean installed(Context context) {
        try { context.getPackageManager().getApplicationInfo(PACKAGE, 0); return true; }
        catch (PackageManager.NameNotFoundException ignored) { return false; }
    }

    private static boolean packageStopped(Context context) throws PackageManager.NameNotFoundException {
        return (context.getPackageManager().getApplicationInfo(PACKAGE, 0).flags
            & android.content.pm.ApplicationInfo.FLAG_STOPPED) != 0;
    }

    static PluginSettings.Snapshot configure(Context context) throws Exception {
        int uid = verifyDevice(context);
        if (!STARTUP.tryLock()) throw new Unavailable("Termux MCP 正在启动，请稍候");
        try {
            PluginSettings.Snapshot snapshot = PluginSettings.load(context);
            PluginSettings.Entry previous = find(snapshot);
            PluginSettings.Entry connection = previous;
            if (connection == null) {
                int port;
                try (ServerSocket selection = bindLoopback()) { port = selection.getLocalPort(); }
                connection = entry(true, "http://127.0.0.1:" + port + "/mcp", randomHex(32), List.of());
            }
            if (!ready(connection, uid)) {
                byte[] payload = asset(context, "upstream.bin", 512 * 1024);
                String archiveHash = sha256(payload);
                byte[] launcher = asset(context, "launch.py", 16 * 1024);
                String version = version(archiveHash, launcher);
                try (Transfer transfer = new Transfer(payload)) {
                    String command = rootCommand(asset(context, "manage.sh", 16 * 1024), uid, port(connection), version,
                        transfer.url(), connection.bearerToken(), base64(launcher), archiveHash);
                    requireUnlocked(context);
                    boolean stopped = packageStopped(context);
                    root(stopped ? openTermuxCommand() : command, START_COMMAND_MS);
                    waitSetup(context, connection, uid, transfer, command, stopped);
                }
            }
            List<PluginToolSpec> tools;
            try (McpClient client = new McpClient(connection)) { tools = client.listTools(); }
            if (!hasExpectedTools(tools)) throw new Unavailable("Termux MCP 返回了意外的工具目录");
            return PluginSettings.saveTermux(context, snapshot.revision(),
                entry(connection.enabled(), connection.endpoint(), connection.bearerToken(), managedTools(tools)));
        } finally { STARTUP.unlock(); }
    }

    static void startManually(Context context) throws Exception {
        PluginSettings.Entry entry = find(PluginSettings.load(context));
        if (entry == null) throw new Unavailable("请先配置 Termux MCP");
        ensureReady(context, entry, true, () -> { });
    }

    /** Called before creating the MCP transport; never retries a model's tools/call. */
    static void ensureReady(Context context, PluginSettings.Entry entry, boolean manual, Runnable check) throws Exception {
        int uid = verifyDevice(context);
        check.run();
        if (ready(entry, uid)) return;
        if (!manual && !PluginSettings.termuxAutoStart(context))
            throw new Unavailable("Termux MCP 未运行，请在「插件 → Termux 配置」启动服务，或开启「按需启动 Termux」");
        if (packageStopped(context))
            throw new Unavailable("Termux 已被系统标为强行停止，请在「插件 → Termux 配置」重新配置以恢复");
        // A concurrent caller must allow the leader's launch command and readiness wait to finish.
        long lockDeadline = SystemClock.elapsedRealtime() + START_COMMAND_MS + START_WAIT_MS + 1_000;
        while (!STARTUP.tryLock(200, TimeUnit.MILLISECONDS)) {
            check.run();
            if (SystemClock.elapsedRealtime() >= lockDeadline)
                throw new Unavailable("Termux MCP 正在配置或启动，请稍候");
        }
        try {
            check.run();
            if (ready(entry, uid)) return;
            String archiveHash = sha256(asset(context, "upstream.bin", 512 * 1024));
            String version = version(archiveHash, asset(context, "launch.py", 16 * 1024));
            byte[] manager = asset(context, "manage.sh", 16 * 1024);
            check.run();
            if (!manual && !PluginSettings.termuxAutoStart(context))
                throw new Unavailable("Termux MCP 的按需启动已关闭，本次未执行");
            requireUnlocked(context);
            root(rootCommand(manager, uid, port(entry), version), START_COMMAND_MS);
            waitReady(entry, uid, START_WAIT_MS, check);
        } finally { STARTUP.unlock(); }
    }

    private static int verifyDevice(Context context) throws Unavailable {
        if (!android.os.Process.myUserHandle().equals(android.os.UserHandle.getUserHandleForUid(0)))
            throw new Unavailable("内置 Termux MCP 目前仅支持 Android 主用户");
        requireUnlocked(context);
        try {
            var service = context.getPackageManager().getServiceInfo(
                new ComponentName(PACKAGE, PACKAGE + ".app.TermuxService"), 0);
            if (!service.enabled || service.exported || !service.applicationInfo.enabled
                || !PACKAGE.equals(service.applicationInfo.packageName)
                || service.applicationInfo.uid < 10_000
                || !Set.of("/data/user/0/com.termux", "/data/data/com.termux").contains(service.applicationInfo.dataDir))
                throw new Unavailable("当前 Termux 版本不支持内置启动");
            return service.applicationInfo.uid;
        } catch (PackageManager.NameNotFoundException missing) {
            throw new Unavailable("请先安装兼容的 Termux");
        }
    }

    static void requireUnlocked(Context context) throws Unavailable {
        KeyguardManager keyguard = context.getSystemService(KeyguardManager.class);
        if (!context.getSystemService(UserManager.class).isUserUnlocked()
            || keyguard.isDeviceLocked() || keyguard.isKeyguardLocked())
            throw new Unavailable("请解锁手机后使用 Termux MCP");
    }

    private static PluginSettings.Entry entry(boolean enabled, String endpoint, String token, List<PluginToolSpec> tools) {
        return new PluginSettings.Entry(PluginSettings.TERMUX_ID, PluginSettings.TERMUX_KIND, LABEL,
            enabled, endpoint, token, PROTOCOL, tools);
    }

    private static List<PluginToolSpec> managedTools(List<PluginToolSpec> tools) throws JSONException {
        var result = new java.util.ArrayList<PluginToolSpec>(tools.size());
        for (PluginToolSpec tool : tools) {
            JSONObject schema = new JSONObject(tool.inputSchema());
            schema.getJSONObject("properties").remove("confirmed");
            schema.put("additionalProperties", false);
            result.add(new PluginToolSpec(tool.pluginId(), tool.kind(), tool.name(), tool.sourceName(),
                tool.displayName(), toolDescription(tool), schema.toString(), tool.timeoutMs()));
        }
        return List.copyOf(result);
    }

    static String toolDescription(PluginToolSpec tool) {
        return switch (tool.sourceName()) {
            case "run", "run_digest" -> "Run a non-interactive shell command as the Termux app. "
                + "Each call starts in the managed HOME. Pass paths directly, e.g. cat \"$HOME/file\"; "
                + "avoid a leading cd with a quoted or variable path. Independent commands can run in parallel; "
                + "wait for results before issuing dependent commands or modifying the same file. "
                + ("run_digest".equals(tool.sourceName())
                    ? "Prefer this tool for exit status, diagnostics and out_ref for large output. " : "")
                + "Inspect failures and adjust the plan; do not repeat a command with possible side effects. "
                + "If confirmation is required or policy blocks the command, stop and tell the user.";
            case "out_read" -> "Read more of a previous run_digest result by out_ref: grep, head, tail, "
                + "or lines 'a-b'. Wait for the producing command's result before using this tool.";
            default -> tool.description();
        };
    }

    static boolean acceptsArguments(PluginToolSpec tool, JSONObject arguments) throws JSONException {
        JSONObject schema = new JSONObject(tool.inputSchema());
        JSONObject properties = schema.optJSONObject("properties");
        for (var keys = arguments.keys(); keys.hasNext();) {
            String key = keys.next();
            if ("confirmed".equals(key) || properties == null || !properties.has(key)) return false;
        }
        return true;
    }

    private static int port(PluginSettings.Entry entry) { return URI.create(entry.endpoint()).getPort(); }

    private static void waitSetup(Context context, PluginSettings.Entry entry, int uid, Transfer transfer,
        String command, boolean openedForSetup) throws Exception {
        long started = SystemClock.elapsedRealtime();
        int attempt = 0;
        long[] bootstrapSteps = openedForSetup ? new long[]{3_000, 12_000, 25_000, 45_000}
            : new long[]{9_000, 15_000, 28_000, 46_000};
        while (SystemClock.elapsedRealtime() - started < SETUP_WAIT_MS) {
            requireUnlocked(context);
            if (ready(entry, uid)) return;
            // Android isolates app data mounts. Only Termux itself can reliably see its
            // bootstrap files; a fresh install may need its first Activity launch.
            if (!transfer.delivered && attempt < bootstrapSteps.length
                && SystemClock.elapsedRealtime() - started >= bootstrapSteps[attempt]) {
                root(attempt++ == 0 && !openedForSetup ? openTermuxCommand() : command, 12_000);
            }
            Thread.sleep(300);
        }
        throw new Unavailable("Termux MCP 配置未完成，请检查 Termux 初始化、Python 和软件源后重试");
    }

    private static String openTermuxCommand() {
        return "[ \"$(/system/bin/id -u)\" = 0 ] || exit 3; "
            + "exec /system/bin/am start --user 0 -n com.termux/.app.TermuxActivity";
    }

    private static void waitReady(PluginSettings.Entry entry, int uid, int budget, Runnable check) throws Exception {
        long deadline = SystemClock.elapsedRealtime() + budget;
        while (SystemClock.elapsedRealtime() < deadline) {
            check.run();
            if (ready(entry, uid)) return;
            Thread.sleep(240);
        }
        throw new Unavailable("Termux MCP 启动未就绪，请在「插件 → Termux 配置」重新配置或启动服务");
    }

    private static boolean ready(PluginSettings.Entry entry, int uid) {
        HttpURLConnection connection = null;
        try {
            String challenge = randomHex(16);
            var url = URI.create("http://127.0.0.1:" + port(entry) + "/miaiplus-ready/" + challenge).toURL();
            connection = (HttpURLConnection) url.openConnection(Proxy.NO_PROXY);
            connection.setInstanceFollowRedirects(false);
            connection.setConnectTimeout(320);
            connection.setReadTimeout(480);
            if (connection.getResponseCode() != 200 || connection.getContentLength() != 64) return false;
            try (InputStream input = connection.getInputStream()) {
                return MessageDigest.isEqual(input.readNBytes(64), proof(entry.bearerToken(), uid, challenge));
            }
        } catch (Exception unavailable) { return false; }
        finally { if (connection != null) connection.disconnect(); }
    }

    static byte[] proof(String token, int uid, String challenge) throws Exception {
        Mac mac = Mac.getInstance("HmacSHA256");
        mac.init(new SecretKeySpec(token.getBytes(StandardCharsets.US_ASCII), "HmacSHA256"));
        byte[] result = mac.doFinal(("MIAIPLUS_TERMUX_1:" + uid + ":" + challenge).getBytes(StandardCharsets.US_ASCII));
        return hex(result).getBytes(StandardCharsets.US_ASCII);
    }

    /** No caller arguments are used. The only variable fields are generated setup data or OS identity. */
    static String rootCommand(byte[] manager, int uid, int port, String version, String... setup) {
        if (uid < 10_000 || uid >= 100_000 || port <= 0 || port > 65535 || !version.matches("[0-9a-f]{64}")
            || (setup.length != 0 && (setup.length != 4
                || !setup[0].matches("http://127\\.0\\.0\\.1:[1-9][0-9]{0,4}/[0-9a-f]{64}")
                || !setup[1].matches("[0-9a-f]{64}") || !setup[2].matches("[A-Za-z0-9+/]+={0,2}")
                || !setup[3].matches("[0-9a-f]{64}"))))
            throw new IllegalArgumentException("Termux 启动参数无效");
        String app = BuildConfig.APPLICATION_ID;
        if (!app.matches("[A-Za-z0-9_.]+")) throw new IllegalStateException("应用身份无效");
        String mode = setup.length == 0 ? "start" : "setup";
        String command = "printf %s " + base64(manager) + " | " + PREFIX + "/bin/base64 -d | "
            + PREFIX + "/bin/bash -s -- " + mode + " " + app + " " + port + " " + uid + " " + version;
        if (setup.length != 0) command += " " + String.join(" ", setup);
        String bash = PREFIX + "/bin/bash";
        String launch = "/system/bin/timeout 3 /system/bin/am start-foreground-service --user 0 -n " + SERVICE
            + " -a com.termux.service_execute -d com.termux.file:" + bash
            + " --ez com.termux.execute.background true --es com.termux.execute.label 'MiAIPlus Termux MCP'"
            + " --esa com.termux.execute.arguments '-c," + command + "'";
        // HyperOS can reject a cold FGS even when the caller is root. Limit its
        // auto-start exception to this single start request and restore the previous mode.
        return "[ \"$(/system/bin/id -u)\" = 0 ] || exit 3; "
            + "start_termux() { " + launch + "; }; start_termux && exit 0; "
            + "[ -n \"$(/system/bin/getprop ro.miui.ui.version.name)\" ] || exit 5; "
            + "get_mode() { /system/bin/cmd appops get --user 0 com.termux 10008 | "
            + "/system/bin/sed -n 's/^MIUIOP(10008): \\([a-z]*\\).*$/\\1/p'; }; "
            + "saved=$(get_mode); case \"$saved\" in ignore|deny|default|foreground) ;; *) exit 5;; esac; "
            + "restore() { [ \"$(get_mode)\" != allow ] || "
            + "/system/bin/cmd appops set --user 0 com.termux 10008 \"$saved\"; }; "
            + "trap restore EXIT; trap 'exit 5' HUP INT TERM; "
            + "/system/bin/cmd appops set --user 0 com.termux 10008 allow && start_termux";
    }

    private static void root(String command, int timeout) throws Exception {
        Process process;
        try {
            process = new ProcessBuilder("su", "-c", command).redirectErrorStream(true)
                .redirectOutput(new File("/dev/null")).start();
        } catch (IOException unavailable) {
            throw new Unavailable("请在 root 管理器中授权 MiAIPlus");
        }
        try {
            if (!process.waitFor(timeout, TimeUnit.MILLISECONDS))
                throw new Unavailable("Termux 启动超时，请检查 MiAIPlus 的 root 授权和 Termux 初始化");
            if (process.exitValue() != 0) throw new Unavailable("无法启动 Termux 后台服务，请检查 root 授权及 Termux 版本");
        } finally { if (process.isAlive()) process.destroyForcibly(); }
    }

    private static byte[] asset(Context context, String name, int limit) throws IOException {
        try (InputStream input = context.getAssets().open(ASSET + name)) {
            byte[] value = input.readNBytes(limit + 1);
            if (value.length == 0 || value.length > limit) throw new IOException("Invalid Termux MCP asset size");
            return value;
        }
    }

    private static String version(String archiveHash, byte[] launcher) throws Exception {
        return sha256((archiveHash + sha256(launcher)).getBytes(StandardCharsets.US_ASCII));
    }

    private static String sha256(byte[] data) throws Exception { return hex(MessageDigest.getInstance("SHA-256").digest(data)); }
    private static String base64(byte[] data) { return Base64.encodeToString(data, Base64.NO_WRAP); }
    private static String randomHex(int size) { byte[] data = new byte[size]; RANDOM.nextBytes(data); return hex(data); }

    private static String hex(byte[] data) {
        StringBuilder out = new StringBuilder(data.length * 2);
        for (byte value : data) out.append(Character.forDigit((value & 255) >>> 4, 16))
            .append(Character.forDigit(value & 15, 16));
        return out.toString();
    }

    private static ServerSocket bindLoopback() throws IOException {
        ServerSocket socket = new ServerSocket();
        try { socket.bind(new InetSocketAddress(InetAddress.getByName("127.0.0.1"), 0), 2); return socket; }
        catch (IOException failure) { socket.close(); throw failure; }
    }

    /** Short-lived, one-shot transfer of the public vendored archive. No credentials are served. */
    static final class Transfer implements AutoCloseable {
        private final ServerSocket server = bindLoopback();
        private final String path = "/" + randomHex(32);
        private volatile Socket active;
        private volatile boolean delivered;

        Transfer(byte[] data) throws IOException {
            Thread worker = new Thread(() -> serve(data), "miaiplus-termux-setup");
            worker.setDaemon(true);
            worker.start();
        }

        String url() { return "http://127.0.0.1:" + server.getLocalPort() + path; }

        private void serve(byte[] data) {
            while (!server.isClosed()) {
                try (Socket socket = server.accept()) {
                    active = socket;
                    if (server.isClosed()) return;
                    socket.setSoTimeout(1500);
                    byte[] header = new byte[4096];
                    InputStream in = socket.getInputStream();
                    int length = 0;
                    long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(2);
                    boolean complete = false;
                    while (length < header.length && System.nanoTime() < deadline) {
                        int value = in.read();
                        if (value == -1) break;
                        header[length++] = (byte) value;
                        if (length >= 4 && header[length - 4] == '\r' && header[length - 3] == '\n'
                            && header[length - 2] == '\r' && header[length - 1] == '\n') {
                            complete = true;
                            break;
                        }
                    }
                    if (!complete) continue;
                    int lineEnd = 0;
                    while (lineEnd < length && header[lineEnd] != '\r') lineEnd++;
                    String first = new String(header, 0, lineEnd, StandardCharsets.US_ASCII);
                    boolean matches = Arrays.asList("GET " + path + " HTTP/1.1", "GET " + path + " HTTP/1.0").contains(first);
                    String response = matches ? "HTTP/1.1 200 OK\r\nContent-Length: " + data.length
                        : "HTTP/1.1 404 Not Found\r\nContent-Length: 0";
                    socket.getOutputStream().write((response + "\r\nConnection: close\r\n\r\n").getBytes(StandardCharsets.US_ASCII));
                    if (matches) {
                        socket.getOutputStream().write(data);
                        delivered = true;
                        close();
                        return;
                    }
                } catch (IOException ignored) {
                    // A bad or abandoned connection cannot stop a pending setup.
                } finally { active = null; }
            }
        }

        @Override public void close() throws IOException {
            try { server.close(); }
            finally { Socket current = active; if (current != null) current.close(); }
        }
    }
}
