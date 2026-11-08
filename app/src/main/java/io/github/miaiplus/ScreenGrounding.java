package io.github.miaiplus;

/** App identity is observation context; the planner decides whether the user's goal is complete. */
final class ScreenGrounding {
    static final String POLICY = "观察中的系统前台包名标识当前宿主应用，截图内的图标、水印或页面样式不代表系统前台身份。"
        + "聊天、相册、浏览器中全屏展示的截图/录屏仍是当前宿主应用中的内容，不等于已进入图片中的应用或打开原内容。"
        + "用户要求去目标应用查找、打开原内容时，图片仅是检索线索；需结合系统前台身份、最新截图和页面状态核对作者、标题等内容身份，不能把看见同一张图或只打开应用当成完成。"
        + "根据用户的完整目标和最新观察自主判断是否finish，用message说明完成依据和未完成部分；不确定时用ask_user/take_over。";

    private ScreenGrounding() {}
}
