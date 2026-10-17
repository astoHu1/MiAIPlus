package io.github.miaiplus;

import android.content.Context;
import android.content.res.Configuration;
import android.graphics.Color;
import android.graphics.Rect;
import android.graphics.drawable.GradientDrawable;
import android.os.Bundle;
import android.view.Gravity;
import android.view.Menu;
import android.view.MenuItem;
import android.view.SubMenu;
import android.view.View;
import android.widget.ArrayAdapter;
import android.widget.ListView;
import android.widget.PopupWindow;
import android.widget.TextView;
import android.widget.Toast;
import io.github.miaiplus.hook.HookCallback;
import io.github.miaiplus.hook.Hooks;
import io.github.miaiplus.hook.Reflector;
import java.util.List;

/** Version-pinned chat mode entry; never changes Xiaomi's AUTO/QUICK/EXPERT enum or stored mode. */
final class NativeReasoningHook {
    private NativeReasoningHook() {}
    private static final java.util.Map<TextView, CharSequence> stockLabels = new java.util.WeakHashMap<>();
    private static final java.util.Map<MenuItem, Bundle> floatingSelections = new java.util.WeakHashMap<>();
    private static java.lang.ref.WeakReference<Object> floatingPopup = new java.lang.ref.WeakReference<>(null);
    private static java.lang.ref.WeakReference<Object> fullScreenPopup = new java.lang.ref.WeakReference<>(null);
    private record FullScreenMenu(View anchor, Bundle config) { }
    private static final ThreadLocal<FullScreenMenu> fullScreenMenu = new ThreadLocal<>();
    // FloatStateMorePopup resources in the exact supported host version.
    static final int FLOAT_MODE_ITEM = 0x7f0a0a43;
    static int floatingModeItem() { return HostCompatibility.modern() ? 0x7f0a0a86 : FLOAT_MODE_ITEM; }
    private static final int FLOAT_EFFORT_ITEM = 0x00f10000;
    private static java.lang.ref.WeakReference<PopupWindow> active = new java.lang.ref.WeakReference<>(null);
    static void dispose() throws Exception {
        MainThread.call(() -> {
                PopupWindow popup = active.get(); if (popup != null) popup.dismiss();
                Object floating = floatingPopup.get();
                if (floating != null) Reflector.callMethod(floating, "dismiss");
                Object fullScreen = fullScreenPopup.get();
                if (fullScreen != null) {
                    Reflector.callMethod(fullScreen, "setOnMenuItemClickListener", (Object)null);
                    Reflector.callMethod(fullScreen, "dismiss");
                }
                fullScreenPopup.clear();
                floatingSelections.clear(); floatingPopup.clear();
                for (TextView chip : new java.util.ArrayList<>(stockLabels.keySet())) restoreLabel(chip);
                stockLabels.clear(); active.clear(); return null;
        });
    }
    static PopupWindow activePopup() { return active.get(); }
    static void install(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> mode = Reflector.findClass("io1.a", loader);
        Class<?> input = Reflector.findClass("com.xiaomi.voiceassistant.mainui.voicebar.StandardEditInputView", loader);
        hooks.add(Hooks.hookMethod(input.getDeclaredMethod("updateAiModeChip", mode), new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                stockLabels.remove(chip(p.thisObject));
                refresh(context, chip(p.thisObject));
            }
        }));
        hooks.add(Hooks.hookMethod(input.getDeclaredMethod("onAttachedToWindow"), new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                NativeModelHook.invalidateConfig();
                refresh(context, chip(p.thisObject));
            }
        }));
        Class<?> popup = Reflector.findClass("uo1.f$a", loader);
        hooks.add(Hooks.hookMethod(popup.getDeclaredMethod("showPopup", Context.class, View.class, View.class,
            mode, Reflector.findClass("kotlin.jvm.functions.Function1", loader), boolean.class, boolean.class,
            Reflector.findClass("kotlin.jvm.functions.Function0", loader)), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                NativeModelHook.invalidateConfig();
                Bundle config;
                try { config = NativeModelHook.config(context); }
                catch (Exception unavailable) { return; }
                if (!config.getBoolean("globalModel")) {
                    if (p.args[1] instanceof TextView text) restoreLabel(text);
                    return;
                }
                // Do not call the stock selection callback: it persists Xiaomi's mode and routes agents.
                p.setResult(null);
                try {
                    Reflector.callMethod(p.thisObject, "dismissPopup");
                    show((Context)p.args[0], (View)p.args[1], config);
                } catch (Exception unavailable) {
                    Toast.makeText((Context)p.args[0], "推理强度菜单不可用，请重新打开", Toast.LENGTH_SHORT).show();
                }
            }
        }));
        // Full-screen chat has a separate function-bar popup, independent of the voice-bar chip.
        Class<?> fullMode = Reflector.findClass("hl1.x", loader);
        Class<?> fullPopup = Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.d$a", loader);
        hooks.add(Hooks.hookMethod(fullPopup.getDeclaredMethod("show", View.class, List.class, fullMode,
            Reflector.findClass("kotlin.jvm.functions.Function1", loader)), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                NativeModelHook.invalidateConfig();
                Bundle config;
                try { config = NativeModelHook.config(context); }
                catch (Exception unavailable) { return; }
                View anchor = (View)p.args[0];
                TextView label = labelView(anchor);
                if (!config.getBoolean("globalModel")) {
                    if (label != null && p.args[1] instanceof List<?> items) {
                        for (Object item : items) if (Reflector.callMethod(item, "getMode") == p.args[2]) {
                            label.setText((String)Reflector.callMethod(item, "getName")); break;
                        }
                    }
                    return;
                }
                if (HostCompatibility.modern()) {
                    // Keep the native factory, placement, keyboard behavior and Miuix motion lifecycle.
                    p.setObjectExtra("previousFullScreenMenu", fullScreenMenu.get());
                    p.setObjectExtra("nativeFullScreenMenu", true);
                    fullScreenMenu.set(new FullScreenMenu(anchor, config));
                    return;
                }
                p.setResult(null);
                try {
                    Reflector.callMethod(p.thisObject, "dismiss");
                    show(anchor.getContext(), anchor, config);
                } catch (Exception unavailable) {
                    Toast.makeText(anchor.getContext(), "推理强度菜单不可用，请重新打开", Toast.LENGTH_SHORT).show();
                }
            }
            @Override protected void afterHookedMethod(MethodHookParam p) {
                if (!Boolean.TRUE.equals(p.getObjectExtra("nativeFullScreenMenu"))) return;
                FullScreenMenu previous = (FullScreenMenu)p.getObjectExtra("previousFullScreenMenu");
                if (previous == null) fullScreenMenu.remove(); else fullScreenMenu.set(previous);
            }
        }));
        if (HostCompatibility.modern()) installFullScreenMenu(loader, hooks);
        hooks.add(Hooks.hookMethod(Reflector.findClass("com.xiaomi.voiceassistant.mainui.functionbar.c", loader)
            .getDeclaredMethod(HostCompatibility.choose("Z", "c0"), fullMode), new HookCallback() {
                @Override protected void afterHookedMethod(MethodHookParam p) {
                    try {
                        Bundle config = NativeModelHook.config(context);
                        if (config.getBoolean("globalModel")) p.setResult(ReasoningPolicy.label(config.getString("reasoning", "default")));
                    } catch (Exception unavailable) { /* Keep the native label. */ }
                }
            }));
        installFloating(context, loader, hooks);
    }
    private static void installFullScreenMenu(ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> popup = Reflector.findClass("miuix.appcompat.widget.HyperPopupMenu", loader);
        Class<?> listener = Reflector.findClass("miuix.appcompat.widget.HyperPopupWindow$OnMenuItemClickListener", loader);
        hooks.add(Hooks.hookMethod(popup.getDeclaredMethod("show"), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                FullScreenMenu request = fullScreenMenu.get();
                if (request == null) return;
                Menu menu = (Menu)Reflector.callMethod(p.thisObject, "getMenu");
                menu.clear();
                Bundle config = request.config();
                List<String> choices = ReasoningPolicy.choices(config.getString("model", ""), config.getString("api", "chat"));
                Boolean[] checked = floatingChecks(config);
                var checks = new java.util.LinkedHashMap<Integer, Boolean>();
                var selections = new java.util.HashMap<Integer, String>();
                for (int i = 0; i < choices.size(); i++) {
                    int id = FLOAT_EFFORT_ITEM + i;
                    menu.add(Menu.NONE, id, i, ReasoningPolicy.label(choices.get(i))).setCheckable(true).setChecked(checked[i]);
                    checks.put(id, checked[i]); selections.put(id, choices.get(i));
                }
                Reflector.callMethod(p.thisObject, "savePrimaryCheckedMap", checks);
                Reflector.callMethod(p.thisObject, "notifyDataChanged", true);
                Object callback = java.lang.reflect.Proxy.newProxyInstance(loader, new Class<?>[]{listener}, (proxy, method, args) -> {
                    return switch (method.getName()) {
                        case "onMenuItemClick" -> {
                            String effort = selections.get(((MenuItem)args[0]).getItemId());
                            if (effort != null) {
                                try {
                                    Bundle result = save(request.anchor().getContext(), config, effort);
                                    TextView label = labelView(request.anchor());
                                    if (label != null) label.setText(ReasoningPolicy.label(result.getString("reasoning", "default")));
                                } catch (Exception failure) {
                                    Toast.makeText(request.anchor().getContext(), "模型配置已更改或保存失败，请重新打开菜单", Toast.LENGTH_SHORT).show();
                                }
                            }
                            yield null;
                        }
                        case "toString" -> "MiAIPlus reasoning selection";
                        case "hashCode" -> System.identityHashCode(proxy);
                        case "equals" -> proxy == args[0];
                        default -> throw new UnsupportedOperationException(method.getName());
                    };
                });
                Reflector.callMethod(p.thisObject, "setOnMenuItemClickListener", callback);
                fullScreenPopup = new java.lang.ref.WeakReference<>(p.thisObject);
            }
        }));
    }
    private static void installFloating(Context context, ClassLoader loader, List<HookCallback.Unhook> hooks) throws Exception {
        Class<?> floating = Reflector.findClass("zd1.b0", loader);
        // Native preCheckSecondaryItem consumes this array before the menu is inflated.
        hooks.add(Hooks.hookMethod(floating.getDeclaredMethod(HostCompatibility.choose("g", "n")), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                NativeModelHook.invalidateConfig();
                try {
                    Bundle config = NativeModelHook.config(context);
                    if (config.getBoolean("globalModel")) p.setResult(floatingChecks(config));
                } catch (Exception unavailable) { /* Keep the native menu. */ }
            }
        }));
        // Legacy t() / current U() finish stock visibility setup before the popup copies the menu.
        hooks.add(Hooks.hookMethod(floating.getDeclaredMethod(HostCompatibility.choose("t", "U"),
            HostCompatibility.modern() ? new Class<?>[]{Reflector.findClass("miuix.appcompat.internal.view.menu.d", loader), Context.class}
                : new Class<?>[]{Reflector.findClass("miuix.appcompat.internal.view.menu.d", loader)}), new HookCallback() {
            @Override protected void afterHookedMethod(MethodHookParam p) {
                try {
                    Bundle config = NativeModelHook.config(context);
                    replaceFloatingModes((Menu)p.args[0], config);
                    if (config.getBoolean("globalModel")) floatingPopup = new java.lang.ref.WeakReference<>(p.thisObject);
                }
                catch (Exception unavailable) { /* Keep the native menu. */ }
            }
        }));
        hooks.add(Hooks.hookMethod(floating.getDeclaredMethod(HostCompatibility.choose("o", "A"), MenuItem.class, Context.class), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) {
                if (!floatingSelections.containsKey((MenuItem)p.args[0])) return;
                // Never pass external efforts to the native AUTO/QUICK/EXPERT callback.
                p.setResult(null);
                Context ui = (Context)p.args[1];
                try { applyFloatingSelection(ui, (MenuItem)p.args[0]); }
                catch (Exception failure) {
                    Toast.makeText(ui, "模型配置已更改或保存失败，请重新打开菜单", Toast.LENGTH_SHORT).show();
                } finally { Reflector.callMethod(p.thisObject, "dismiss"); }
            }
        }));
    }
    static boolean applyFloatingSelection(Context context, MenuItem item) {
        Bundle selection = floatingSelections.get(item);
        if (selection == null) return false;
        save(context, selection, selection.getString("reasoning", "default"));
        return true;
    }
    static Boolean[] floatingChecks(Bundle config) {
        List<String> choices = ReasoningPolicy.choices(config.getString("model", ""), config.getString("api", "chat"));
        String selected = ReasoningPolicy.normalize(config.getString("model", ""), config.getString("api", "chat"),
            config.getString("reasoning", "default"));
        return choices.stream().map(selected::equals).toArray(Boolean[]::new);
    }
    static void replaceFloatingModes(Menu menu, Bundle config) {
        if (!config.getBoolean("globalModel")) return;
        MenuItem parent = menu.findItem(floatingModeItem());
        if (parent == null || !parent.hasSubMenu()) return;
        SubMenu modes = parent.getSubMenu();
        modes.clear();
        List<String> choices = ReasoningPolicy.choices(config.getString("model", ""), config.getString("api", "chat"));
        Boolean[] checked = floatingChecks(config);
        for (int i = 0; i < choices.size(); i++) {
            String effort = choices.get(i);
            MenuItem item = modes.add(Menu.NONE, FLOAT_EFFORT_ITEM + i, i, ReasoningPolicy.label(effort));
            item.setCheckable(true).setChecked(checked[i]);
            floatingSelections.put(item, selection(config, effort));
        }
    }
    private static Bundle selection(Bundle config, String effort) {
        Bundle extras = new Bundle();
        for (String key : List.of("endpoint", "model", "api")) extras.putString(key, config.getString(key, ""));
        extras.putString("reasoning", effort);
        return extras;
    }
    private static Bundle save(Context context, Bundle config, String effort) {
        Bundle result = context.getContentResolver().call(BridgeProvider.URI, "set_reasoning", null, selection(config, effort));
        if (result == null) throw new IllegalStateException("未保存");
        NativeModelHook.invalidateConfig();
        return result;
    }
    private static TextView chip(Object input) {
        return (TextView)Reflector.getObjectField(Reflector.getObjectField(input, "I"), "c");
    }
    private static void refresh(Context context, TextView chip) {
        try {
            Bundle config = NativeModelHook.config(context);
            if (config.getBoolean("globalModel")) {
                stockLabels.putIfAbsent(chip, chip.getText());
                chip.setText(ReasoningPolicy.label(config.getString("reasoning", "default")));
            } else restoreLabel(chip);
        } catch (Exception unavailable) { /* Original native label is still usable. */ }
    }
    private static void restoreLabel(TextView chip) {
        CharSequence original = stockLabels.remove(chip);
        if (original != null) chip.setText(original);
    }
    private static TextView labelView(View view) {
        if (view instanceof TextView text) return text;
        if (view instanceof android.view.ViewGroup group) {
            for (int i = 0; i < group.getChildCount(); i++) {
                TextView child = labelView(group.getChildAt(i));
                if (child != null) return child;
            }
        }
        return null;
    }
    @android.annotation.SuppressLint("RtlHardcoded") // Positions use absolute screen coordinates, including RTL.
    static PopupWindow show(Context context, View anchor, Bundle config) {
        PopupWindow previous = active.get();
        if (previous != null) previous.dismiss();
        List<String> choices = ReasoningPolicy.choices(config.getString("model", ""), config.getString("api", "chat"));
        String selected = config.getString("reasoning", "default");
        int checked = choices.indexOf(selected);
        boolean dark = (context.getResources().getConfiguration().uiMode & Configuration.UI_MODE_NIGHT_MASK) == Configuration.UI_MODE_NIGHT_YES;
        int textColor = dark ? Color.WHITE : Color.BLACK;
        ListView list = new ListView(context);
        String[] labels = choices.stream().map(ReasoningPolicy::label).toArray(String[]::new);
        list.setAdapter(new ArrayAdapter<String>(context, android.R.layout.simple_list_item_single_choice, labels) {
            @Override public View getView(int position, View convert, android.view.ViewGroup parent) {
                TextView row = (TextView)super.getView(position, convert, parent);
                row.setTextColor(textColor); row.setMinHeight(dp(context, 48));
                return row;
            }
        });
        list.setChoiceMode(ListView.CHOICE_MODE_SINGLE); list.setItemChecked(Math.max(0, checked), true);
        list.setDivider(null);
        Rect viewport = new Rect(); anchor.getWindowVisibleDisplayFrame(viewport);
        int width = Math.min(dp(context, 260), viewport.width());
        int height = Math.min(dp(context, 48 * choices.size() + 12), viewport.height());
        PopupWindow popup = new PopupWindow(list, width, height, true);
        GradientDrawable background = new GradientDrawable();
        background.setColor(dark ? 0xff2b2b2b : 0xfff5f5f5); background.setCornerRadius(dp(context, 20));
        popup.setBackgroundDrawable(background); popup.setElevation(dp(context, 8)); popup.setOutsideTouchable(true);
        View.OnAttachStateChangeListener attachment = new View.OnAttachStateChangeListener() {
            @Override public void onViewAttachedToWindow(View view) { }
            @Override public void onViewDetachedFromWindow(View view) { popup.dismiss(); }
        };
        popup.setOnDismissListener(() -> anchor.removeOnAttachStateChangeListener(attachment));
        list.setPadding(0, dp(context, 6), 0, dp(context, 6));
        list.setOnItemClickListener((parent, row, position, id) -> {
            try {
                Bundle result = save(context, config, choices.get(position));
                TextView text = labelView(anchor);
                if (text != null) text.setText(ReasoningPolicy.label(result.getString("reasoning", "default")));
                popup.dismiss();
            } catch (Exception failure) {
                Toast.makeText(context, "模型配置已更改或保存失败，请重新打开菜单", Toast.LENGTH_SHORT).show();
                popup.dismiss();
            }
        });
        int[] location = new int[2]; anchor.getLocationOnScreen(location);
        int x = Math.max(viewport.left, Math.min(location[0], viewport.right - width));
        int y = Math.max(viewport.top, location[1] - height - dp(context, 8));
        popup.showAtLocation(anchor, Gravity.TOP | Gravity.LEFT, x, y);
        anchor.addOnAttachStateChangeListener(attachment);
        active = new java.lang.ref.WeakReference<>(popup);
        return popup;
    }
    private static int dp(Context context, int value) { return Math.round(context.getResources().getDisplayMetrics().density * value); }
}
