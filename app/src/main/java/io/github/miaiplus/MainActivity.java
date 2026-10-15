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
    private EditText endpoint, key, model, steps, query;
    private Switch enabled, globalModel;
    private Spinner protocol, mode, planner;
    private TextView status, result;
    private final Handler handler = new Handler(Looper.getMainLooper());
    private String job;
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
        endpoint = field(root, "API Base URL（含 /v1）", c.endpoint, false);
        key = field(root, "API Key（本地服务可留空）", c.apiKey, true);
        model = field(root, "模型名称", c.model, false);
        button(root, "从服务获取模型列表", v -> fetchModels());
        label(root, "控屏动作协议", 16);
        protocol = spinner(root, new String[]{"computer：视觉 + function calling", "autoglm：Open-AutoGLM 动作协议"});
        protocol.setSelection(c.protocol.equals("autoglm") ? 1 : 0);
        steps = field(root, "最大步数（1–80）", String.valueOf(c.maxSteps), false); steps.setInputType(InputType.TYPE_CLASS_NUMBER);
        label(root, "默认模式将控屏观察交给小爱当前模型；选择独立外部循环时发到下方端点。纯第三方对话不读取屏幕。地址、密钥和模型由你填写。", 14);
        button(root, "保存配置", v -> save());
        button(root, "开启无障碍服务", v -> startActivity(new Intent(Settings.ACTION_ACCESSIBILITY_SETTINGS)));
        button(root, "应用权限设置", v -> startActivity(new Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS,
            android.net.Uri.parse("package:" + getPackageName()))));
        label(root, "小米系统如无法自动打开应用，请在其他权限中允许“后台弹出界面”。权限弹窗出现时请手动处理后重新发起任务。", 14);
        button(root, "刷新状态", v -> refresh());
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
        if (saved != null) { job = saved.getString("job"); query.setText(saved.getString("query", "")); }
        refresh();
    }
    @Override protected void onResume() { super.onResume(); if (status != null) refresh(); handler.removeCallbacks(poll); if (job != null) handler.post(poll); }
    @Override protected void onPause() { super.onPause(); handler.removeCallbacks(poll); }
    @Override protected void onSaveInstanceState(Bundle out) { super.onSaveInstanceState(out); out.putString("job", job); out.putString("query", query.getText().toString()); }
    private boolean save() {
        try {
            String url = endpoint.getText().toString().trim();
            boolean external = globalModel.isChecked() || planner.getSelectedItemPosition() == 1;
            if (external || !url.isBlank()) url = Config.validateEndpoint(url);
            int n = Integer.parseInt(steps.getText().toString());
            if (n < 1 || n > 80) throw new IllegalArgumentException("最大步数为 1–80");
            if (external && model.getText().toString().isBlank()) throw new IllegalArgumentException("请填写模型名称");
            Config.prefs(this).edit().putBoolean("enabled", enabled.isChecked()).putString("endpoint", url)
                .putString("apiKey", key.getText().toString()).putString("model", model.getText().toString().trim())
                .putBoolean("globalModel", globalModel.isChecked()).putString("planner", planner.getSelectedItemPosition() == 1 ? "external" : "native")
                .putString("protocol", protocol.getSelectedItemPosition() == 1 ? "autoglm" : "computer").putInt("maxSteps", n).apply();
            if (!enabled.isChecked()) BridgeProvider.cancelActive();
            Toast.makeText(this, "已保存；改变启用状态后请重启小爱", Toast.LENGTH_SHORT).show(); return true;
        } catch (Exception e) { Toast.makeText(this, e.getMessage(), Toast.LENGTH_LONG).show(); return false; }
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
        status.setText("无障碍：" + (ScreenService.instance == null ? "未连接" : "已连接") + "\n小爱 Hook：" + p.getString("hook_report", "尚未收到；请在 LSPosed 启用模块并重启小爱")
            + (p.getLong("hook_at", 0) == 0 ? "" : "\n报告时间：" + new java.text.SimpleDateFormat("MM-dd HH:mm:ss", java.util.Locale.CHINA).format(new java.util.Date(p.getLong("hook_at", 0)))));
    }
    private void fetchModels() {
        String url = endpoint.getText().toString().trim(), apiKey = key.getText().toString();
        Toast.makeText(this, "正在获取模型列表", Toast.LENGTH_SHORT).show();
        new Thread(() -> {
            try {
                String[] models = ModelClient.listModels(url, apiKey);
                runOnUiThread(() -> {
                    if (isFinishing() || isDestroyed()) return;
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
