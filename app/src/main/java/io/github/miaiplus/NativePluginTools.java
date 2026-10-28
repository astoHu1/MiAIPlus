package io.github.miaiplus;

import android.content.Context;
import android.os.Bundle;
import android.os.Looper;
import android.os.SystemClock;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.lang.ref.WeakReference;
import java.util.ArrayList;
import java.util.Collections;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.WeakHashMap;
import org.json.JSONArray;
import org.json.JSONObject;

/** Version-pinned native adapter shared by first-party and user-configured plugins. */
final class NativePluginTools {
    private static final Object LOCK = new Object();
    private static final Map<Object, Map<String, Object>> registries = new WeakHashMap<>();
    private static final List<WeakReference<Object>> definitions = new ArrayList<>();
    private static final ThreadLocal<Boolean> synchronizing = ThreadLocal.withInitial(() -> false);
    private static volatile Catalog catalog = new Catalog("", Map.of());
    private static ClassLoader hostLoader;

    private record Entry(String revision, PluginToolSpec spec, Object proxy, Object definition) { }
    private record Catalog(String revision, Map<String, Entry> tools) { }

    static Object json(ClassLoader loader, String text) {
        return Reflector.callMethod(Reflector.getStaticObjectField(Reflector.findClass("s92.b", loader), "d"),
            "parseToJsonElement", text);
    }

    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) {
        synchronized (LOCK) { hostLoader = loader; }
        refresh(context);
        Class<?> registry = Reflector.findClass("com.aios.osbot.tools.ToolRegistry", loader);
        if (HostCompatibility.modern()) {
            // One set of hooks for the whole catalog, including registries created before reload.
            for (var method : registry.getDeclaredMethods()) {
                if (!Set.of("get", "getAll", "peek", "peekAll", "searchTools").contains(method.getName())) continue;
                hooks.add(Hooks.hookMethod(method, new HookCallback() {
                    @Override protected void beforeHookedMethod(MethodHookParam param) {
                        synchronizeRegistry(param.thisObject);
                    }
                }));
            }
        } else {
            Object existing = Reflector.getStaticObjectField(registry, "instance");
            if (existing != null) synchronizeRegistry(existing);
        }
        hooks.addAll(Hooks.hookAllConstructors(registry, new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam param) {
                synchronizeRegistry(param.thisObject);
            }
        }));
    }

    /** Local IPC only. Registry query hooks use this snapshot without any IPC or network discovery. */
    static boolean refresh(Context context) {
        synchronized (LOCK) {
            try {
                if (hostLoader == null) return false;
                Bundle response = context.getContentResolver().call(BridgeProvider.URI, "plugin_catalog", null, null);
                if (response == null) throw new IllegalStateException("Plugin catalog unavailable");
                String revision = response.getString("revision", "");
                if (revision.isBlank()) throw new IllegalStateException("Missing plugin revision");
                if (revision.equals(catalog.revision())) return true;
                String encoded = response.getString("tools", "[]");
                // Match PluginRuntime's credential-free Binder catalog budget.
                if (encoded.length() > 96 * 1024) throw new IllegalStateException("Plugin catalog too large");
                JSONArray tools = new JSONArray(encoded);
                if (tools.length() > 128) throw new IllegalStateException("Too many plugin tools");
                Map<String, Entry> next = new LinkedHashMap<>();
                for (int i = 0; i < tools.length(); i++) {
                    JSONObject value = tools.getJSONObject(i);
                    PluginToolSpec spec = PluginToolSpec.fromJson(value);
                    String pluginLabel = value.has("pluginLabel")
                        ? PluginToolSpec.string(value, "pluginLabel") : spec.displayName();
                    if (value.has("pluginLabel")) PluginToolSpec.requireText(pluginLabel, 80, "插件显示名称无效");
                    if ((!"mcp".equals(spec.kind()) && !PhoneUsePlugin.owns(spec))
                        || ("mcp".equals(spec.kind()) && (PhoneUseRouting.FOREGROUND_TOOL.equals(spec.name())
                        || PhoneUseRouting.BACKGROUND_TOOL.equals(spec.name()))))
                        throw new IllegalArgumentException("Unknown plugin execution kind");
                    if (next.containsKey(spec.name())) throw new IllegalArgumentException("Duplicate plugin tool");
                    next.put(spec.name(), createEntry(context, hostLoader, revision, spec, pluginLabel));
                }
                catalog = new Catalog(revision, Collections.unmodifiableMap(next));
                definitions.removeIf(reference -> reference.get() == null);
                for (Entry entry : next.values()) definitions.add(new WeakReference<>(entry.definition()));
                synchronizeKnownRegistries();
                return true;
            } catch (Exception unavailable) {
                // Do not advertise or execute cached tools while the authoritative catalog is unavailable.
                catalog = new Catalog("", Map.of());
                synchronizeKnownRegistries();
                return false;
            }
        }
    }

    static boolean available(Context context, String name) {
        return refresh(context) && catalog.tools().containsKey(name);
    }

    static String promptPolicy(Context context) {
        refresh(context);
        return PhoneUsePlugin.promptPolicy(catalog.tools().keySet());
    }

    static boolean canReload(Context context) {
        try {
            Bundle state = context.getContentResolver().call(BridgeProvider.URI, "plugin_busy", null, null);
            return state != null && !state.getBoolean("active", true);
        } catch (RuntimeException unavailable) { return false; }
    }

    private static Entry createEntry(Context context, ClassLoader loader, String revision, PluginToolSpec spec,
        String pluginLabel) {
        Object schema = json(loader, spec.inputSchema());
        boolean remote = "mcp".equals(spec.kind());
        String description = remote ? "MCP 插件「" + pluginLabel + "」/ 来源工具 " + spec.sourceName()
            + "：" + spec.description() : spec.description();
        String displayName = remote ? pluginLabel + " · " + spec.displayName() : spec.displayName();
        Object definition = Reflector.newInstance(Reflector.findClass("com.aios.osbot.llm.model.ToolDefinition", loader),
            spec.name(), description, schema);
        Class<?> toolType = Reflector.findClass("com.aios.osbot.tools.Tool", loader);
        Object proxy = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{toolType}, (self, method, args) -> {
            return switch (method.getName()) {
                case "getName" -> spec.name();
                case "getDisplayName" -> displayName;
                case "getDescription", "getSummary", "getSearchHint" -> description;
                case "getParameters" -> schema;
                case "getCategory" -> "builtin".equals(spec.kind()) ? "phone" : "plugin";
                case "getRejects" -> "";
                case "getScope", "getTags" -> List.of();
                case "getDisplayDescriptionResId" -> 0;
                case "getTimeoutMs", "resolveTimeoutMs" -> "mcp".equals(spec.kind()) ? 65000L : spec.timeoutMs();
                case "isStoppable" -> false;
                case "onCancel" -> Reflector.getStaticObjectField(Reflector.findClass("kotlin.Unit", loader), "INSTANCE");
                case "execute" -> execute(context, loader, revision, spec, self, args);
                case "toString" -> spec.name();
                case "hashCode" -> System.identityHashCode(self);
                case "equals" -> self == args[0];
                default -> throw new UnsupportedOperationException(method.getName());
            };
        });
        return new Entry(revision, spec, proxy, definition);
    }

    private static Object execute(Context context, ClassLoader loader, String revision, PluginToolSpec spec,
        Object proxy, Object[] args) {
        Hooks.enter();
        try {
            if (Looper.myLooper() == Looper.getMainLooper())
                return unavailable(loader, spec, "插件工具不可在主线程执行");
            if (!refresh(context)) return unavailable(loader, spec, "插件配置不可用，本次调用未执行");
            Entry current = catalog.tools().get(spec.name());
            if (!revision.equals(catalog.revision()) || current == null || current.proxy() != proxy
                || !current.spec().equals(spec))
                return unavailable(loader, spec, "插件配置已更改或工具已停用，本次调用未执行，不要自动重试");
            if (args == null || args.length < 2 || args[0] == null)
                return unavailable(loader, spec, "插件工具参数不完整");
            return "builtin".equals(spec.kind())
                ? PhoneUsePlugin.execute(context, loader, spec, args[0], args[1])
                : executeRemote(context, loader, revision, spec, args[0].toString());
        } finally { Hooks.leave(); }
    }

    private static Object unavailable(ClassLoader loader, PluginToolSpec spec, String reason) {
        boolean phone = "builtin".equals(spec.kind());
        return failure(loader, phone ? PhoneUseResult.stopped(reason) : reason, phone);
    }

    private static Object executeRemote(Context context, ClassLoader loader, String revision, PluginToolSpec spec,
        String arguments) {
        String id = null;
        boolean finished = false;
        try {
            new JSONObject(arguments);
            Bundle request = new Bundle();
            request.putString("revision", revision);
            request.putString("pluginId", spec.pluginId());
            request.putString("toolName", spec.name());
            request.putString("arguments", arguments);
            Bundle started = context.getContentResolver().call(BridgeProvider.URI, "plugin_start", null, request);
            if (started == null || started.containsKey("error"))
                return failure(loader, "插件工具启动失败，配置可能已更改；不要自动重试", false);
            id = started.getString("id");
            if (id == null || id.isBlank()) throw new IllegalStateException("Missing plugin run");
            long deadline = SystemClock.elapsedRealtime() + 65000L;
            while (SystemClock.elapsedRealtime() < deadline) {
                if (Thread.currentThread().isInterrupted()) throw new InterruptedException();
                Bundle state = context.getContentResolver().call(BridgeProvider.URI, "plugin_poll", id, null);
                if (state == null) throw new IllegalStateException("Missing plugin state");
                String status = state.getString("status", "");
                if ("done".equals(status)) {
                    finished = true;
                    return success(loader, remoteText(state.getString("result", "")), Map.of(), false);
                }
                if ("error".equals(status) || state.containsKey("error")) {
                    finished = true;
                    return failure(loader, "插件工具执行失败或已停止，本次调用未重试", false);
                }
                if (!"running".equals(status)) throw new IllegalStateException("Invalid plugin state");
                Thread.sleep(100);
            }
            return failure(loader, "插件工具调用超时，已请求取消；不要自动重试", false);
        } catch (Exception failure) {
            if (failure instanceof InterruptedException) Thread.currentThread().interrupt();
            return failure(loader, "插件工具调用已中断或服务不可用；不要自动重试", false);
        } finally {
            if (id != null && !finished) {
                try { context.getContentResolver().call(BridgeProvider.URI, "plugin_cancel", id, null); }
                catch (Exception ignored) { /* Module-side timeout remains authoritative. */ }
            }
        }
    }

    /** Remote content must never consume the privileged one-time Phone Use image channel. */
    static String remoteText(String text) {
        return text.replace("MIAIPLUS_FRAME:", "MIAIPLUS_REMOTE_FRAME:");
    }

    static Object success(ClassLoader loader, String text, Map<String, Object> metadata, boolean directAnswer) {
        return Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Success", loader),
            text, metadata, null, List.of(), directAnswer, null, false);
    }

    static Object failure(ClassLoader loader, String text, boolean terminateAgent) {
        Object permanent = Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.tools.FailureType", loader),
            "PERMANENT");
        return Reflector.newInstance(Reflector.findClass("com.aios.osbot.tools.ToolResult$Failure", loader),
            text, false, permanent, null, terminateAgent, text, null);
    }

    private static Object registeredTool(Object registry, String name) {
        // Both pinned APKs use this exact map field. Modern peek() hides invisible tools and
        // therefore cannot prove a name is free or that a hidden registration belongs to us.
        Object tools = Reflector.getObjectField(registry, "tools");
        if (!(tools instanceof Map<?, ?> map)) throw new IllegalStateException("Invalid native tool registry");
        return map.get(name);
    }

    private static void synchronizeKnownRegistries() {
        for (Object registry : new ArrayList<>(registries.keySet())) synchronizeRegistry(registry);
    }

    static void synchronizeRegistry(Object registry) {
        if (registry == null || synchronizing.get()) return;
        synchronized (LOCK) {
            synchronizing.set(true);
            try {
                Map<String, Object> owned = registries.computeIfAbsent(registry, key -> new LinkedHashMap<>());
                for (String name : new ArrayList<>(owned.keySet())) {
                    Entry next = catalog.tools().get(name);
                    Object previous = owned.get(name);
                    if (next != null && next.proxy() == previous) continue;
                    removeOwned(registry, name, previous);
                    owned.remove(name);
                }
                for (Entry entry : catalog.tools().values()) {
                    Object current = registeredTool(registry, entry.spec().name());
                    if (current == null) {
                        Reflector.callMethod(registry, HostCompatibility.choose("registerSystem", "register"), entry.proxy());
                        current = registeredTool(registry, entry.spec().name());
                    }
                    // A native/other module tool wins a collision. Never replace it or claim ownership.
                    if (current == entry.proxy()) {
                        owned.put(entry.spec().name(), entry.proxy());
                    }
                }
            } finally { synchronizing.remove(); }
        }
    }

    private static void removeOwned(Object registry, String name, Object proxy) {
        if (registeredTool(registry, name) != proxy) return;
        Reflector.callMethod(registry, "unregister", name);
        if (!HostCompatibility.modern())
            ((Set<?>)Reflector.getObjectField(registry, "systemToolNames")).remove(name);
    }

    static Object registry(Context context, ClassLoader loader) {
        if (!HostCompatibility.modern())
            return Reflector.getStaticObjectField(Reflector.findClass("com.aios.osbot.tools.ToolRegistry", loader), "instance");
        Object entry = Reflector.callStaticMethod(Reflector.findClass("n02.e", loader), "fromApplication", context,
            Reflector.findClass("com.aios.osbot.tools.phone.service.InCallServiceEntryPoint", loader));
        return Reflector.callMethod(entry, "toolRegistry");
    }

    static void addDefinition(Context context, HookCallback.MethodHookParam param) {
        if (!(param.args[1] instanceof List<?> previous)) return;
        refresh(context);
        synchronized (LOCK) {
            Object registry = null;
            try { registry = registry(context, hostLoader); synchronizeRegistry(registry); }
            catch (RuntimeException unavailable) { /* Existing native definitions remain authoritative. */ }
            List<Object> tools = new ArrayList<>();
            for (Object value : previous) {
                boolean ours = false;
                for (WeakReference<Object> reference : definitions) if (reference.get() == value) { ours = true; break; }
                // This request may originate from another DI registry. Name/schema equality and the
                // application registry cannot prove ownership of an incoming definition.
                if (!ours) tools.add(value);
            }
            if (!catalog.tools().isEmpty()) {
                if (registry != null) for (Entry entry : catalog.tools().values()) {
                    if (registeredTool(registry, entry.spec().name()) != entry.proxy()) continue;
                    boolean exists = false;
                    for (Object value : tools) if (entry.spec().name().equals(Reflector.callMethod(value, "getName"))) {
                        exists = true; break;
                    }
                    if (!exists) tools.add(entry.definition());
                }
            }
            param.args[1] = tools;
        }
    }

    static void dispose() {
        synchronized (LOCK) {
            catalog = new Catalog("", Map.of());
            synchronizing.set(true);
            try {
                RuntimeException failed = null;
                for (var registry : registries.entrySet()) for (var entry : registry.getValue().entrySet()) {
                    try { removeOwned(registry.getKey(), entry.getKey(), entry.getValue()); }
                    catch (RuntimeException failure) { if (failed == null) failed = failure; }
                }
                if (failed != null) throw failed;
            } finally {
                registries.clear();
                definitions.clear();
                hostLoader = null;
                synchronizing.remove();
            }
        }
    }

    private NativePluginTools() { }
}
