package io.github.miaiplus;

/** Short user-facing intent only; never display raw reasoning, typed text, or coordinates. */
public final class TaskProgress {
    private TaskProgress() {}
    public static String compact(String text) {
        if (text == null) return "";
        StringBuilder out = new StringBuilder();
        text.codePoints().filter(c -> !Character.isISOControl(c) && Character.getType(c) != Character.FORMAT)
            .limit(60).forEach(out::appendCodePoint);
        return out.toString().strip();
    }
    public static String goal(String goal) {
        String value = compact(goal);
        return value.isEmpty() ? "正在理解任务" : "准备：" + value;
    }
    public static String intent(Action action) {
        Object supplied = action.data.opt("intent");
        String value = supplied instanceof String ? compact((String)supplied) : "";
        if (!value.isEmpty()) return value;
        return switch (action.name) {
            case "tap" -> "点击目标控件";
            case "double_tap" -> "双击目标控件";
            case "long_press" -> "长按目标控件";
            case "swipe" -> "滑动查找目标内容";
            case "type" -> "填写目标输入框";
            case "launch" -> "打开目标应用";
            case "back" -> "返回上一页";
            case "home" -> "返回桌面";
            case "wait" -> "等待页面加载";
            case "finish" -> "确认任务完成";
            case "take_over", "ask_user" -> "等待你接管";
            default -> "正在操作手机";
        };
    }
    public static String waiting(String intent) { return compact(intent) + " · 正在判断下一步"; }
}
