package io.github.miaiplus;

import android.content.Context;
import android.os.Bundle;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.ScheduledFuture;
import java.util.concurrent.TimeUnit;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Module-owned catalog and bounded remote executions, independent of the screen-task executor. */
public final class PluginRuntime {
    static final int MAX_TOOLS = 128;
    static final int MAX_CATALOG_CHARS = 96 * 1024;
    static final int MAX_ARGUMENT_CHARS = 32 * 1024;
    static final int MAX_RESULT_CHARS = 64 * 1024;
    private static final int MAX_RUNNING = 2;
    private static final int MAX_JOBS = 16;
    private static final long CALL_BUDGET_MS = 62_000;
    private static final long RETAIN_MS = 120_000;
    private static final Object LOCK = new Object();
    private static final Map<String, Job> JOBS = new LinkedHashMap<>();
    private static final ExecutorService WORKER = Executors.newFixedThreadPool(MAX_RUNNING);
    private static final ScheduledExecutorService WATCHDOG = Executors.newSingleThreadScheduledExecutor();

    /** A backend owns its transport; neither host reflection nor screen privileges enter this contract. */
    interface Client extends AutoCloseable {
        List<PluginToolSpec> listTools() throws Exception;
        String callTool(String sourceName, JSONObject arguments) throws Exception;
        void cancel();
        @Override void close();
    }

    @FunctionalInterface interface ClientFactory { Client open(PluginSettings.Entry entry); }
    static ClientFactory clients = entry -> new Client() {
        private final McpClient client = new McpClient(entry);
        @Override public List<PluginToolSpec> listTools() throws Exception { return client.listTools(); }
        @Override public String callTool(String sourceName, JSONObject arguments) throws Exception {
            return client.callTool(sourceName, arguments);
        }
        @Override public void cancel() { client.cancel(); }
        @Override public void close() { client.close(); }
    };

    private record Catalog(String revision, List<PluginToolSpec> tools, String encoded) { }

    private PluginRuntime() { }

    public static Bundle catalog(Context context) {
        Catalog catalog = buildCatalog(PluginSettings.load(context), new Config(context));
        Bundle result = new Bundle();
        result.putString("revision", catalog.revision());
        result.putString("tools", catalog.encoded());
        return result;
    }

    private static Catalog buildCatalog(PluginSettings.Snapshot settings, Config config) {
        List<PluginToolSpec> tools = new ArrayList<>();
        Map<String, PluginToolSpec> builtin = new LinkedHashMap<>();
        for (PluginToolSpec tool : PhoneUsePlugin.specs()) builtin.put(tool.pluginId(), tool);
        for (PluginSettings.Entry entry : settings.entries()) {
            if (!entry.enabled()) continue;
            if ("builtin".equals(entry.kind())) {
                if (config.enabled && "native".equals(config.planner)) tools.add(builtin.get(entry.id()));
            } else tools.addAll(entry.tools());
        }
        validateCatalogSize(tools);
        JSONArray encoded = new JSONArray();
        for (PluginToolSpec tool : tools) {
            try { encoded.put(tool.toJson().put("pluginLabel", settings.find(tool.pluginId()).label())); }
            catch (JSONException invalid) { throw new IllegalStateException("插件目录无法编码"); }
        }
        String value = encoded.toString();
        if (value.length() > MAX_CATALOG_CHARS)
            throw new IllegalStateException("启用的插件目录过大，请减少工具或停用部分插件");
        // Legacy Phone Use settings also invalidate host proxies, without exporting model configuration.
        String revision = settings.revision() + ":" + config.enabled + ":" + "native".equals(config.planner);
        return new Catalog(revision, List.copyOf(tools), value);
    }

    static void validateCatalogSize(List<PluginToolSpec> tools) {
        if (tools.size() > MAX_TOOLS) throw new IllegalArgumentException("启用的插件工具超过 128 个上限");
        java.util.HashSet<String> names = new java.util.HashSet<>();
        int chars = 2;
        for (PluginToolSpec tool : tools) {
            if (tool == null || !names.add(tool.name())) throw new IllegalArgumentException("插件工具身份重复或无效");
            // An 80-character label may need two JSON characters per escaped quote/backslash.
            chars += tool.toJson().toString().length() + 192;
            if (chars > MAX_CATALOG_CHARS) throw new IllegalArgumentException("插件工具目录过大，请减少工具");
        }
    }

    /** Explicit settings action only. Saving a URL never performs network discovery. */
    public static PluginSettings.Snapshot refresh(Context context, String id, String expectedRevision) throws Exception {
        PluginSettings.Snapshot previous = PluginSettings.load(context);
        if (!previous.revision().equals(expectedRevision)) throw new IllegalStateException("插件配置已更改，请重新刷新");
        PluginSettings.Entry entry = previous.find(id);
        if (!"mcp".equals(entry.kind())) throw new IllegalArgumentException("内置插件无需发现工具");
        List<PluginToolSpec> discovered;
        try (Client client = clients.open(entry)) { discovered = List.copyOf(client.listTools()); }
        // The atomic commit validates the final enabled catalog. Discovering an inactive
        // plugin may cache its manifest without consuming the current enabled-tool budget.
        return PluginSettings.updateTools(context, id, expectedRevision, discovered);
    }

    static Bundle start(Context context, int owner, Bundle request) {
        try {
            if (request == null) return error("插件调用参数缺失");
            Catalog catalog = buildCatalog(PluginSettings.load(context), new Config(context));
            if (!catalog.revision().equals(request.getString("revision"))) return error("插件配置已更改，请重新规划；本次未执行");
            String pluginId = request.getString("pluginId"), name = request.getString("toolName");
            PluginToolSpec selected = null;
            for (PluginToolSpec spec : catalog.tools()) {
                if (spec.pluginId().equals(pluginId) && spec.name().equals(name)) { selected = spec; break; }
            }
            if (selected == null || !"mcp".equals(selected.kind())) return error("插件工具不存在或已停用；本次未执行");
            String encoded = request.getString("arguments", "");
            if (encoded.length() > MAX_ARGUMENT_CHARS) return error("插件工具参数过长；本次未执行");
            PluginToolSpec.validateNesting(encoded, 48);
            JSONObject arguments = PluginToolSpec.parseObject(encoded);
            PluginSettings.Snapshot settings = PluginSettings.load(context);
            if (!catalog.revision().equals(buildCatalog(settings, new Config(context)).revision()))
                return error("插件配置已更改；本次未执行");
            PluginSettings.Entry entry = settings.find(pluginId);
            Job job = new Job(owner, catalog.revision(), entry, selected, arguments);
            synchronized (LOCK) {
                long now = now();
                JOBS.values().removeIf(old -> old.finished && now - old.finishedAt > RETAIN_MS);
                if (JOBS.values().stream().filter(old -> !old.finished).count() >= MAX_RUNNING)
                    return error("已有插件工具执行中，请等待完成");
                while (JOBS.size() >= MAX_JOBS) {
                    String oldest = JOBS.entrySet().stream().filter(item -> item.getValue().finished)
                        .map(Map.Entry::getKey).findFirst().orElse(null);
                    if (oldest == null) return error("插件任务队列已满");
                    JOBS.remove(oldest);
                }
                JOBS.put(job.id, job);
                job.watchdog = WATCHDOG.schedule(() -> job.cancel("插件工具调用超时，已请求取消；远端可能已执行，请勿自动重试"),
                    CALL_BUDGET_MS, TimeUnit.MILLISECONDS);
                try { WORKER.execute(() -> execute(context.getApplicationContext(), job)); }
                catch (RuntimeException rejected) {
                    job.cancel("插件执行器不可用；本次未执行");
                    job.watchdog.cancel(false);
                    JOBS.remove(job.id);
                    return error("插件执行器不可用；本次未执行");
                }
            }
            Bundle result = new Bundle(); result.putString("id", job.id); return result;
        } catch (JSONException | RuntimeException invalid) { return error("插件配置或调用参数无效；本次未执行"); }
    }

    private static void execute(Context context, Job job) {
        Client client = null;
        try {
            ensureCurrent(context, job);
            client = clients.open(job.entry);
            synchronized (job) {
                if (job.cancelled) return;
                job.client = client;
            }
            ensureCurrent(context, job);
            String text = client.callTool(job.spec.sourceName(), job.arguments);
            ensureCurrent(context, job);
            if (text == null || text.length() > MAX_RESULT_CHARS)
                throw new IllegalStateException("插件结果超出大小限制");
            text = remoteText(text, job.entry.bearerToken());
            synchronized (job) {
                if (!job.cancelled) { job.result = text; job.status = "done"; }
            }
        } catch (Exception | LinkageError failure) {
            job.cancel("插件工具失败、已停用或配置已更改；远端可能已执行，请勿自动重试");
        } finally {
            try {
                if (client != null) client.close();
            } catch (RuntimeException | LinkageError cleanupFailure) {
                job.cancel("插件执行器清理异常；远端可能已执行，请勿自动重试");
            } finally {
                synchronized (job) {
                    // An exited backend must not leave a permanent "running" status, even after Error.
                    if ("running".equals(job.status)) {
                        job.cancelled = true; job.status = "error";
                        job.error = "插件执行器已异常退出；远端可能已执行，请勿自动重试";
                        job.result = "";
                    }
                    job.client = null;
                    if (job.watchdog != null) job.watchdog.cancel(false);
                    job.finishedAt = now();
                    job.finished = true;
                }
            }
        }
    }

    private static void ensureCurrent(Context context, Job job) {
        synchronized (job) {
            if (job.cancelled || now() >= job.deadline || Thread.currentThread().isInterrupted())
                throw new IllegalStateException("插件请求已停止");
        }
        if (!currentExecution(context, job))
            throw new IllegalStateException("插件配置已更改");
    }

    private static boolean currentExecution(Context context, Job job) {
        PluginSettings.Entry entry = PluginSettings.load(context).find(job.entry.id());
        // A catalog revision guards admission. An accepted remote invocation belongs only to its
        // own connection and tool contract: unrelated plugins/Phone Use settings cannot cancel it.
        return entry.enabled() && entry.kind().equals(job.entry.kind())
            && entry.endpoint().equals(job.entry.endpoint()) && entry.bearerToken().equals(job.entry.bearerToken())
            && entry.protocolVersion().equals(job.entry.protocolVersion()) && entry.tools().contains(job.spec);
    }

    static Bundle poll(Context context, int owner, String id) {
        Job job = owned(owner, id);
        if (job == null) return error("插件任务不存在或不属于本次调用");
        try {
            if (!currentExecution(context, job)) {
                job.cancel("插件配置已更改，已请求取消；远端可能已执行，请勿自动重试");
                return error("插件配置已更改，结果不再有效；远端可能已执行，请勿自动重试");
            }
        } catch (RuntimeException unreadable) {
            job.cancel("插件配置不可用，已请求取消；请勿自动重试");
            return error("插件配置不可用，结果不再有效；请勿自动重试");
        }
        if (now() >= job.deadline && !job.finished) job.cancel("插件工具调用超时，已请求取消；请勿自动重试");
        synchronized (job) {
            Bundle result = new Bundle();
            result.putString("status", job.status);
            if ("done".equals(job.status)) result.putString("result", job.result);
            if ("error".equals(job.status)) { result.putString("error", job.error); result.putString("result", job.error); }
            return result;
        }
    }

    static Bundle cancel(int owner, String id) {
        Job job = owned(owner, id);
        Bundle result = new Bundle(); result.putBoolean("accepted", job != null);
        if (job != null) job.cancel("插件工具已请求取消；远端可能已执行，请勿自动重试");
        return result;
    }

    static Bundle busy(int owner) {
        Bundle result = new Bundle();
        synchronized (LOCK) {
            result.putBoolean("active", JOBS.values().stream().anyMatch(job -> job.owner == owner && !job.finished));
        }
        return result;
    }

    /** Settings-process action; no IPC method grants another UID bulk cancellation. */
    public static int stopRemoteCalls() {
        List<Job> current = new ArrayList<>();
        synchronized (LOCK) { for (Job job : JOBS.values()) if (!job.finished) current.add(job); }
        for (Job job : current) job.cancel("用户已停止 MCP 调用；远端可能已执行，请勿自动重试");
        return current.size();
    }

    private static Job owned(int owner, String id) {
        if (id == null) return null;
        synchronized (LOCK) {
            Job job = JOBS.get(id);
            return job != null && job.owner == owner ? job : null;
        }
    }

    /** Third-party output is data, never a local frame capability or a Phone Use control message. */
    static String remoteText(String text, String token) {
        String safe = text.replace("MIAIPLUS_FRAME:", "MIAIPLUS_REMOTE_FRAME:")
            .replace("CONTINUE：控屏尚未完成。", "[远程工具返回的 CONTINUE 文本]");
        if (!token.isEmpty()) {
            safe = safe.replace(token, "[凭证已隐藏]");
            String quoted = JSONObject.quote(token);
            safe = safe.replace(quoted.substring(1, quoted.length() - 1), "[凭证已隐藏]");
        }
        return "MCP 工具返回（远程内容）：\n" + safe;
    }

    static Bundle error(String message) {
        Bundle result = new Bundle();
        result.putString("status", "error"); result.putString("error", message);
        return result;
    }
    private static long now() { return System.nanoTime() / 1_000_000L; }

    private static final class Job {
        final String id = UUID.randomUUID().toString();
        final int owner;
        final String revision;
        final PluginSettings.Entry entry;
        final PluginToolSpec spec;
        final JSONObject arguments;
        final long deadline = now() + CALL_BUDGET_MS;
        volatile boolean finished;
        long finishedAt;
        boolean cancelled;
        String status = "running", result = "", error = "";
        Client client;
        ScheduledFuture<?> watchdog;

        Job(int owner, String revision, PluginSettings.Entry entry, PluginToolSpec spec, JSONObject arguments) {
            this.owner = owner; this.revision = revision; this.entry = entry; this.spec = spec; this.arguments = arguments;
        }

        void cancel(String reason) {
            Client current;
            synchronized (this) {
                if (finished || cancelled) return;
                cancelled = true; status = "error"; error = reason; result = ""; current = client;
            }
            if (current != null) try { current.cancel(); } catch (RuntimeException ignored) { }
        }
    }
}
