package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.os.Bundle;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.PopupMenu;
import java.util.List;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public class NativeReasoningHookTest {
    private Context context;
    private Menu menu;
    private SubMenu modes;
    private Bundle config;

    @Before public void setup() {
        context = RuntimeEnvironment.getApplication();
        NativeModelHook.invalidateConfig();
        Config.prefs(context).edit().clear().putBoolean("globalModel", true)
            .putString("endpoint", "https://example.test/v1").putString("model", "gpt-6.1-sol")
            .putString("api", "responses").commit();
        BridgeProvider provider = new BridgeProvider();
        ProviderInfo info = new ProviderInfo(); info.authority = BridgeProvider.URI.getAuthority();
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        config = provider.call("model_config", null, null);
        menu = new PopupMenu(context, new View(context)).getMenu();
        menu.add(Menu.NONE, 42, 0, "键盘输入");
        modes = menu.addSubMenu(Menu.NONE, NativeReasoningHook.FLOAT_MODE_ITEM, 1, "回答模式");
        for (String title : List.of("自动模式", "快速模式", "专家模式")) modes.add(title);
    }

    @Test public void floatingMenuUsesModelEffortsAndKeepsOtherEntries() {
        config.putString("reasoning", "high");
        NativeReasoningHook.replaceFloatingModes(menu, config);
        assertNotNull(menu.findItem(42));
        assertEquals("回答模式", modes.getItem().getTitle());
        assertEquals(6, modes.size());
        assertEquals("模型默认", modes.getItem(0).getTitle());
        assertEquals("最高 · max", modes.getItem(5).getTitle());
        assertArrayEquals(new Boolean[]{false, false, false, true, false, false}, NativeReasoningHook.floatingChecks(config));
        for (int i = 0; i < modes.size(); i++) assertEquals(i == 3, modes.getItem(i).isChecked());
    }

    @Test public void choosingFloatingEffortOnlyUpdatesItsScopedPreferenceThroughBridge() {
        var expected = new java.util.HashMap<String, Object>(Config.prefs(context).getAll());
        NativeReasoningHook.replaceFloatingModes(menu, config);
        assertTrue(NativeReasoningHook.applyFloatingSelection(context, modes.getItem(5)));
        assertEquals("max", new Config(context).reasoning);
        expected.put(ReasoningPolicy.preferenceKey(config.getString("endpoint"), config.getString("model"),
            config.getString("api")), "max");
        assertEquals(expected, Config.prefs(context).getAll());
        assertFalse(NativeReasoningHook.applyFloatingSelection(context, menu.findItem(42)));
        config.putString("reasoning", new Config(context).reasoning);
        NativeReasoningHook.replaceFloatingModes(menu, config);
        assertTrue(modes.getItem(5).isChecked());
    }

    @Test public void staleFloatingSelectionCannotWriteToAnotherModelOrOfficialMode() {
        NativeReasoningHook.replaceFloatingModes(menu, config);
        MenuItem stale = modes.getItem(5);
        Config.prefs(context).edit().putString("model", "qwen3.8-max").commit();
        assertThrows(IllegalStateException.class, () -> NativeReasoningHook.applyFloatingSelection(context, stale));
        assertEquals("default", new Config(context).reasoning);
        Config.prefs(context).edit().putString("model", "gpt-6.1-sol").putBoolean("globalModel", false).commit();
        assertThrows(IllegalStateException.class, () -> NativeReasoningHook.applyFloatingSelection(context, stale));
        assertEquals("default", new Config(context).reasoning);
    }

    @Test public void officialModeLeavesNativeSubmenuUntouched() {
        config.putBoolean("globalModel", false);
        NativeReasoningHook.replaceFloatingModes(menu, config);
        assertEquals(3, modes.size());
        assertEquals("自动模式", modes.getItem(0).getTitle());
        assertEquals("快速模式", modes.getItem(1).getTitle());
        assertEquals("专家模式", modes.getItem(2).getTitle());
        assertFalse(NativeReasoningHook.applyFloatingSelection(context, modes.getItem(0)));
    }

    @Test public void qwenAndUnknownModelsOnlyExposeSupportedChoices() {
        config.putString("model", "qwen3.8-max"); config.putString("api", "chat");
        config.putString("reasoning", "on");
        NativeReasoningHook.replaceFloatingModes(menu, config);
        assertEquals(3, modes.size());
        assertEquals("关闭思考", modes.getItem(1).getTitle());
        assertEquals("开启思考", modes.getItem(2).getTitle());
        assertArrayEquals(new Boolean[]{false, false, true}, NativeReasoningHook.floatingChecks(config));
        config.putString("model", "unknown-alias");
        NativeReasoningHook.replaceFloatingModes(menu, config);
        assertEquals(1, modes.size());
        assertTrue(modes.getItem(0).isChecked());
        assertArrayEquals(new Boolean[]{true}, NativeReasoningHook.floatingChecks(config));
    }
}
