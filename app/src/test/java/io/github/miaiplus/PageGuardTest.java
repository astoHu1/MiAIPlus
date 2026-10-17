package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class PageGuardTest {
    private static final String ROOT = "android.widget.FrameLayout [0,0][1000,1000] window=3 id=root enabled=true\n";
    private static final String BUTTON = "android.widget.Button [100,100][300,300] window=3 id=submit enabled=true clickable text=提交\n";
    private static final String FIELD = "android.widget.EditText [100,100][300,300] window=3 id=input enabled=true editable focused text=草稿\n";

    private static ScreenService.Frame frame(String image, String tree) {
        return new ScreenService.Frame(image, tree, 1000, 1000, "app", 500, 500, 0, "");
    }

    private static Action tap() throws Exception {
        return new Action(new JSONObject().put("action", "tap").put("element", new JSONArray().put(200).put(200)));
    }

    private static Action type() throws Exception {
        return new Action(new JSONObject().put("action", "type").put("text", "新内容"));
    }

    @Test public void onlyControlNamedActionsNeedThePageTheyPlannedAgainst() {
        for (String action : new String[]{"tap", "double_tap", "long_press", "type"})
            assertTrue(PageGuard.needsFreshFrame(action));
        for (String action : new String[]{"swipe", "back", "home", "launch", "wait", "finish"})
            assertFalse(PageGuard.needsFreshFrame(action));
    }

    @Test public void unrelatedDynamicTextDoesNotInvalidateAStableTarget() throws Exception {
        String clock = "android.widget.TextView [700,0][1000,80] enabled=true text=";
        assertTrue(PageGuard.canExecute(tap(), frame("old screenshot", ROOT + BUTTON + clock + "10:00\n"),
            frame("new screenshot", ROOT + BUTTON + clock + "10:01\n")));
    }

    @Test public void changedTargetTextRequiresAnotherObservation() throws Exception {
        assertFalse(PageGuard.canExecute(tap(), frame("image", ROOT + BUTTON),
            frame("image", ROOT + BUTTON.replace("提交", "删除"))));
    }

    @Test public void movedTargetCannotReuseOldCoordinates() throws Exception {
        assertFalse(PageGuard.canExecute(tap(), frame("image", ROOT + BUTTON),
            frame("image", ROOT + BUTTON.replace("[100,100][300,300]", "[250,100][450,300]"))));
    }

    @Test public void changedTargetIdentityOrAvailabilityRequiresAnotherObservation() throws Exception {
        for (String changed : new String[]{BUTTON.replace("id=submit", "id=delete"),
                BUTTON.replace("enabled=true", "enabled=false"), BUTTON.replace(" clickable", "")})
            assertFalse(PageGuard.canExecute(tap(), frame("image", ROOT + BUTTON), frame("image", ROOT + changed)));
    }

    @Test public void disabledTargetsAreNeverInjectedEvenIfUnchanged() throws Exception {
        ScreenService.Frame disabled = frame("image", ROOT + BUTTON.replace("enabled=true", "enabled=false"));
        assertFalse(PageGuard.canExecute(tap(), disabled, disabled));
    }

    @Test public void newDialogOverAnUnchangedButtonRequiresAnotherObservation() throws Exception {
        String dialog = "android.widget.FrameLayout [50,50][800,800] window=4 enabled=true\n";
        assertFalse(PageGuard.canExecute(tap(), frame("image", ROOT + BUTTON), frame("image", ROOT + BUTTON + dialog)));
    }

    @Test public void transparentContainerOverTargetAlsoRequiresAnotherObservation() throws Exception {
        String interceptor = "android.view.View [0,0][1000,1000] window=3 enabled=true\n";
        assertFalse(PageGuard.canExecute(tap(), frame("image", ROOT + BUTTON), frame("image", ROOT + BUTTON + interceptor)));
    }

    @Test public void ancestorChangeIsCheckedEvenWhenDeepestChildIsUnchanged() throws Exception {
        assertFalse(PageGuard.canExecute(tap(), frame("image", ROOT + BUTTON),
            frame("image", ROOT.replace("id=root", "id=modal") + BUTTON)));
    }

    @Test public void coveringNodeOrderMustRemainStable() throws Exception {
        String label = "android.widget.TextView [100,100][300,300] window=3 enabled=true text=确认\n";
        assertFalse(PageGuard.canExecute(tap(), frame("image", ROOT + BUTTON + label), frame("image", ROOT + label + BUTTON)));
    }

    @Test public void fullForegroundAndDisplayIdentityMustMatch() throws Exception {
        ScreenService.Frame before = frame("image", ROOT + BUTTON);
        assertFalse(PageGuard.canExecute(tap(), before,
            new ScreenService.Frame("image", before.tree(), 1000, 1000, "other.app", 500, 500, 0, "")));
        assertFalse(PageGuard.canExecute(tap(), before,
            new ScreenService.Frame("image", before.tree(), 1000, 1000, "app", 500, 500, 1, "")));
        assertFalse(PageGuard.canExecute(tap(), before,
            new ScreenService.Frame("image", before.tree(), 1000, 1000, "app", 500, 500, 0, "new generation")));
    }

    @Test public void displayAndEncodedImageGeometryMustMatch() throws Exception {
        ScreenService.Frame before = frame("image", ROOT + BUTTON);
        assertFalse(PageGuard.canExecute(tap(), before,
            new ScreenService.Frame("image", before.tree(), 1000, 1100, "app", 500, 500, 0, "")));
        assertFalse(PageGuard.canExecute(tap(), before,
            new ScreenService.Frame("image", before.tree(), 1000, 1000, "app", 501, 500, 0, "")));
    }

    @Test public void pureImageOrEmptyTreeRequiresAnIdenticalNonemptyImage() throws Exception {
        for (String tree : new String[]{"", ROOT, ROOT + "android.widget.ImageView [100,100][300,300] enabled=true\n"}) {
            assertTrue(PageGuard.canExecute(tap(), frame("same image", tree), frame("same image", tree)));
            assertFalse(PageGuard.canExecute(tap(), frame("old image", tree), frame("new image", tree)));
            assertFalse(PageGuard.canExecute(tap(), frame("", tree), frame("", tree)));
        }
    }

    @Test public void normalizedCoordinatesUseDisplayPixelsAndClampTheFarEdge() throws Exception {
        String tree = "android.widget.Button [900,900][1000,1000] enabled=true clickable text=角落\n";
        Action edge = new Action(new JSONObject().put("action", "tap").put("element", new JSONArray().put(1000).put(1000)));
        assertTrue(PageGuard.canExecute(edge, frame("old image", tree), frame("new image", tree)));
    }

    @Test public void typeOnlyTracksTheFocusedEditableTarget() throws Exception {
        String other = "android.widget.TextView [700,0][1000,80] enabled=true text=";
        assertTrue(PageGuard.canExecute(type(), frame("old image", ROOT + FIELD + other + "old\n"),
            frame("new image", ROOT + FIELD + other + "new\n")));
        assertFalse(PageGuard.canExecute(type(), frame("image", ROOT + FIELD),
            frame("image", ROOT + FIELD.replace("草稿", "另一个值"))));
        assertFalse(PageGuard.canExecute(type(), frame("image", ROOT + FIELD),
            frame("image", ROOT + FIELD.replace(" focused", ""))));
        assertFalse(PageGuard.canExecute(type(), frame("image", ROOT + FIELD),
            frame("image", ROOT + FIELD.replace("id=input", "id=another"))));
    }

    @Test public void typeRejectsMissingAmbiguousAndPasswordFocus() throws Exception {
        assertFalse(PageGuard.canExecute(type(), frame("image", ROOT), frame("image", ROOT)));
        ScreenService.Frame multiple = frame("image", ROOT + FIELD + FIELD.replace("id=input", "id=another"));
        assertFalse(PageGuard.canExecute(type(), multiple, multiple));
        ScreenService.Frame password = frame("image", ROOT + FIELD.replace("text=草稿", "[password redacted]"));
        assertFalse(PageGuard.canExecute(type(), password, password));
    }

    @Test public void typeRejectsNewCoveringDialog() throws Exception {
        String dialog = "android.widget.FrameLayout [50,50][800,800] window=4 enabled=true\n";
        assertFalse(PageGuard.canExecute(type(), frame("image", ROOT + FIELD), frame("image", ROOT + FIELD + dialog)));
    }

    @Test public void theStepBudgetIsLongEnoughForASlowToolCall() {
        assertEquals(240_000L, RunState.STEP_BUDGET_MS);
    }
}
