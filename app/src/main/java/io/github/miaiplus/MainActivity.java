package io.github.miaiplus;

import android.app.Activity;
import android.content.Intent;
import android.os.Bundle;
import android.os.Handler;
import android.os.Looper;
import android.provider.Settings;
import android.text.InputType;
import android.view.View;
import android.view.WindowManager;
import android.widget.*;

public final class MainActivity extends Activity {
    private EditText profileName, endpoint, key, model, steps, query;
    private Switch enabled, globalModel, accessibilityGuard, backgroundDisplay;
    private Spinner profiles, protocol, mode, planner, api;
    private TextView status, result;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String job;
    private String selectedProfileId;
    private EndpointProfiles endpointProfiles;
    private int profileRevision;
    private boolean updatingGuardSwitch;
    private final Runnable frameworkStatus = new Runnable() {
        @Override public void run() { refresh(); handler.postDelayed(this, 1000); }
    };
    private final Runnable poll = new Runnable() {
        @Override public void run() {
            if (job == null) return;
            try {
                Bundle b = getContentResolver().call(BridgeProvider.URI, "poll", job, null);
                if (b == null || b.containsKey("error")) { result.setText("任务已丢失"); job = null; return; }
                if ("running".equals(b.getString("status"))) { result.setText(b.getString("progress")); handler.postDelayed(this, 500); }
                else { result.setText(b.getString("result")); job = null; }
            } catch (Exception e) { result.setText("模块桥接失败"); job = null; }
        }
    };
    @Override public void onCreate(Bundle saved) {
        super.onCreate(saved);
        getWindow().setFlags(WindowManager.LayoutParams.FLAG_SECURE, WindowManager.LayoutParams.FLAG_SECURE);
        Config c = new Config(this);
        try { endpointProfiles = new EndpointProfiles(Config.prefs(this)); }
        catch (IllegalStateException e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); finish(); return; }
        selectedProfileId = endpointProfiles.activeId;
        ScrollView scroll = new ScrollView(this);
        LinearLayout root = new LinearLayout(this); root.setOrientation(LinearLayout.VERTICAL); root.setPadding(32,32,32,32); scroll.addView(root);
        label(root, "MiAIPlus", 26);
        label(root, "默认用官方小爱模型规划控屏；可选外部模型接管小爱主对话及工具规划。", 15);
        enabled = new Switch(this); enabled.setText("启用本机控屏工具"); enabled.setChecked(c.enabled); root.addView(enabled);
        label(root, "控屏规划模型", 16);
        planner = spinner(root, new String[]{"小爱当前模型（无需额外 API）", "独立外部视觉模型循环"});
        planner.setSelection(c.planner.equals("external") ? 1 : 0);
        globalModel = new Switch(this); globalModel.setText("小爱全局使用外部模型（对话与工具规划）"); globalModel.setChecked(c.globalModel); root.addView(globalModel);
        label(root, "开启全局替换后，小爱原生聊天历史、系统提示及工具上下文将发送至下方服务。语音识别、语音合成及原生工具服务继续由系统提供。切换后重启小爱。", 14);
        label(root, "API 配置 · 快捷切换", 18);
        profiles = spinner(root, new String[0]);
        label(root, "选择已保存的配置即可切换；地址、密钥、模型和 API 协议一起保存。启用开关、控屏规划及最大步数为全局设置。", 14);
        profileName = field(root, "配置名称", endpointProfiles.active().name, false);
        endpoint = field(root, "API Base URL（含 /v1）", c.endpoint, false);
        key = field(root, "API Key（本地服务可留空）", c.apiKey, true);
        model = field(root, "模型名称", c.model, false);
        label(root, "模型 API 协议", 16);
        api = spinner(root, new String[]{"自动（GPT-6 系列用 Responses）", "Responses", "Chat Completions"});
        String selectedApi = Config.prefs(this).getString("api", "auto");
        api.setSelection("responses".equals(selectedApi) ? 1 : "chat".equals(selectedApi) ? 2 : 0);
        label(root, "GPT-6.1 Sol 的工具调用需要 Responses；服务使用自定义模型别名时请手动选择。此选项也用于全局替换、记忆和路由请求。", 14);
        button(root, "保存当前 API 配置", v -> saveProfile());
        button(root, "另存为新 API 配置", v -> saveProfileAs());
        button(root, "删除当前 API 配置", v -> deleteProfile());
        button(root, "从服务获取模型列表", v -> fetchModels());
        label(root, "控屏动作协议", 16);
        protocol = spinner(root, new String[]{"phone use：视觉 + function calling", "autoglm：Open-AutoGLM 动作协议"});
        protocol.setSelection(c.protocol.equals("autoglm") ? 1 : 0);
        steps = field(root, "最大步数（1–80）", String.valueOf(c.maxSteps), false); steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        label(root, "默认模式将控屏观察交给小爱当前模型；选择独立外部循环时发到下方端点。纯第三方对话不读取屏幕。地址、密钥和模型由你填写。", 14);
        button(root, "保存配置", v -> save());
        button(root, "开启无障碍服务", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        backgroundDisplay = new Switch(this); backgroundDisplay.setText("在后台虚拟屏执行控屏任务");
        backgroundDisplay.setChecked(c.backgroundDisplay); root.addView(backgroundDisplay);
        label(root, "开启后，控屏任务在独立的后台屏幕上运行，你可以继续使用手机主屏。需要 root 授权 MiAIPlus，并在解锁状态下使用；本机若无 root 或 ROM 限制后台屏，任务会明确失败并停止，不会改到主屏执行。关闭时行为与旧版本一致。", 14);
        accessibilityGuard = new Switch(this); accessibilityGuard.setText("root 控屏前检查无障碍");
        accessibilityGuard.setChecked(AccessibilityGuard.enabled(this)); root.addView(accessibilityGuard);
        label(root, "只在新控屏任务开始前检查真实连接，掉线时用 root 恢复 MiAIPlus 无障碍，连接正常后开始操作。任务执行中不修复，无常驻守护。", 14);
        accessibilityGuard.setOnCheckedChangeListener((view, checked) -> {
            if (updatingGuardSwitch) return;
            accessibilityGuard.setEnabled(false);
            AccessibilityGuard.setEnabled(this, checked, applied -> {
                if (isFinishing() || isDestroyed()) return;
                updatingGuardSwitch = true; accessibilityGuard.setChecked(applied); updatingGuardSwitch = false;
                accessibilityGuard.setEnabled(true); refresh();
            });
        });
        button(root, "应用权限设置", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + getPackageName()))));
        label(root, "控屏会自动允许与本步目标匹配的小米“启动应用”确认，并检查应用确实已打开；其他权限提示需要接管。如仍被后台启动限制，请在其他权限中允许“后台弹出界面”。", 14);
        button(root, "刷新状态", v -> refresh());
        button(root, "安装更新后热重载模块", v -> { HotReload.request(); refresh(); });
        status = label(root, "", 14);
        label(root, "直接测试", 21);
        mode = spinner(root, new String[]{"识屏控屏任务", "第三方模型纯对话"});
        query = field(root, "任务 / 问题", "", false); query.setSingleLine(false); query.setMinLines(2);
        button(root, "开始", v -> start());
        button(root, "停止当前任务", v -> {
            BridgeProvider.cancelActive(); result.setText("已请求停止");
        });
        result = label(root, "", 16); result.setTextIsSelectable(true);
        label(root, "小爱中可说：在 XX 应用里……，或：用第三方模型回答……。\nLSPosed 作用域只选超级小爱，启用后重启小爱进程。停用模块后重启小爱可恢复原行为。", 14);
        setContentView(scroll);
        loadProfile();
        if (saved != null) {
            job = saved.getString("job"); query.setText(saved.getString("query", ""));
            if (selectedProfileId.equals(saved.getString("profileId"))) {
                profileName.setText(saved.getString("profileName")); endpoint.setText(saved.getString("endpoint"));
                key.setText(saved.getString("apiKey")); model.setText(saved.getString("model")); api.setSelection(saved.getInt("api"));
            }
            enabled.setChecked(saved.getBoolean("enabled", c.enabled)); globalModel.setChecked(saved.getBoolean("globalModel", c.globalModel));
            planner.setSelection(saved.getInt("planner")); protocol.setSelection(saved.getInt("protocol"));
            steps.setText(saved.getString("steps", String.valueOf(c.maxSteps))); mode.setSelection(saved.getInt("mode"));
        }
        refresh();
    }
    @Override protected void onResume() { super.onResume(); if (status != null) handler.post(frameworkStatus); handler.removeCallbacks(poll); if (job != null) handler.post(poll); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(poll); handler.removeCallbacks(frameworkStatus); }
    @Override protected void onSaveInstanceState(Bundle out) {
        super.onSaveInstanceState(out);
        if (query == null) return;
        out.putString("job", job); out.putString("query", query.getText().toString());
        out.putString("profileId", selectedProfileId); out.putString("profileName", profileName.getText().toString());
        out.putString("endpoint", endpoint.getText().toString()); out.putString("apiKey", key.getText().toString());
        out.putString("model", model.getText().toString()); out.putInt("api", api.getSelectedItemPosition());
        out.putBoolean("enabled", enabled.isChecked()); out.putBoolean("globalModel", globalModel.isChecked());
        out.putInt("planner", planner.getSelectedItemPosition()); out.putInt("protocol", protocol.getSelectedItemPosition());
        out.putString("steps", steps.getText().toString()); out.putInt("mode", mode.getSelectedItemPosition());
    }
    private boolean save() {
        try {
            boolean external = globalModel.isChecked() || planner.getSelectedItemPosition() == 1;
            EndpointProfiles.Profile selected = profileFromFields(selectedProfileId, profileName.getText().toString()).validated(external);
            int n = Integer.parseInt(steps.getText().toString());
            if (n < 1 || n > 80) throw new IllegalArgumentException("最大步数为 1–80");
            var editor = Config.prefs(this).edit().putBoolean("enabled", enabled.isChecked())
                .putBoolean("globalModel", globalModel.isChecked()).putString("planner", planner.getSelectedItemPosition() == 1 ? "external" : "native")
                .putBoolean("backgroundDisplay", backgroundDisplay.isChecked())
                .putString("protocol", protocol.getSelectedItemPosition() == 1 ? "autoglm" : "phone_use").putInt("maxSteps", n);
            endpointProfiles.write(editor, selected, false);
            if (!editor.commit()) throw new IllegalStateException("保存失败，请重试");
            loadProfile();
            if (!enabled.isChecked()) BridgeProvider.cancelActive();
            Toast.makeText(this, "已保存；改变启用状态后请重启小爱", Toast.LENGTH_SHORT).show(); return true;
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return false; }
    }
    private EndpointProfiles.Profile profileFromFields(String id, String name) {
        return new EndpointProfiles.Profile(id, name, endpoint.getText().toString(), key.getText().toString(),
            model.getText().toString(), new String[]{"auto", "responses", "chat"}[api.getSelectedItemPosition()]);
    }
    private void loadProfile() {
        profileRevision++;
        endpointProfiles = new EndpointProfiles(Config.prefs(this));
        EndpointProfiles.Profile selected = endpointProfiles.active();
        selectedProfileId = selected.id;
        profileName.setText(selected.name); endpoint.setText(selected.endpoint); key.setText(selected.apiKey); model.setText(selected.model);
        api.setSelection("responses".equals(selected.api) ? 1 : "chat".equals(selected.api) ? 2 : 0);
        profiles.setOnItemSelectedListener(null);
        profiles.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item,
            endpointProfiles.profiles.stream().map(p -> p.name).toArray(String[]::new)));
        for (int i = 0; i < endpointProfiles.profiles.size(); i++)
            if (endpointProfiles.profiles.get(i).id.equals(selectedProfileId)) profiles.setSelection(i);
        profiles.setOnItemSelectedListener(new AdapterView.OnItemSelectedListener() {
            @Override public void onNothingSelected(AdapterView<?> parent) { }
            @Override public void onItemSelected(AdapterView<?> parent, View view, int position, long id) {
                String target = endpointProfiles.profiles.get(position).id;
                if (target.equals(selectedProfileId)) return;
                for (int i = 0; i < endpointProfiles.profiles.size(); i++)
                    if (endpointProfiles.profiles.get(i).id.equals(selectedProfileId)) profiles.setSelection(i);
                if (profileFromFields(selectedProfileId, profileName.getText().toString()).encode()
                    .equals(endpointProfiles.active().encode())) switchProfile(target);
                else new android.app.AlertDialog.Builder(MainActivity.this).setTitle("当前 API 配置有未保存的修改")
                    .setMessage("切换前可以保存修改，或放弃修改。")
                    .setPositiveButton("保存并切换", (d, which) -> { if (saveProfile()) switchProfile(target); })
                    .setNeutralButton("放弃并切换", (d, which) -> switchProfile(target)).setNegativeButton("取消", null).show();
            }
        });
    }
    private boolean persistProfile(EndpointProfiles.Profile profile, boolean removeCurrent) {
        try {
            Config config = new Config(this);
            profile = profile.validated(config.globalModel || config.planner.equals("external"));
            var editor = Config.prefs(this).edit();
            endpointProfiles.write(editor, profile, removeCurrent);
            if (!editor.commit()) throw new IllegalStateException("保存失败，请重试");
            loadProfile();
            return true;
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return false; }
    }
    private boolean saveProfile() {
        boolean saved = persistProfile(profileFromFields(selectedProfileId, profileName.getText().toString()), false);
        if (saved) Toast.makeText(this, "API 配置已保存", Toast.LENGTH_SHORT).show();
        return saved;
    }
    private void switchProfile(String id) {
        if (persistProfile(endpointProfiles.find(id), false))
            Toast.makeText(this, "已切换至「" + endpointProfiles.active().name + "」，用于后续新请求", Toast.LENGTH_SHORT).show();
    }
    private void saveProfileAs() {
        EditText name = new EditText(this); name.setSingleLine(true); name.setHint("新配置名称");
        android.app.AlertDialog dialog = new android.app.AlertDialog.Builder(this).setTitle("另存为新 API 配置")
            .setMessage("将当前填写的地址、密钥、模型和 API 协议保存为新配置并切换。")
            .setView(name).setPositiveButton("保存并切换", null).setNegativeButton("取消", null).create();
        dialog.setOnShowListener(d -> dialog.getButton(android.app.AlertDialog.BUTTON_POSITIVE).setOnClickListener(v -> {
            if (persistProfile(profileFromFields(java.util.UUID.randomUUID().toString(), name.getText().toString()), false)) {
                dialog.dismiss(); Toast.makeText(this, "已新增并切换 API 配置", Toast.LENGTH_SHORT).show();
            }
        }));
        dialog.show();
    }
    private void deleteProfile() {
        if (endpointProfiles.profiles.size() == 1) {
            Toast.makeText(this, "请至少保留一套 API 配置；可编辑当前配置或先新增配置", Toast.LENGTH_LONG).show(); return;
        }
        EndpointProfiles.Profile next = endpointProfiles.profiles.stream().filter(p -> !p.id.equals(selectedProfileId)).findFirst().orElseThrow();
        new android.app.AlertDialog.Builder(this).setTitle("删除「" + endpointProfiles.active().name + "」？")
            .setMessage("将删除此配置及密钥，放弃未保存的 API 修改，并切换至「" + next.name + "」。")
            .setPositiveButton("删除并切换", (d, which) -> {
                if (persistProfile(next, true)) Toast.makeText(this, "配置已删除", Toast.LENGTH_SHORT).show();
            }).setNegativeButton("取消", null).show();
    }
    private void start() {
        if (!save()) return;
        if (mode.getSelectedItemPosition() == 0 && planner.getSelectedItemPosition() == 0) {
            result.setText("默认模式请在小爱里提交任务，由小爱当前模型连续调用控屏工具；设置页不会代替主模型规划。独立循环测试请选择外部视觉模型。"); return;
        }
        if (mode.getSelectedItemPosition() == 0) moveTaskToBack(true);
        Bundle extras = new Bundle(); extras.putString("query", query.getText().toString());
        extras.putString("mode", mode.getSelectedItemPosition() == 0 ? "screen" : "chat");
        Bundle b = getContentResolver().call(BridgeProvider.URI, "start", null, extras);
        if (b == null || b.containsKey("error")) { result.setText(b == null ? "无法启动" : b.getString("error")); return; }
        job = b.getString("id"); handler.removeCallbacks(poll); handler.post(poll);
    }
    private void refresh() {
        var p = Config.prefs(this);
        if (accessibilityGuard != null && !AccessibilityGuard.isChanging()) {
            updatingGuardSwitch = true; accessibilityGuard.setChecked(AccessibilityGuard.enabled(this)); updatingGuardSwitch = false;
            accessibilityGuard.setEnabled(true);
        }
        status.setText("热重载：" + HotReload.status() + "\n无障碍：" + (ScreenService.isHealthy() ? "已连接" : "未连接或连接已失效")
            + "\n控屏前检查：" + AccessibilityGuard.status(this) + "\n小爱 Hook：" + p.getString("hook_report", "尚未收到；请在 LSPosed 启用模块并重启小爱")
            + (p.getLong("hook_at", 0) == 0 ? "" : "\n报告时间：" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date(p.getLong("hook_at", 0)))));
    }
    private void fetchModels() {
        String url = endpoint.getText().toString().trim(), apiKey = key.getText().toString();
        int revision = profileRevision;
        Toast.makeText(this, "正在获取模型列表", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String[] models = ModelClient.listModels(url, apiKey);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed() || revision != profileRevision
                        || !url.equals(endpoint.getText().toString().trim()) || !apiKey.equals(key.getText().toString())) return;
                    new android.app.AlertDialog.Builder(this).setTitle("选择支持视觉和工具调用的模型")
                        .setItems(models, (d, which) -> model.setText(models[which])).setNegativeButton("取消", null).show();
                });
            } catch (Exception e) {
                runOnUiThread(() -> Toast.makeText(this, "获取失败：请检查地址和密钥（" + e.getClass().getSimpleName() + "）", Toast.LENGTH_LONG).show());
            }
        }, "model-list").start();
    }
    private TextView label(LinearLayout root, String text, int size) {
        TextView t = new TextView(this); t.setText(text); t.setTextSize(size); t.setPadding(0,16,0,12); root.addView(t); return t;
    }
    private EditText field(LinearLayout root, String hint, String value, boolean secret) {
        label(root, hint, 15); EditText e = new EditText(this); e.setSingleLine(true);
        if (secret) { e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_VARIATION_PASSWORD); e.setImportantForAutofill(View.IMPORTANT_FOR_AUTOFILL_NO); }
        else e.setInputType(InputType.TYPE_CLASS_TEXT | InputType.TYPE_TEXT_FLAG_NO_SUGGESTIONS);
        e.setText(value); root.addView(e); return e;
    }
    private Spinner spinner(LinearLayout root, String[] options) {
        Spinner s = new Spinner(this); s.setAdapter(new ArrayAdapter<>(this, android.R.layout.simple_spinner_dropdown_item, options)); root.addView(s); return s;
    }
    private void button(LinearLayout root, String text, View.OnClickListener listener) {
        Button b = new Button(this); b.setText(text); b.setOnClickListener(listener); root.addView(b);
    }
}
