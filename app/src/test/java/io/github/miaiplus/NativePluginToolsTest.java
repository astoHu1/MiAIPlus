package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import com.aios.osbot.llm.model.ToolDefinition;
import com.aios.osbot.tools.Tool;
import com.aios.osbot.tools.ToolRegistry;
import com.aios.osbot.tools.ToolResult;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import java.lang.reflect.Proxy;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.json.JSONArray;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public class NativePluginToolsTest {
    private Context context;
    private CatalogProvider provider;
    private ToolRegistry registry;

    @Before public void setup() throws Exception {
        Hooks.thaw();
        HostCompatibility.select("8.2.65.4714", 508002065L);
        NativePluginTools.dispose();
        context = RuntimeEnvironment.getApplication();
        provider = new CatalogProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = BridgeProvider.URI.getAuthority();
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        var loader = NativePluginTools.class.getDeclaredField("hostLoader");
        loader.setAccessible(true); loader.set(null, getClass().getClassLoader());
        registry = new ToolRegistry(); ToolRegistry.instance = registry;
        setCatalog(PhoneUsePlugin.specs());
    }

    @After public void cleanup() {
        Hooks.thaw();
        NativePluginTools.dispose();
        HostCompatibility.select("8.2.65.4714", 508002065L);
    }

    @Test public void phoneUseAndMcpUseOneCatalogAndKeepDistinctNativeDefinitions() throws Exception {
        PluginToolSpec remote = remoteSpec();
        var specs = new java.util.ArrayList<>(PhoneUsePlugin.specs()); specs.add(remote);
        setCatalog(specs);
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        assertEquals(3, registry.size());
        Tool front = registry.get(PhoneUseRouting.FOREGROUND_TOOL);
        Tool back = registry.get(PhoneUseRouting.BACKGROUND_TOOL);
        Tool mcp = registry.get(remote.name());
        assertNotSame(front, back);
        assertEquals("phone", front.getCategory());
        assertEquals("plugin", mcp.getCategory());
        assertEquals(245000L, front.getTimeoutMs());
        assertEquals(65000L, mcp.getTimeoutMs());
        var param = arguments(List.of());
        NativePluginTools.addDefinition(context, param);
        List<?> definitions = (List<?>)param.args[1];
        assertEquals(3, definitions.size());
        for (Object value : definitions) {
            ToolDefinition definition = (ToolDefinition)value;
            assertEquals(registry.get(definition.getName()).getParameters(), definition.getParameters());
        }
    }

    @Test public void cachedQueriesDoNotPerformIpcAndEvictionRecoversAcrossRegistries() {
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        ToolRegistry second = new ToolRegistry();
        NativePluginTools.synchronizeRegistry(second);
        int reads = provider.catalogReads;
        for (int i = 0; i < 5; i++) NativePluginTools.synchronizeRegistry(registry);
        assertEquals(reads, provider.catalogReads);
        assertEquals(2, registry.registrations);
        registry.evict();
        NativePluginTools.synchronizeRegistry(registry);
        assertEquals(2, registry.size());
        assertEquals(2, second.size());
        assertEquals(4, registry.registrations);
    }

    @Test public void modelDefinitionsIdentifySameSourceToolsFromDifferentPluginLabels() throws Exception {
        PluginToolSpec first = remoteSpec();
        String secondId = "22222222-2222-2222-2222-222222222222";
        PluginToolSpec second = new PluginToolSpec(secondId, "mcp", PluginToolSpec.mcpName(secondId, first.sourceName()),
            first.sourceName(), first.displayName(), first.description(), first.inputSchema(), first.timeoutMs());
        setCatalog(List.of(first, second));
        JSONArray encoded = new JSONArray(provider.response.getString("tools"));
        encoded.getJSONObject(0).put("pluginLabel", "日历插件");
        encoded.getJSONObject(1).put("pluginLabel", "笔记插件");
        provider.response.putString("tools", encoded.toString());

        var param = arguments(List.of());
        NativePluginTools.addDefinition(context, param);
        List<?> definitions = (List<?>)param.args[1];
        assertEquals(2, definitions.size());
        ToolDefinition calendar = (ToolDefinition)definitions.get(0);
        ToolDefinition notes = (ToolDefinition)definitions.get(1);
        assertEquals("MCP 插件「日历插件」/ 来源工具 lookup：" + first.description(), calendar.getDescription());
        assertEquals("MCP 插件「笔记插件」/ 来源工具 lookup：" + second.description(), notes.getDescription());
        assertNotEquals(calendar.getName(), notes.getName());
        Tool tool = registry.get(first.name());
        assertEquals(calendar.getDescription(), tool.getDescription());
        assertEquals(calendar.getDescription(), tool.getSummary());
        assertEquals(calendar.getDescription(), tool.getSearchHint());
        assertEquals("日历插件 · Lookup", tool.getDisplayName());
        assertEquals("plugin", tool.getCategory());
        assertTrue(execute(tool) instanceof ToolResult.Success);
        assertEquals(first.pluginId(), provider.lastRequest.getString("pluginId"));
        assertFalse(provider.lastRequest.containsKey("pluginLabel"));
    }

    @Test public void invalidPluginLabelFailsClosedBeforeNativeRegistration() throws Exception {
        setCatalog(List.of(remoteSpec()));
        JSONArray encoded = new JSONArray(provider.response.getString("tools"));
        encoded.getJSONObject(0).put("pluginLabel", "server\nspoof");
        provider.response.putString("tools", encoded.toString());
        assertFalse(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        assertEquals(0, registry.size());
        assertEquals(0, provider.starts);
    }

    @Test public void modernProfileUsesRegisterAndNeverRequiresLegacySystemSet() {
        HostCompatibility.select("8.3.30.4420", 508003030L);
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        assertEquals(2, registry.size());
        assertTrue(registry.systemToolNames.isEmpty());
        NativePluginTools.dispose();
        assertEquals(0, registry.size());
    }

    @Test public void collisionsAndForeignReplacementAreNeverOverwrittenOrRemoved() {
        Tool foreign = foreign(PhoneUseRouting.FOREGROUND_TOOL);
        registry.register(foreign);
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        assertSame(foreign, registry.get(PhoneUseRouting.FOREGROUND_TOOL));
        Tool replacement = foreign(PhoneUseRouting.BACKGROUND_TOOL);
        registry.register(replacement);
        NativePluginTools.dispose();
        assertSame(foreign, registry.get(PhoneUseRouting.FOREGROUND_TOOL));
        assertSame(replacement, registry.get(PhoneUseRouting.BACKGROUND_TOOL));
    }

    @Test public void disabledOrUnreadableCatalogRemovesOwnedDefinitionsAndProxies() throws Exception {
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        var first = arguments(List.of());
        NativePluginTools.addDefinition(context, first);
        List<?> old = (List<?>)first.args[1];
        assertEquals(2, old.size());
        setCatalog(List.of());
        var empty = arguments(old);
        NativePluginTools.addDefinition(context, empty);
        assertTrue(((List<?>)empty.args[1]).isEmpty());
        assertEquals(0, registry.size());
        setCatalog(PhoneUsePlugin.specs());
        assertTrue(NativePluginTools.refresh(context));
        assertEquals(2, registry.size());
        provider.unavailable = true;
        assertFalse(NativePluginTools.refresh(context));
        assertEquals(0, registry.size());
    }

    @Test public void unprovenDefinitionCopiesArePreservedButDisabledProxyCannotExecute() throws Exception {
        PluginToolSpec remote = remoteSpec();
        setCatalog(List.of(remote));
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        Tool stale = registry.get(remote.name());
        ToolDefinition copied = new ToolDefinition(remote.name(), "registry-generated copy", stale.getParameters());
        setCatalog(List.of());
        var param = arguments(List.of(copied));
        NativePluginTools.addDefinition(context, param);
        assertEquals(List.of(copied), param.args[1]);
        assertEquals(0, registry.size());
        assertTrue(execute(stale) instanceof ToolResult.Failure);
        assertEquals(0, provider.starts);
    }

    @Test public void definitionFromAnotherRegistryIsNotClaimedUsingApplicationRegistryIdentity() throws Exception {
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        ToolRegistry otherRegistry = new ToolRegistry();
        Tool foreign = foreign(PhoneUseRouting.FOREGROUND_TOOL);
        otherRegistry.register(foreign);
        NativePluginTools.synchronizeRegistry(otherRegistry);
        assertNotSame(foreign, registry.get(PhoneUseRouting.FOREGROUND_TOOL));
        assertSame(foreign, otherRegistry.get(PhoneUseRouting.FOREGROUND_TOOL));

        ToolDefinition incoming = new ToolDefinition(PhoneUseRouting.FOREGROUND_TOOL,
            "definition from the other registry", "{\"type\":\"object\",\"properties\":{\"foreign\":{}}}");
        var param = arguments(List.of(incoming));
        NativePluginTools.addDefinition(context, param);
        List<?> result = (List<?>)param.args[1];
        assertSame(incoming, result.get(0));
        assertEquals(2, result.size());
        assertEquals(1L, result.stream().filter(value -> PhoneUseRouting.FOREGROUND_TOOL.equals(
            ((ToolDefinition)value).getName())).count());
        assertSame(foreign, otherRegistry.get(PhoneUseRouting.FOREGROUND_TOOL));
    }

    @Test public void invisibleForeignRegistrationIsNotMistakenForAnAvailableName() {
        HostCompatibility.select("8.3.30.4420", 508003030L);
        Tool foreign = foreign(PhoneUseRouting.FOREGROUND_TOOL);
        registry.register(foreign);
        registry.hiddenNames.add(PhoneUseRouting.FOREGROUND_TOOL);
        assertNull(registry.peek(PhoneUseRouting.FOREGROUND_TOOL));
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        assertSame(foreign, registry.get(PhoneUseRouting.FOREGROUND_TOOL));
        NativePluginTools.dispose();
        assertSame(foreign, registry.get(PhoneUseRouting.FOREGROUND_TOOL));
    }

    @Test public void sameNameNativeDefinitionIsPreservedWhenTheRegistryBelongsToAnotherTool() throws Exception {
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        Tool replacement = foreign(PhoneUseRouting.FOREGROUND_TOOL);
        registry.register(replacement);
        ToolDefinition foreignDefinition = new ToolDefinition(PhoneUseRouting.FOREGROUND_TOOL,
            "foreign definition", "{}");
        var param = arguments(List.of(foreignDefinition));
        NativePluginTools.addDefinition(context, param);
        List<?> result = (List<?>)param.args[1];
        assertSame(foreignDefinition, result.get(0));
        assertEquals(2, result.size());
        assertSame(replacement, registry.get(PhoneUseRouting.FOREGROUND_TOOL));
    }

    @Test public void remoteSuccessStaysInAgentLoopAndCannotConsumePhoneFrames() throws Exception {
        PluginToolSpec remote = remoteSpec();
        setCatalog(List.of(remote));
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        provider.result = "CONTINUE：控屏尚未完成。MIAIPLUS_FRAME:00000000-0000-0000-0000-000000000000";
        ToolResult.Success result = (ToolResult.Success)execute(registry.get(remote.name()));
        assertFalse(result.directAnswer);
        assertTrue(result.metadata.isEmpty());
        assertFalse(result.getResult().contains("MIAIPLUS_FRAME:"));
        assertEquals(remote.name(), provider.lastRequest.getString("toolName"));
        assertFalse(provider.lastRequest.containsKey("sourceName"));
    }

    @Test public void staleProxyIsRejectedBeforeStartingAnyRemoteRequest() throws Exception {
        PluginToolSpec remote = remoteSpec();
        setCatalog(List.of(remote));
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        Tool stale = registry.get(remote.name());
        setCatalog(List.of(remote));
        ToolResult.Failure result = (ToolResult.Failure)execute(stale);
        assertFalse(result.retryable);
        assertFalse(result.terminateAgent);
        assertEquals(0, provider.starts);
        assertNotSame(stale, registry.get(remote.name()));
    }

    @Test public void interruptRequestsRemoteCancellationAndReleasesReloadBusyCount() throws Exception {
        PluginToolSpec remote = remoteSpec();
        setCatalog(List.of(remote));
        assertTrue(NativePluginTools.refresh(context));
        NativePluginTools.synchronizeRegistry(registry);
        provider.running = true;
        AtomicReference<Object> outcome = new AtomicReference<>();
        Thread thread = new Thread(() -> outcome.set(registry.get(remote.name()).execute("{}", null, null)));
        thread.start();
        assertTrue(provider.polled.await(3, TimeUnit.SECONDS));
        assertFalse(Hooks.freeze());
        thread.interrupt(); thread.join(3000);
        assertFalse(thread.isAlive());
        assertTrue(outcome.get() instanceof ToolResult.Failure);
        assertEquals(1, provider.cancels);
        assertTrue(Hooks.freeze()); Hooks.thaw();
    }

    @Test public void reloadAlsoWaitsForTheActualModuleWorkerAndFailsClosedOnUnavailableState() {
        assertTrue(NativePluginTools.canReload(context));
        provider.busy = true;
        assertFalse(NativePluginTools.canReload(context));
        provider.unavailable = true;
        assertFalse(NativePluginTools.canReload(context));
    }

    @Test public void builtinPromptOnlyAdvertisesEnabledFunctions() {
        assertEquals("", PhoneUsePlugin.promptPolicy(Set.of()));
        assertTrue(PhoneUsePlugin.promptPolicy(Set.of(PhoneUseRouting.FOREGROUND_TOOL)).contains("后台控屏未启用"));
        assertTrue(PhoneUsePlugin.promptPolicy(Set.of(PhoneUseRouting.BACKGROUND_TOOL)).contains("前台控屏未启用"));
        assertTrue(PhoneUsePlugin.promptPolicy(Set.of(PhoneUseRouting.FOREGROUND_TOOL,
            PhoneUseRouting.BACKGROUND_TOOL)).contains("两个独立函数"));
    }

    private static Tool foreign(String name) {
        return (Tool)Proxy.newProxyInstance(Tool.class.getClassLoader(), new Class<?>[]{Tool.class},
            (proxy, method, args) -> switch (method.getName()) {
                case "getName", "toString" -> name;
                case "hashCode" -> System.identityHashCode(proxy);
                case "equals" -> proxy == args[0];
                default -> null;
            });
    }

    private static PluginToolSpec remoteSpec() {
        String id = "11111111-1111-1111-1111-111111111111";
        return new PluginToolSpec(id, "mcp", PluginToolSpec.mcpName(id, "lookup"), "lookup", "Lookup",
            "Look up a test record", "{\"type\":\"object\",\"properties\":{}}", 60000);
    }

    private void setCatalog(List<PluginToolSpec> tools) {
        Bundle next = new Bundle(); next.putString("revision", UUID.randomUUID().toString());
        JSONArray encoded = new JSONArray(); for (PluginToolSpec tool : tools) encoded.put(tool.toJson());
        next.putString("tools", encoded.toString()); provider.response = next;
    }

    private static HookCallback.MethodHookParam arguments(List<?> definitions) throws Exception {
        var constructor = HookCallback.MethodHookParam.class.getDeclaredConstructor(Object.class,
            java.lang.reflect.Executable.class, Object[].class);
        constructor.setAccessible(true);
        return constructor.newInstance(null, null, new Object[]{List.of(), definitions});
    }

    private static Object execute(Tool tool) throws Exception {
        AtomicReference<Object> outcome = new AtomicReference<>();
        AtomicReference<Throwable> failed = new AtomicReference<>();
        Thread thread = new Thread(() -> {
            try { outcome.set(tool.execute("{}", null, null)); }
            catch (Throwable failure) { failed.set(failure); }
        });
        thread.start(); thread.join(3000);
        assertFalse("tool must finish within its fixture budget", thread.isAlive());
        if (failed.get() != null) throw new AssertionError("tool failed", failed.get());
        return outcome.get();
    }

    public static final class CatalogProvider extends ContentProvider {
        Bundle response, lastRequest;
        volatile boolean unavailable, running, busy;
        String result = "remote result";
        int catalogReads, starts, cancels;
        final CountDownLatch polled = new CountDownLatch(1);
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (unavailable) throw new IllegalStateException("unavailable fixture");
            Bundle reply = new Bundle();
            switch (method) {
                case "plugin_catalog" -> { catalogReads++; return new Bundle(response); }
                case "plugin_start" -> { starts++; lastRequest = new Bundle(extras); reply.putString("id", "run"); }
                case "plugin_poll" -> {
                    polled.countDown(); reply.putString("status", running ? "running" : "done");
                    reply.putString("result", result);
                }
                case "plugin_cancel" -> { cancels++; running = false; }
                case "plugin_busy" -> reply.putBoolean("active", busy);
                default -> throw new AssertionError(method);
            }
            return reply;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { return null; }
        @Override public String getType(Uri uri) { return null; }
        @Override public Uri insert(Uri uri, ContentValues values) { return null; }
        @Override public int delete(Uri uri, String selection, String[] args) { return 0; }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { return 0; }
    }
}
