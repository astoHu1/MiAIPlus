package io.github.miaiplus;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class PhoneUsePermissionPolicyTest {
    private static final String SECURITY = "com.miui.securitycenter";

    private static Action tap() throws Exception {
        return new Action(new JSONObject().put("action", "tap").put("element", new JSONArray("[200,200]")));
    }

    private static Action center(String name) throws Exception {
        return new Action(new JSONObject().put("action", name)
            .put("element", new JSONArray().put(500).put(500)));
    }

    private static ScreenService.Frame frame(String label) {
        return frame("app", "android.widget.Button [100,100][300,300] enabled=true clickable text=" + label + "\n");
    }

    private static ScreenService.Frame frame(String owner, String tree) {
        return new ScreenService.Frame("image", tree, 1000, 1000, owner, 1000, 1000, 0, "");
    }

    private static String group(String title) {
        return "android.widget.TextView [0,0][1000,200] window=1 id=" + SECURITY
            + ":id/permission_group_title enabled=true text=" + title + "\n";
    }

    @Test public void sensitiveMetadataOrCommitLabelsUseTheSelectedPresetButCriticalCannotBeDowngraded() throws Exception {
        for (String field : new String[]{"requires_confirmation", "message", "risk_level"}) {
            Action action = tap();
            action.data.put(field, field.equals("requires_confirmation") ? true : field.equals("message") ? "发送本条消息" : "sensitive");
            assertTrue(PhoneUsePermissionPolicy.needsReview(action, frame("确定")));
            assertTrue(PhoneUsePermissionPolicy.requiresManual("manual", action, frame("确定")));
            assertFalse(PhoneUsePermissionPolicy.requiresManual("auto", action, frame("确定")));
            assertFalse(PhoneUsePermissionPolicy.requiresManual("yolo", action, frame("确定")));
            assertTrue(PhoneUsePermissionPolicy.requiresManual("unknown", action, frame("确定")));
        }
        Action action = tap();
        action.data.put("risk_level", "critical");
        assertTrue(PhoneUsePermissionPolicy.requiresManual("yolo", action, frame("确定")));
        action.data.put("risk_level", "sensitive").put("message", "确认转账给指定收款人");
        assertTrue(PhoneUsePermissionPolicy.requiresManual("yolo", action, frame("确定")));
        for (String label : new String[]{"发送", "发布", "删除", "确认删除", "提交订单", "send", "publish now"})
            assertTrue(label, PhoneUsePermissionPolicy.needsReview(tap(), frame(label)));
        Action normal = tap();
        normal.data.put("risk_level", "normal");
        assertFalse(PhoneUsePermissionPolicy.needsReview(normal, frame("打开搜索")));
    }

    @Test public void terminalMessagesAndWaitDoNotBecomeActionsToConfirm() throws Exception {
        for (String name : new String[]{"wait", "finish", "ask_user", "take_over"}) {
            Action action = new Action(new JSONObject().put("action", name).put("message", "确认支付")
                .put("risk_level", "critical").put("requires_confirmation", true));
            assertFalse(name, PhoneUsePermissionPolicy.needsReview(action, frame("确认支付")));
            assertFalse(name, PhoneUsePermissionPolicy.critical(action, frame("确认支付")));
        }
    }

    @Test public void severeCommitTargetsAlwaysRequireManualEvenInYolo() throws Exception {
        for (String label : new String[]{"确认支付", "立即付款", "确认转账", "提现", "注销账号", "修改登录密码",
            "关闭安全保护", "始终允许", "永久授权", "发送密码", "导出私钥", "Pay now", "Reset password",
            "支付 ¥100", "Pay $10.50", "恢复出厂设置"}) {
            assertTrue(label, PhoneUsePermissionPolicy.needsReview(tap(), frame(label)));
            assertTrue(label, PhoneUsePermissionPolicy.requiresManual("auto", tap(), frame(label)));
            assertTrue(label, PhoneUsePermissionPolicy.requiresManual("yolo", tap(), frame(label)));
        }
        ScreenService.Frame large = new ScreenService.Frame("image",
            "android.widget.Button [200,200][600,600] enabled=true clickable text=确认支付\n",
            2000, 2000, "app", 500, 500, 0, "");
        assertTrue(PhoneUsePermissionPolicy.critical(tap(), large));
        assertTrue(PhoneUsePermissionPolicy.critical(tap(), frame("app",
            "android.widget.Button [100,100][300,300] enabled=true clickable text=支付 description=支付\n")));
    }

    @Test public void onceOnlyGrantIsReviewedUnrelatedRiskTextDoesNotElevateItAndDeviceAdminIsManual() throws Exception {
        String root = "android.widget.FrameLayout [0,0][1000,1000] enabled=true text=确认支付 关闭安全保护\n";
        String target = "android.widget.Button [100,100][300,300] enabled=true clickable text=仅本次允许\n";
        String unrelated = "android.widget.TextView [600,100][900,300] enabled=true text=始终允许\n";
        ScreenService.Frame frame = frame("com.android.permissioncontroller", root + target + unrelated);
        assertTrue(PhoneUsePermissionPolicy.needsReview(tap(), frame));
        assertFalse(PhoneUsePermissionPolicy.requiresManual("yolo", tap(), frame));
        assertFalse(PhoneUsePermissionPolicy.critical(tap(), frame("app", root + target + unrelated)));
        ScreenService.Frame admin = frame("com.android.permissioncontroller",
            "android.widget.TextView [0,0][1000,100] id=android:id/alertTitle enabled=true text=激活设备管理\n"
                + "android.widget.Button [100,100][300,300] enabled=true clickable text=允许\n");
        assertTrue(PhoneUsePermissionPolicy.requiresManual("yolo", tap(), admin));
    }

    @Test public void unknownXiaomiGrantButtonIsReviewedButAppSelectionNeedsNoExtraConsent() throws Exception {
        String selectorTitle = "android.widget.TextView [0,0][1000,100] enabled=true "
            + "id=com.miui.securitycenter:id/permission_group_title text=选择应用\n";
        for (String label : new String[]{"允许", "同意", "授权", "本次允许", "仅本次允许", "allow", "allow once", "agree", "grant"}) {
            ScreenService.Frame unknown = frame(SECURITY,
                "android.widget.Button [100,100][300,300] enabled=true clickable text=" + label + "\n");
            assertFalse(SystemDialogPolicy.requiresApproval(unknown));
            assertTrue(label, PhoneUsePermissionPolicy.needsReview(tap(), unknown));
            assertTrue(label, PhoneUsePermissionPolicy.requiresManual("manual", tap(), unknown));
            assertFalse(label, PhoneUsePermissionPolicy.requiresManual("yolo", tap(), unknown));
            assertFalse(label, PhoneUsePermissionPolicy.needsReview(tap(), frame(SECURITY, selectorTitle + unknown.tree())));
        }
        assertFalse(PhoneUsePermissionPolicy.needsReview(tap(), frame(SECURITY,
            "android.widget.Button [100,100][300,300] enabled=true clickable text=电池\n")));
        ScreenService.Frame chooser = frame(SECURITY, selectorTitle
            + "android.widget.Button [100,100][300,300] enabled=true clickable text=地图\n");
        Action described = tap();
        described.data.put("message", "选择地图应用");
        assertFalse(PhoneUsePermissionPolicy.needsReview(described, chooser));
        described.data.put("requires_confirmation", true);
        assertTrue(PhoneUsePermissionPolicy.needsReview(described, chooser));
    }

    @Test public void confirmationUsesButtonLabelOrExplicitDescriptionButNeverInputContents() throws Exception {
        Action action = tap();
        assertEquals("点击目标控件；目标控件：确认支付", PhoneUsePermissionPolicy.confirmationMessage(action, frame("确认支付")));
        action.data.put("message", "完整说明\n" + "对象和后果".repeat(30));
        assertEquals(action.data.getString("message"), PhoneUsePermissionPolicy.confirmationMessage(action, frame("确认支付")));
        Action type = new Action(new JSONObject().put("action", "type").put("text", "private-value"));
        String message = PhoneUsePermissionPolicy.confirmationMessage(type, frame("app",
            "android.widget.EditText [100,100][300,300] enabled=true editable focused text=existing-private-value\n"));
        assertEquals("填写目标输入框", message);
        assertFalse(message.contains("private"));
    }

    @Test public void reviewSchemaIsStrict() throws Exception {
        JSONObject schema = PhoneUsePermissionPolicy.reviewSchema();
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals(3, schema.getJSONArray("required").length());
        assertEquals(2000, schema.getJSONObject("properties").getJSONObject("reason").getInt("maxLength"));
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
        assertFalse(SystemDialogPolicy.isAppSelection(SECURITY, group("位置") + "TextView text=选择应用\n"));
        assertFalse(SystemDialogPolicy.isAppSelection("untrusted.app", group("启动应用")));
    }

    @Test public void systemPrivilegesRequireReviewAndSecurityChangesStayManualDespiteUnrelatedText() {
        assertTrue(SystemDialogPolicy.requiresApproval(frame(SECURITY, group("相机"))));
        assertTrue(SystemDialogPolicy.requiresApproval(frame("com.android.permissioncontroller", "")));
        assertTrue(SystemDialogPolicy.requiresApproval(frame("com.lbe.security.miui/.Dialog", "")));
        assertFalse(SystemDialogPolicy.requiresApproval(frame("untrusted.app", group("相机"))));
        for (String title : new String[]{"设备管理员", "开启无障碍服务", "允许安装未知应用", "关闭安全保护"})
            assertTrue(title, SystemDialogPolicy.requiresManual(frame(SECURITY, group(title))));
        assertTrue(SystemDialogPolicy.requiresManual(frame("com.miui.packageinstaller", "安装")));
        assertFalse(SystemDialogPolicy.requiresManual(frame(SECURITY, group("相机"))));
        String tree = group("相机")
            + "android.widget.FrameLayout [0,0][1000,1000] enabled=true text=关闭安全保护 设备管理员\n"
            + "android.widget.Button [100,100][300,300] enabled=true clickable text=始终允许\n";
        assertFalse(SystemDialogPolicy.requiresManual(frame(SECURITY, tree)));
        assertFalse(SystemDialogPolicy.requiresManual(frame("com.android.permissioncontroller", tree)));
        String androidTitle = "android.widget.TextView [0,0][1000,100] id=android:id/alertTitle enabled=true text=激活设备管理员\n";
        assertTrue(SystemDialogPolicy.requiresManual(frame("com.android.permissioncontroller", androidTitle)));
    }

    @Test public void newlyAppearedAndChangedSystemPromptsReplanBeforeDispatch() throws Exception {
        ScreenService.Frame camera = frame(SECURITY, group("相机"));
        ScreenService.Frame location = frame(SECURITY, group("位置"));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(center("tap"), camera, camera, false));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(center("tap"), camera, location, true));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(center("tap"), frame(SECURITY, "首页"), camera, true));
        assertThrows(ScreenService.ObservationChanged.class,
            () -> SystemDialogPolicy.verify(center("tap"), camera, frame(SECURITY, "首页"), true));
        SystemDialogPolicy.verify(center("tap"), camera, camera, true);
        SystemDialogPolicy.verify(center("tap"), frame(SECURITY, group("启动应用")),
            frame(SECURITY, group("启动应用")), false);
        SystemDialogPolicy.verify(center("back"), camera, camera, false);
    }
}
