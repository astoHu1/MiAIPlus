package io.github.miaiplus;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public class SystemDialogPolicyTest {
    private static final String SECURITY = "com.miui.securitycenter";
    private static String group(String title) {
        return "android.widget.TextView [0,0][1000,200] window=1 id=" + SECURITY
            + ":id/permission_group_title enabled=true text=" + title + "\n";
    }
    private static ScreenService.Frame frame(String owner, String tree) {
        return ScreenService.Frame.physical("image", tree, 1000, 1000, owner);
    }
    private static Action action(String name) throws Exception {
        return new Action(new JSONObject().put("action", name)
            .put("element", new JSONArray().put(500).put(500)));
    }

    @Test public void launchSelectionAndOrdinarySecurityPagesAreModelOperable() {
        assertFalse(SystemDialogPolicy.requiresApproval(frame(SECURITY, "首页 清理 电池")));
        for (String title : new String[]{"启动应用", "打开应用", "选择应用"}) {
            ScreenService.Frame prompt = frame(SECURITY, group(title));
            assertFalse(title, SystemDialogPolicy.requiresApproval(prompt));
            assertFalse(title, SystemDialogPolicy.requiresManual(prompt));
            assertTrue(title, SystemDialogPolicy.isAppSelection(prompt.foreground(), prompt.tree()));
        }
        assertTrue(SystemDialogPolicy.isAppSelection("android/com.android.internal.app.ResolverActivity", ""));
        assertTrue(SystemDialogPolicy.isAppSelection("com.android.intentresolver", "TextView text=打开方式\n"));
        String untitledChooser = "android.widget.TextView [82,3003][383,3115] window=1 "
            + "id=com.android.intentresolver:id/text1 enabled=true text=MiAIPlus choice A\n";
        assertTrue(SystemDialogPolicy.isAppSelection("com.android.intentresolver", untitledChooser));
        assertFalse(SystemDialogPolicy.isAppSelection("android", untitledChooser));
        assertFalse(SystemDialogPolicy.isAppSelection("untrusted.app", untitledChooser));
        assertFalse(SystemDialogPolicy.requiresApproval(frame("com.android.intentresolver", untitledChooser)));
    }

    @Test public void privilegesRequireReviewAndUnknownTitlesAreNotSelectors() {
        assertTrue(SystemDialogPolicy.requiresApproval(frame(SECURITY, group("相机"))));
        assertTrue(SystemDialogPolicy.requiresApproval(frame("com.android.permissioncontroller", "")));
        assertTrue(SystemDialogPolicy.requiresApproval(frame("com.lbe.security.miui/.Dialog", "")));
        assertFalse(SystemDialogPolicy.isAppSelection(SECURITY, group("位置") + "TextView text=选择应用\n"));
        assertFalse(SystemDialogPolicy.isAppSelection("untrusted.app", group("启动应用")));
        assertFalse(SystemDialogPolicy.requiresApproval(frame("untrusted.app", group("相机"))));
    }

    @Test public void securityChangesRemainManualEvenForAutomaticPresets() {
        for (String title : new String[]{"设备管理员", "开启无障碍服务", "允许安装未知应用", "关闭安全保护"})
            assertTrue(title, SystemDialogPolicy.requiresManual(frame(SECURITY, group(title))));
        assertTrue(SystemDialogPolicy.requiresManual(frame("com.miui.packageinstaller", "安装")));
        assertFalse(SystemDialogPolicy.requiresManual(frame(SECURITY, group("相机"))));
    }

    @Test public void unrelatedPageTextDoesNotTurnAnOrdinaryGrantIntoASevereGrant() {
        String tree = group("相机")
            + "android.widget.FrameLayout [0,0][1000,1000] enabled=true text=关闭安全保护 设备管理员\n"
            + "android.widget.Button [100,100][300,300] enabled=true clickable text=始终允许\n";
        assertFalse(SystemDialogPolicy.requiresManual(frame(SECURITY, tree)));
        assertFalse(SystemDialogPolicy.requiresManual(frame("com.android.permissioncontroller", tree)));
        String androidTitle = "android.widget.TextView [0,0][1000,100] id=android:id/alertTitle enabled=true text=激活设备管理员\n";
        assertTrue(SystemDialogPolicy.requiresManual(frame("com.android.permissioncontroller", androidTitle)));
    }

    @Test public void newlyAppearedAndChangedPromptsReplanBeforeDispatch() throws Exception {
        ScreenService.Frame camera = frame(SECURITY, group("相机"));
        ScreenService.Frame location = frame(SECURITY, group("位置"));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(action("tap"), camera, camera, false));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(action("tap"), camera, location, true));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(action("tap"), frame(SECURITY, "首页"), camera, true));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(action("tap"), camera, frame(SECURITY, "首页"), true));
        SystemDialogPolicy.verify(action("tap"), camera, camera, true);
        SystemDialogPolicy.verify(action("tap"), frame(SECURITY, group("启动应用")),
            frame(SECURITY, group("启动应用")), false);
        SystemDialogPolicy.verify(action("back"), camera, camera, false);
    }
}
