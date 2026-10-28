package io.github.miaiplus;

import android.content.Context;
import io.github.miaiplus.hook.Reflector;
import java.util.List;
import java.util.Map;
import org.json.JSONObject;

/** First-party plugin; screen execution, ownership and return navigation stay in their original paths. */
final class PhoneUsePlugin {
    private static final String DESCRIPTION = "本机手机控屏。start时传goal取得session和观察；然后每次传session及一个action对象，按新观察继续调用直到finish。剩余动作预算为0时只能finish/take_over/ask_user。STOP、PAUSE或错误表示本轮结束，不得重试或重新提交goal；向用户如实说明未完成和原因。无需额外API，由你逐步规划。默认使用截图JPEG像素坐标(image_pixels)，不是0..1000；也可显式选screen_pixels或normalized，必须匹配观察尺寸。不要复述CONTINUE或用自然语言替代action。系统专用工具优先。";
    private static final String SCHEMA = """
        {"type":"object","properties":{
          "goal":{"type":"string","description":"仅首次开始时填写完整任务"},
          "coordinate_space":{"type":"string","enum":["image_pixels","screen_pixels","normalized"],"description":"默认image_pixels：按附图真实JPEG宽高的像素；screen_pixels按无障碍树物理屏幕像素；normalized为0..1000"},
          "session":{"type":"string","description":"后续动作必填，取自前一步返回值"},
          "action":{"type":"object","properties":{
            "action":{"type":"string","enum":["tap","double_tap","long_press","swipe","type","launch","back","home","wait","finish","take_over","ask_user"]},
            "element":{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2},
            "start":{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2},
            "end":{"type":"array","items":{"type":"number"},"minItems":2,"maxItems":2},
            "intent":{"type":"string","description":"每步用简短中文说明当前操作意图，例如点击搜索框、查找订单，显示在灵动岛；不含隐私内容、坐标或推理过程。不要用message填写普通意图。"},
            "text":{"type":"string"},"app":{"type":"string","description":"launch使用首次观察可启动应用列表中的包名，不猜测包名或Intent"},
            "message":{"type":"string"},"requires_confirmation":{"type":"boolean"},
            "risk_level":{"type":"string","enum":["normal","sensitive","critical"],"description":"付款转账、账号安全、泄露凭证、关闭安全防护或永久授权标记critical；普通敏感提交标记sensitive"},
            "permission_review":{"type":"object","properties":{
              "token":{"type":"string"},"decision":{"type":"string","enum":["allow","ask_user","deny"]},"reason":{"type":"string"}
            },"required":["token","decision","reason"],"additionalProperties":false},
            "duration_ms":{"type":"integer"}
          },"required":["action"]}
        }}
        """;
    private static String description(String name) {
        return (PhoneUseRouting.FOREGROUND_TOOL.equals(name) ? "前台控屏：直接操作用户当前主屏。" : "后台控屏：在独立虚拟屏操作应用，尽量不打扰用户主屏。")
            + "根据用户要求选择此工具；未指定后台时使用前台工具。整个会话继续使用同一个工具，包括后台经用户同意切换前台以后。" + DESCRIPTION;
    }

    private static final String LEGACY_DESCRIPTION = "本机全应用识屏控屏工具；默认由小爱当前模型逐步规划，无需另配API。用户要求在手机已安装应用中执行 UI 操作（包括微信）时，首次原样传入完整请求；CONTINUE表示尚未完成，按返回协议继续调用同一工具，每次只执行一个动作；"
        + "系统设置、搜索及已有专用工具优先走原功能。用户明确指定第三方模型对话时，query 使用前缀『模型对话：』加用户问题；此模式不读取屏幕。"
        + "控屏由 MiAIPlus 在本机执行；默认由你逐步规划，只有用户选择独立外部模式才另调其配置端点。返回 PAUSE 时提示用户接管，不要自行重试。";
    private static final String LEGACY_POLICY = "\n\n[MiAIPlus 扩展能力，覆盖上文同一工具的旧范围说明]\n"
        + "逐步控屏提供两个独立函数：miaiplus_phone_use 操作当前主屏，miaiplus_background_phone_use 操作独立虚拟屏。根据用户要求选择；未指定后台时用前台工具，不受全局旧版执行屏幕设置影响。选定后整个会话必须使用同一个函数，包括后台任务经授权切到前台以后。两个函数都是首次{goal:完整任务}，之后{session:返回会话号,coordinate_space:screen_pixels,action:{action:tap,element:[树中目标中心x,树中目标中心y]}}，直至finish。不要把自然语言动作再次当成首次任务。voiceassist__controlApp 已由本机 MiAIPlus 接管，不再受原云端的应用白名单或微信不支持限制。"
        + "对于用户明确要求的应用内 UI 操作，已有专用能力优先；无专用能力时可用 cli 调用 voiceassist__controlApp，原样传递完整任务。"
        + "包括微信等应用的页面操作、消息等，仅在用户明确要求时执行。"
        + "需要后续识屏、点击或输入的任务先开始所选前台或后台控屏会话，再用launch打开目标，不要先用app start打开应用；仅打开应用的普通请求仍保留原功能。"
        + "控屏会话内打开应用使用同一个控屏工具的launch动作，app从首次观察的应用列表选择包名，不要另调app start或原生打开应用工具；只有观察到目标前台才报告打开。当前launch对应的小米启动确认由执行器自动允许本次；其他系统权限应接管。"
        + "每个控屏action附intent简短中文说明当前意图供灵动岛显示；不含隐私、坐标或推理过程，普通意图不要放入触发确认的message。"
        + "用户说『用第三方模型回答/对话』时调用 cli: voiceassist__controlApp \"模型对话：用户问题\"，原样返回结果。"
        + "普通对话、联网搜索、系统设置、智能家居仍使用现有主模型、路由和工具（主模型可由用户全局替换）。不要把这些任务改派到控屏。"
        + "工具返回CONTINUE时必须按返回的session继续调用启动会话的同一个控屏工具；只有旧版voiceassist__controlApp会话继续使用MIAIPLUS:动作JSON，依据最新图片或无障碍树规划；此模式无需额外视觉模型。不要只复述观察后结束。剩余动作预算为0时只能根据最后观察finish或take_over/ask_user，不能执行更多操作。STOP、PAUSE或工具报错表示本轮结束，不要重试、重新提交goal或改用其他工具重开同一任务；向用户说明未完成及原因，不要伪称完成。\n";

    private static final List<PluginToolSpec> SPECS = List.of(
        spec("phone_foreground", PhoneUseRouting.FOREGROUND_TOOL, "前台控屏"),
        spec("phone_background", PhoneUseRouting.BACKGROUND_TOOL, "后台控屏"));

    static String legacyDescription() { return LEGACY_DESCRIPTION; }

    static String promptPolicy(java.util.Set<String> names) {
        boolean foreground = names.contains(PhoneUseRouting.FOREGROUND_TOOL);
        boolean background = names.contains(PhoneUseRouting.BACKGROUND_TOOL);
        if (!foreground && !background) return "";
        String available = foreground && background
            ? "逐步控屏提供两个独立函数：miaiplus_phone_use 操作当前主屏，miaiplus_background_phone_use 操作独立虚拟屏。根据用户要求选择；未指定后台时用前台工具，不受全局旧版执行屏幕设置影响。"
            : foreground
                ? "当前仅启用 miaiplus_phone_use 前台控屏函数；后台控屏未启用，不要调用后台函数。不受全局旧版执行屏幕设置影响。"
                : "当前仅启用 miaiplus_background_phone_use 后台控屏函数；前台控屏未启用，不要调用前台函数。仅在用户要求后台执行时调用；未指定后台时说明前台插件未启用。";
        return LEGACY_POLICY.replace("逐步控屏提供两个独立函数：miaiplus_phone_use 操作当前主屏，miaiplus_background_phone_use 操作独立虚拟屏。根据用户要求选择；未指定后台时用前台工具，不受全局旧版执行屏幕设置影响。", available)
            .replace("两个函数都是", "已启用的控屏函数都是");
    }

    static List<PluginToolSpec> specs() { return SPECS; }

    private static PluginToolSpec spec(String id, String name, String title) {
        return new PluginToolSpec(id, "builtin", name, name, title, description(name), SCHEMA, 245000L);
    }

    static boolean owns(PluginToolSpec spec) {
        return specs().stream().anyMatch(candidate -> candidate.equals(spec));
    }

    static Object execute(Context context, ClassLoader loader, PluginToolSpec spec, Object arguments,
        Object executionContext) {
        String text;
        try {
            JSONObject request = new JSONObject(arguments.toString());
            String query = PhoneUseRouting.encode(spec.name(), request);
            Object service = Reflector.newInstance(Reflector.findClass(
                "com.xiaomi.voiceassistant.UIAgentServiceForOSBot", loader));
            try (var origin = ConversationReturn.bind(executionContext)) {
                Object result = Reflector.callMethod(service, "controlApp", query);
                text = String.valueOf(Reflector.callMethod(result, "getText"));
                NativeTurnLifecycle.observe(context, executionContext, text);
                if (Boolean.TRUE.equals(Reflector.callMethod(result, "isError")))
                    return NativePluginTools.failure(loader, PhoneUseResult.stopped(text), true);
            }
        } catch (Exception error) {
            return NativePluginTools.failure(loader, PhoneUseResult.stopped(error instanceof org.json.JSONException
                ? "缺少goal或session/action参数" : "控屏工具调用失败"), true);
        }
        boolean terminal = ForegroundConsent.endsNativeTurn(text);
        return NativePluginTools.success(loader, text,
            terminal ? Map.of("cli_internal_direct_answer", true) : Map.of(), terminal);
    }

    private PhoneUsePlugin() { }
}
