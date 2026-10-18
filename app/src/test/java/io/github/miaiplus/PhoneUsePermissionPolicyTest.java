package io.github.miaiplus;

import static org.junit.Assert.*;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class PhoneUsePermissionPolicyTest {
    private static Action tap() throws Exception {
        return new Action(new JSONObject().put("action", "tap").put("element", new JSONArray("[200,200]")));
    }

    private static ScreenService.Frame frame(String label) {
        return frame("app", "android.widget.Button [100,100][300,300] enabled=true clickable text=" + label + "\n");
    }

    private static ScreenService.Frame frame(String owner, String tree) {
        return new ScreenService.Frame("image", tree, 1000, 1000, owner, 1000, 1000, 0, "");
    }

    @Test public void explicitSensitiveMetadataUsesTheSelectedPreset() throws Exception {
        for (String field : new String[]{"requires_confirmation", "message", "risk_level"}) {
            Action action = tap();
            action.data.put(field, field.equals("requires_confirmation") ? true : field.equals("message") ? "发送本条消息" : "sensitive");
            assertTrue(PhoneUsePermissionPolicy.needsReview(action, frame("确定")));
            assertTrue(PhoneUsePermissionPolicy.requiresManual("manual", action, frame("确定")));
            assertFalse(PhoneUsePermissionPolicy.requiresManual("auto", action, frame("确定")));
            assertFalse(PhoneUsePermissionPolicy.requiresManual("yolo", action, frame("确定")));
            assertTrue(PhoneUsePermissionPolicy.requiresManual("unknown", action, frame("确定")));
        }
    }

    @Test public void ordinaryNavigationAndNormalRiskDoNotNeedReview() throws Exception {
        Action action = tap();
        action.data.put("risk_level", "normal");
        assertFalse(PhoneUsePermissionPolicy.needsReview(action, frame("打开搜索")));
    }

    @Test public void terminalMessagesAndWaitDoNotBecomeActionsToConfirm() throws Exception {
        for (String name : new String[]{"wait", "finish", "ask_user", "take_over"}) {
            Action action = new Action(new JSONObject().put("action", name).put("message", "确认支付")
                .put("risk_level", "critical").put("requires_confirmation", true));
            assertFalse(name, PhoneUsePermissionPolicy.needsReview(action, frame("确认支付")));
            assertFalse(name, PhoneUsePermissionPolicy.critical(action, frame("确认支付")));
        }
    }

    @Test public void ordinaryIrreversibleCommitLabelsNeedReview() throws Exception {
        for (String label : new String[]{"发送", "发布", "删除", "确认删除", "提交订单", "send", "publish now"})
            assertTrue(label, PhoneUsePermissionPolicy.needsReview(tap(), frame(label)));
    }

    @Test public void severeCommitTargetsAlwaysRequireManualEvenInYolo() throws Exception {
        for (String label : new String[]{"确认支付", "立即付款", "确认转账", "提现", "注销账号", "修改登录密码",
            "关闭安全保护", "始终允许", "永久授权", "发送密码", "导出私钥", "Pay now", "Reset password",
            "支付 ¥100", "Pay $10.50", "恢复出厂设置"}) {
            assertTrue(label, PhoneUsePermissionPolicy.needsReview(tap(), frame(label)));
            assertTrue(label, PhoneUsePermissionPolicy.requiresManual("auto", tap(), frame(label)));
            assertTrue(label, PhoneUsePermissionPolicy.requiresManual("yolo", tap(), frame(label)));
        }
    }

    @Test public void modelCriticalClassificationCannotBeDowngradedByYolo() throws Exception {
        Action action = tap();
        action.data.put("risk_level", "critical");
        assertTrue(PhoneUsePermissionPolicy.requiresManual("yolo", action, frame("确定")));
        action.data.put("risk_level", "sensitive").put("message", "确认转账给指定收款人");
        assertTrue(PhoneUsePermissionPolicy.requiresManual("yolo", action, frame("确定")));
    }

    @Test public void unrelatedRiskTextDoesNotElevateNavigationOrOnceOnlyPermission() throws Exception {
        String root = "android.widget.FrameLayout [0,0][1000,1000] enabled=true text=确认支付 关闭安全保护\n";
        String target = "android.widget.Button [100,100][300,300] enabled=true clickable text=仅本次允许\n";
        String unrelated = "android.widget.TextView [600,100][900,300] enabled=true text=始终允许\n";
        ScreenService.Frame frame = frame("com.android.permissioncontroller", root + target + unrelated);
        assertTrue(PhoneUsePermissionPolicy.needsReview(tap(), frame));
        assertFalse(PhoneUsePermissionPolicy.requiresManual("yolo", tap(), frame));
        assertFalse(PhoneUsePermissionPolicy.critical(tap(), frame("app", root + target + unrelated)));
    }

    @Test public void xiaomiAppSelectionIsReadableAndDoesNotNeedExtraConsent() throws Exception {
        ScreenService.Frame chooser = frame("com.miui.securitycenter",
            "android.widget.TextView [0,0][1000,100] enabled=true id=com.miui.securitycenter:id/permission_group_title text=选择应用\n"
                + "android.widget.Button [100,100][300,300] enabled=true clickable text=地图\n");
        assertFalse(PhoneUsePermissionPolicy.needsReview(tap(), chooser));
        Action described = tap();
        described.data.put("message", "选择地图应用");
        assertFalse(PhoneUsePermissionPolicy.needsReview(described, chooser));
        described.data.put("requires_confirmation", true);
        assertTrue(PhoneUsePermissionPolicy.needsReview(described, chooser));
    }

    @Test public void sensitiveSystemGrantIsReviewedAndDangerousSystemGrantIsManual() throws Exception {
        ScreenService.Frame permission = frame("com.android.permissioncontroller",
            "android.widget.Button [100,100][300,300] enabled=true clickable text=仅本次允许\n");
        assertTrue(PhoneUsePermissionPolicy.needsReview(tap(), permission));
        assertFalse(PhoneUsePermissionPolicy.requiresManual("yolo", tap(), permission));
        ScreenService.Frame admin = frame("com.android.permissioncontroller",
            "android.widget.TextView [0,0][1000,100] id=android:id/alertTitle enabled=true text=激活设备管理\n"
                + "android.widget.Button [100,100][300,300] enabled=true clickable text=允许\n");
        assertTrue(PhoneUsePermissionPolicy.requiresManual("yolo", tap(), admin));
    }

    @Test public void unknownXiaomiAuthorizationTemplateReviewsTheGrantButtonWithoutBlockingThePackage() throws Exception {
        for (String label : new String[]{"允许", "同意", "授权", "本次允许", "仅本次允许", "allow", "allow once", "agree", "grant"}) {
            ScreenService.Frame unknown = frame("com.miui.securitycenter",
                "android.widget.Button [100,100][300,300] enabled=true clickable text=" + label + "\n");
            assertFalse(SystemDialogPolicy.requiresApproval(unknown));
            assertTrue(label, PhoneUsePermissionPolicy.needsReview(tap(), unknown));
            assertTrue(label, PhoneUsePermissionPolicy.requiresManual("manual", tap(), unknown));
            assertFalse(label, PhoneUsePermissionPolicy.requiresManual("yolo", tap(), unknown));
            ScreenService.Frame selector = frame("com.miui.securitycenter",
                "android.widget.TextView [0,0][1000,100] enabled=true id=com.miui.securitycenter:id/permission_group_title text=选择应用\n"
                    + unknown.tree());
            assertFalse(label, PhoneUsePermissionPolicy.needsReview(tap(), selector));
        }
        assertFalse(PhoneUsePermissionPolicy.needsReview(tap(), frame("com.miui.securitycenter",
            "android.widget.Button [100,100][300,300] enabled=true clickable text=电池\n")));
    }

    @Test public void normalizedCoordinatesUsePhysicalGeometry() throws Exception {
        ScreenService.Frame large = new ScreenService.Frame("image",
            "android.widget.Button [200,200][600,600] enabled=true clickable text=确认支付\n",
            2000, 2000, "app", 500, 500, 0, "");
        assertTrue(PhoneUsePermissionPolicy.critical(tap(), large));
    }

    @Test public void duplicateTextAndDescriptionDoNotHideSevereTargets() throws Exception {
        assertTrue(PhoneUsePermissionPolicy.critical(tap(), frame("app",
            "android.widget.Button [100,100][300,300] enabled=true clickable text=支付 description=支付\n")));
    }

    @Test public void confirmationUsesConcreteButtonLabelAndPreservesExplicitDescription() throws Exception {
        Action action = tap();
        assertEquals("点击目标控件；目标控件：确认支付", PhoneUsePermissionPolicy.confirmationMessage(action, frame("确认支付")));
        action.data.put("message", "完整说明\n" + "对象和后果".repeat(30));
        assertEquals(action.data.getString("message"), PhoneUsePermissionPolicy.confirmationMessage(action, frame("确认支付")));
    }

    @Test public void confirmationNeverDerivesTypedOrExistingInputContents() throws Exception {
        Action type = new Action(new JSONObject().put("action", "type").put("text", "private-value"));
        String message = PhoneUsePermissionPolicy.confirmationMessage(type, frame("app",
            "android.widget.EditText [100,100][300,300] enabled=true editable focused text=existing-private-value\n"));
        assertEquals("填写目标输入框", message);
        assertFalse(message.contains("private"));
    }

    @Test public void reviewSchemaAndPromptsDescribeTheSameSevereRiskBoundary() throws Exception {
        JSONObject schema = PhoneUsePermissionPolicy.reviewSchema();
        assertFalse(schema.getBoolean("additionalProperties"));
        assertEquals(3, schema.getJSONArray("required").length());
        assertEquals(2000, schema.getJSONObject("properties").getJSONObject("reason").getInt("maxLength"));
        for (String mode : new String[]{"manual", "auto", "yolo"}) {
            String prompt = PhoneUsePermissionPolicy.prompt(mode);
            assertTrue(prompt.contains("critical"));
            assertTrue(prompt.contains("验证码/CAPTCHA"));
        }
        assertTrue(PhoneUsePermissionPolicy.reviewInstructions().contains("不确定"));
        assertTrue(PhoneUsePermissionPolicy.reviewInstructions().contains("原样token"));
    }
}
