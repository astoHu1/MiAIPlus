package io.github.miaiplus;

import static org.junit.Assert.*;
import java.util.concurrent.atomic.AtomicLong;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;

public final class ActionPermissionReviewTest {
    private static final String BUTTON = "android.widget.Button [100,100][300,300] enabled=true clickable text=发送\n";

    private static Action action() throws Exception {
        return new Action(new JSONObject().put("action", "tap").put("element", new JSONArray("[200,200]"))
            .put("requires_confirmation", true).put("message", "发送当前草稿给指定联系人").put("risk_level", "sensitive"));
    }

    private static ScreenService.Frame frame(String tree) {
        return new ScreenService.Frame("image", tree, 1000, 1000, "app", 1000, 1000, 0, "");
    }

    private static Action answer(Action action, JSONObject challenge, String decision) throws Exception {
        Action copy = new Action(new JSONObject(action.data.toString()));
        copy.data.put("permission_review", new JSONObject().put("token", challenge.getString("token"))
            .put("decision", decision).put("reason", "用户明确要求给该联系人发送当前草稿"));
        return copy;
    }

    @Test public void reviewIsBoundToTheConcreteActionAndCanOnlyBeConsumedOnce() throws Exception {
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = action();
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        assertTrue(review.pending());
        assertEquals(BUTTON, review.observation().tree());
        assertTrue(review.matches(candidate));
        assertEquals("normalized", challenge.getString("coordinate_space"));
        assertTrue(challenge.getString("review_instructions").contains("原样token"));
        assertEquals("allow", review.consume(answer(candidate, challenge, "allow"), frame(BUTTON)));
        assertFalse(review.pending());
        assertNull(review.observation());
        assertEquals("ask_user", review.consume(answer(candidate, challenge, "allow"), frame(BUTTON)));
    }

    @Test public void reviewKeepsAllowAskAndDenySemantics() throws Exception {
        for (String decision : new String[]{"allow", "ask_user", "deny"}) {
            ActionPermissionReview review = new ActionPermissionReview();
            Action candidate = action();
            assertEquals(decision, review.consume(answer(candidate, review.issue(candidate, frame(BUTTON)), decision), frame(BUTTON)));
        }
    }

    @Test public void changesToCoordinatesMessageOrRiskCannotReuseApproval() throws Exception {
        for (String field : new String[]{"element", "message", "risk_level", "extra"}) {
            ActionPermissionReview review = new ActionPermissionReview();
            Action candidate = action();
            Action answer = answer(candidate, review.issue(candidate, frame(BUTTON)), "allow");
            answer.data.put(field, field.equals("element") ? new JSONArray("[250,200]") : "changed");
            assertFalse(field, review.matches(answer));
            assertEquals(field, "ask_user", review.consume(answer, frame(BUTTON)));
            assertFalse(review.pending());
        }
    }

    @Test public void intentAndObjectOrderingDoNotInvalidateTheSameAction() throws Exception {
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = action();
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        Action reordered = new Action(new JSONObject().put("risk_level", "sensitive").put("message", candidate.data.getString("message"))
            .put("requires_confirmation", true).put("element", new JSONArray("[200.0,200]"))
            .put("action", "tap").put("intent", "发送草稿"));
        assertTrue(review.matches(reordered));
        assertEquals("allow", review.consume(answer(reordered, challenge, "allow"), frame(BUTTON)));
    }

    @Test public void mutationAfterIssuingCannotAlterTheStoredCandidate() throws Exception {
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = action();
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        candidate.data.put("message", "发送另一段内容");
        assertFalse(review.matches(candidate));
        challenge.getJSONObject("action").put("message", "发送另一段内容");
        assertFalse(review.matches(candidate));
    }

    @Test public void missingMalformedForgedOrOverlongReviewFallsBackToUser() throws Exception {
        for (String scenario : new String[]{"missing", "wrong_token", "no_reason", "blank_reason", "long_reason", "bad_decision", "extra", "not_string"}) {
            ActionPermissionReview review = new ActionPermissionReview();
            Action candidate = action();
            Action answer = answer(candidate, review.issue(candidate, frame(BUTTON)), "allow");
            JSONObject data = answer.data.getJSONObject("permission_review");
            switch (scenario) {
                case "missing" -> answer.data.remove("permission_review");
                case "wrong_token" -> data.put("token", "forged");
                case "no_reason" -> data.remove("reason");
                case "blank_reason" -> data.put("reason", " \n ");
                case "long_reason" -> data.put("reason", "x".repeat(2001));
                case "bad_decision" -> data.put("decision", "approved");
                case "extra" -> data.put("other", true);
                case "not_string" -> data.put("reason", 42);
            }
            assertEquals(scenario, "ask_user", review.consume(answer, frame(BUTTON)));
            assertFalse(scenario, review.pending());
        }
    }

    @Test public void expirationAndReplacementInvalidateTheOldChallenge() throws Exception {
        AtomicLong now = new AtomicLong(1_000);
        ActionPermissionReview review = new ActionPermissionReview(now::get);
        Action candidate = action();
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        now.addAndGet(ActionPermissionReview.TIMEOUT_NANOS);
        assertEquals("ask_user", review.consume(answer(candidate, challenge, "allow"), frame(BUTTON)));
        JSONObject old = review.issue(candidate, frame(BUTTON));
        JSONObject fresh = review.issue(candidate, frame(BUTTON));
        assertNotEquals(old.getString("token"), fresh.getString("token"));
        assertEquals("ask_user", review.consume(answer(candidate, old, "allow"), frame(BUTTON)));
    }

    @Test public void aChangedTargetOrDisplayCannotUseTheReview() throws Exception {
        Action candidate = action();
        for (ScreenService.Frame current : new ScreenService.Frame[]{frame(BUTTON.replace("发送", "删除")),
            new ScreenService.Frame("image", BUTTON, 1000, 1000, "app", 1000, 1000, 1, "background")}) {
            ActionPermissionReview review = new ActionPermissionReview();
            JSONObject challenge = review.issue(candidate, frame(BUTTON));
            assertEquals("ask_user", review.consume(answer(candidate, challenge, "allow"), current));
        }
    }

    @Test public void unrelatedPageRefreshKeepsTheSameReviewedTarget() throws Exception {
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = action();
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        assertEquals("allow", review.consume(answer(candidate, challenge, "allow"), frame(BUTTON
            + "android.widget.TextView [700,0][1000,80] enabled=true text=新广告\n")));
    }

    @Test public void modelReviewCannotAllowASevereRisk() throws Exception {
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = action();
        candidate.data.put("risk_level", "critical");
        JSONObject challenge = review.issue(candidate, frame(BUTTON));
        assertEquals("ask_user", review.consume(answer(candidate, challenge, "allow"), frame(BUTTON)));
    }

    @Test public void changingOtherAuthorizationTextInvalidatesAStableAllowButton() throws Exception {
        ActionPermissionReview review = new ActionPermissionReview();
        Action candidate = action();
        ScreenService.Frame old = new ScreenService.Frame("image", BUTTON
            + "android.widget.TextView [600,100][900,300] enabled=true text=获取相机权限\n",
            1000, 1000, "com.android.permissioncontroller", 1000, 1000, 0, "");
        ScreenService.Frame current = new ScreenService.Frame("image", old.tree().replace("获取相机权限", "获取麦克风权限"),
            1000, 1000, "com.android.permissioncontroller", 1000, 1000, 0, "");
        JSONObject challenge = review.issue(candidate, old);
        assertEquals("ask_user", review.consume(answer(candidate, challenge, "allow"), current));
    }
}
