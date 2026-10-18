package io.github.miaiplus;

import java.util.Set;
import java.util.UUID;
import java.util.function.LongSupplier;
import org.json.JSONArray;
import org.json.JSONObject;

/** One session's one-time review challenge; model approval is not user consent or an execution permit. */
final class ActionPermissionReview {
    static final long TIMEOUT_NANOS = 60_000_000_000L;
    private final LongSupplier clock;
    private Action candidate;
    private ScreenService.Frame frame;
    private String token;
    private long issuedAt;

    ActionPermissionReview() { this(System::nanoTime); }
    ActionPermissionReview(LongSupplier clock) { this.clock = clock; }

    JSONObject issue(Action action, ScreenService.Frame observation) throws Exception {
        candidate = new Action(new JSONObject(action.data.toString()));
        candidate.data.remove("permission_review");
        frame = observation;
        token = UUID.randomUUID().toString();
        issuedAt = clock.getAsLong();
        return new JSONObject().put("token", token).put("action", new JSONObject(candidate.data.toString()))
            .put("coordinate_space", "normalized")
            .put("review_instructions", PhoneUsePermissionPolicy.reviewInstructions()
                + "本挑战action坐标已归一化为0..1000；原样提交该action时必须在工具请求顶层传coordinate_space=normalized，不能按附图像素再次换算。");
    }

    boolean pending() { return candidate != null; }

    ScreenService.Frame observation() { return frame; }

    boolean matches(Action action) {
        if (candidate == null || action == null) return false;
        return equivalent(candidate.data, action.data, true);
    }

    /** Invalid, stale or unverifiable reviews request the real user's decision instead of executing. */
    String consume(Action action, ScreenService.Frame current) {
        try {
            if (!matches(action) || clock.getAsLong() - issuedAt >= TIMEOUT_NANOS) return "ask_user";
            JSONObject review = action.data.optJSONObject("permission_review");
            if (review == null || review.length() != 3 || !(review.opt("token") instanceof String)
                || !(review.opt("decision") instanceof String) || !(review.opt("reason") instanceof String)
                || !token.equals(review.getString("token"))) return "ask_user";
            String decision = review.getString("decision"), reason = review.getString("reason");
            if (!Set.of("allow", "ask_user", "deny").contains(decision) || reason.isBlank() || reason.length() > 2000)
                return "ask_user";
            if (!PageGuard.canExecute(candidate, frame, current)) return "ask_user";
            if ((SystemDialogPolicy.requiresApproval(frame) || SystemDialogPolicy.requiresApproval(current))
                && !PageGuard.same(PageGuard.anchor(frame), PageGuard.anchor(current))) return "ask_user";
            if (decision.equals("allow") && PhoneUsePermissionPolicy.critical(candidate, current)) return "ask_user";
            return decision;
        } catch (Exception invalid) {
            return "ask_user";
        } finally {
            clear();
        }
    }

    void clear() { candidate = null; frame = null; token = null; issuedAt = 0; }

    private static boolean equivalent(Object expected, Object actual, boolean actionRoot) {
        if (expected instanceof JSONObject first && actual instanceof JSONObject second) {
            int firstCount = 0, secondCount = 0;
            var keys = first.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (actionRoot && (key.equals("permission_review") || key.equals("intent"))) continue;
                firstCount++;
                if (!second.has(key) || !equivalent(first.opt(key), second.opt(key), false)) return false;
            }
            keys = second.keys();
            while (keys.hasNext()) {
                String key = keys.next();
                if (!actionRoot || (!key.equals("permission_review") && !key.equals("intent"))) secondCount++;
            }
            return firstCount == secondCount;
        }
        if (expected instanceof JSONArray first && actual instanceof JSONArray second) {
            if (first.length() != second.length()) return false;
            for (int i = 0; i < first.length(); i++)
                if (!equivalent(first.opt(i), second.opt(i), false)) return false;
            return true;
        }
        if (expected instanceof Number first && actual instanceof Number second)
            return first.toString().equals(second.toString())
                || (Double.isFinite(first.doubleValue()) && first.doubleValue() == second.doubleValue());
        return expected == actual || (expected != null && expected.equals(actual));
    }
}
