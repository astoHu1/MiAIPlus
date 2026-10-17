package io.github.miaiplus;

import static org.junit.Assert.*;
import org.json.JSONObject;
import org.junit.Test;

public final class ForegroundConsentTest {
    @Test public void grantRejectsWrongOwnerTaskAndTokenWithoutConsumingRequest() {
        ForegroundConsent.Request request = request();
        assertFalse(request.respond("task-a", 200, request.token, true, 101));
        assertFalse(request.respond("task-b", 100, request.token, true, 101));
        assertFalse(request.respond("task-a", 100, "old-token", true, 101));
        assertNull(request.answer());
        assertTrue(request.respond("task-a", 100, request.token, true, 101));
        assertEquals(Boolean.TRUE, request.answer());
    }

    @Test public void denialCannotBeReplacedByReplayedApproval() {
        ForegroundConsent.Request request = request();
        assertTrue(request.respond("task-a", 100, request.token, false, 101));
        assertFalse(request.respond("task-a", 100, request.token, true, 102));
        assertEquals(Boolean.FALSE, request.answer());
    }

    @Test public void expiredGrantCannotAuthorizeForegroundControl() {
        ForegroundConsent.Request request = request();
        assertFalse(request.respond("task-a", 100, request.token, true, request.expires));
        assertNull(request.answer());
        assertNotEquals(request.token, request().token);
    }

    @Test public void declinedHandoffEndsNativeTurnButOrdinaryObservationsDoNot() {
        ForegroundConsent.Paused pause = new ForegroundConsent.Paused("PAUSE：你已拒绝，任务已暂停");
        assertTrue(pause.getMessage().startsWith("PAUSE："));
        assertTrue(ForegroundConsent.endsNativeTurn(pause.getMessage()));
        // The compatibility CLI serializes the AppResult inside its own JSON result envelope.
        assertTrue(ForegroundConsent.endsNativeTurn("{\"__envelope__\":1,\"ok\":true,\"data\":\"" + pause.getMessage() + "\"}"));
        assertTrue(ForegroundConsent.endsNativeTurn("{\"type\":\"text\",\"text\":\"" + pause.getMessage() + "\"}"));
        assertFalse(ForegroundConsent.endsNativeTurn("CONTINUE：控屏尚未完成。"));
        assertFalse(ForegroundConsent.endsNativeTurn("CONTINUE：控屏尚未完成。目标=" + pause.getMessage()));
        assertFalse(ForegroundConsent.endsNativeTurn("CONTINUE：控屏尚未完成。无障碍树text=" + pause.getMessage()));
        assertFalse(ForegroundConsent.endsNativeTurn("{\"data\":\"" + pause.getMessage() + "\"}"));
        assertFalse(ForegroundConsent.endsNativeTurn("PAUSE：需要你接管手机"));
    }

    @Test public void nativeStopDecisionIsLimitedToOurPhoneToolAndExactCompatibilityCommand() throws Exception {
        String text = new ForegroundConsent.Paused("已拒绝").getMessage();
        assertTrue(ForegroundConsent.endsNativeToolTurn(NativePhoneUseTool.NAME, "{}", text));
        assertTrue(ForegroundConsent.endsNativeToolTurn("miaiplus_background_phone_use", "{}", text));
        assertTrue(ForegroundConsent.endsNativeToolTurn("voiceassist__controlApp", "{}", text));
        String cli = new JSONObject().put("type", "text").put("text", text).toString();
        assertTrue(ForegroundConsent.endsNativeToolTurn("cli", new JSONObject()
            .put("command", "  voiceassist__controlApp \"MIAIPLUS:...\"").toString(), cli));
        assertFalse(ForegroundConsent.endsNativeToolTurn("search", "{}", text));
        assertFalse(ForegroundConsent.endsNativeToolTurn("cli", new JSONObject()
            .put("command", "echo voiceassist__controlApp").toString(), cli));
        assertFalse(ForegroundConsent.endsNativeToolTurn("cli", new JSONObject()
            .put("command", "voiceassist__controlApp_other").toString(), cli));
    }

    @Test public void initialLaunchRemainsInitialAfterWaitingButNotAfterAnAppAction() throws Exception {
        BackgroundActions.LaunchPolicy policy = new BackgroundActions.LaunchPolicy();
        policy.used(new Action(new JSONObject().put("action", "wait")));
        policy.used(new Action(new JSONObject().put("action", "wait")));
        assertTrue(policy.initial());
        policy.used(new Action(new JSONObject().put("action", "launch").put("app", "example.app")));
        assertFalse(policy.initial());
    }

    private static ForegroundConsent.Request request() {
        return new ForegroundConsent.Request("task-a", 100, "example.app", "Example", 100);
    }
}
