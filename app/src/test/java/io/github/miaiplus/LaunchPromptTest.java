package io.github.miaiplus;

import java.util.List;
import org.junit.Test;
import static org.junit.Assert.*;

public class LaunchPromptTest {
    private static final List<String> CALLERS = List.of("MiAIPlus", "io.github.miaiplus");
    private static final List<String> TARGETS = List.of("测试应用", "fixture.target");
    @Test public void recognizesXiaomiRequestWithQuotedOrWrappedNames() {
        for (String request : new String[]{"MiAIPlus 想要打开\nfixture.target，是否允许？",
            "“MiAIPlus”尝试打开“测试应用”", "MiAIPlus 正在尝试打开 测试应用"})
            assertTrue(LaunchPrompt.matches("com.miui.securitycenter", "启动应用", request,
                List.of("启动应用", request, "本次允许", "始终允许", "拒绝"), CALLERS, TARGETS));
    }
    @Test public void rejectsUnrelatedCallerTargetAndNonLaunchPermissions() {
        for (String request : new String[]{"其他应用想要打开fixture.target，是否允许？",
            "MiAIPlus想要打开其他应用，是否允许？", "MiAIPlus想要打开fixture.target.evil，是否允许？",
            "MiAIPlus想要打开fixture.target并读取通讯录，是否允许？", "MiAIPlus请求访问相机"})
            assertFalse(LaunchPrompt.matches("com.miui.securitycenter", "启动应用", request,
                List.of(request, "本次允许", "拒绝"), CALLERS, TARGETS));
    }
    @Test public void otherSystemPermissionActivityIsNotAutoApproved() {
        assertFalse(LaunchPrompt.matches("com.miui.securitycenter", "相机", "MiAIPlus想要打开fixture.target，是否允许？",
            List.of("MiAIPlus想要打开fixture.target，是否允许？", "本次允许", "拒绝"), CALLERS, TARGETS));
    }
    @Test public void appContentCannotImpersonateSystemConfirmation() {
        assertFalse(LaunchPrompt.matches("fixture.target", "启动应用", "MiAIPlus想要打开fixture.target，是否允许？",
            List.of("MiAIPlus想要打开fixture.target，是否允许？", "本次允许", "拒绝"), CALLERS, TARGETS));
        assertFalse(LaunchPrompt.matches("com.miui.securitycenter", "启动应用", "MiAIPlus想要打开fixture.target，是否允许？",
            List.of("MiAIPlus想要打开fixture.target，是否允许？", "始终允许", "拒绝"), CALLERS, TARGETS));
    }
}
