package io.github.miaiplus;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.util.Map;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class PhoneUseRoutingTest {
    @After public void clearFakeSession() throws Exception { activeField().set(null, null); }

    @Test public void eachToolOverridesBothLegacyDefaultsWithoutChangingThem() throws Exception {
        for (boolean defaultBackground : new boolean[]{false, true}) {
            Config config = new Config(Map.of("backgroundDisplay", defaultBackground));
            for (String tool : new String[]{PhoneUseRouting.FOREGROUND_TOOL, PhoneUseRouting.BACKGROUND_TOOL}) {
                String encoded = PhoneUseRouting.encode(tool, new JSONObject().put("goal", "打开测试应用"));
                PhoneUseRouting.Request route = PhoneUseRouting.decode(encoded, config.backgroundDisplay);
                assertEquals("打开测试应用", route.query());
                assertEquals(tool, route.sourceTool());
                assertEquals(tool.equals(PhoneUseRouting.BACKGROUND_TOOL), route.background());
                assertEquals(defaultBackground, config.backgroundDisplay);
            }
        }
    }

    @Test public void legacyStartsRetainConfiguredDefault() throws Exception {
        assertTrue(PhoneUseRouting.decode("test", true).background());
        assertFalse(PhoneUseRouting.decode("test", false).background());
        assertEquals("", PhoneUseRouting.decode("test", false).sourceTool());
    }

    @Test public void continuationCarriesOriginalToolAndImageCoordinates() throws Exception {
        JSONObject request = new JSONObject().put("session", "session")
            .put("origin_tool", PhoneUseRouting.FOREGROUND_TOOL)
            .put("action", new JSONObject().put("action", "wait"));
        String encoded = PhoneUseRouting.encode(PhoneUseRouting.BACKGROUND_TOOL, request);
        PhoneUseRouting.Request route = PhoneUseRouting.decode(encoded, false);
        assertEquals(PhoneUseRouting.BACKGROUND_TOOL, route.sourceTool());
        assertEquals("image_pixels", new JSONObject(route.query().substring(9)).getString("coordinate_space"));
    }

    @Test public void malformedOrUnknownStartIsRejectedBeforeExecution() throws Exception {
        for (String encoded : new String[]{"MIAIPLUS_START:{", "MIAIPLUS_START:{}",
            "MIAIPLUS_START:{\"goal\":\"test\",\"origin_tool\":\"other\"}"}) {
            try { PhoneUseRouting.decode(encoded, false); fail("Malformed start was accepted"); }
            catch (Exception expected) { }
        }
    }

    @Test public void actionCannotOverrideExecutionScreen() throws Exception {
        for (String property : new String[]{"execution_mode", "background"}) {
            JSONObject request = new JSONObject().put("session", "session").put(property, "foreground")
                .put("action", new JSONObject().put("action", "wait"));
            try { PhoneUseRouting.encode(PhoneUseRouting.BACKGROUND_TOOL, request); fail("Override accepted"); }
            catch (IllegalArgumentException expected) { }
            try { PhoneUseRouting.decode("MIAIPLUS:" + request, false); fail("Legacy override accepted"); }
            catch (IllegalArgumentException expected) { }
        }
    }

    @Test public void wrongToolCannotCancelOrRenewSessionEvenAfterBackgroundHandoff() throws Exception {
        Class<?> stateType = Class.forName(NativeSession.class.getName() + "$State");
        Constructor<?> constructor = stateType.getDeclaredConstructor(String.class, int.class, int.class,
            ScreenService.class, boolean.class, String.class);
        constructor.setAccessible(true);
        // This session originated in background, but its approved handoff now uses the physical screen.
        Object state = constructor.newInstance("test", 1000, 10, null, false, PhoneUseRouting.BACKGROUND_TOOL);
        activeField().set(null, state);
        Field runField = stateType.getDeclaredField("run"); runField.setAccessible(true);
        RunState sessionRun = (RunState) runField.get(state);
        long deadline = sessionRun.deadline;
        String query = "MIAIPLUS:" + new JSONObject().put("session", sessionRun.id)
            .put("action", new JSONObject().put("action", "wait"));
        RunState caller = new RunState("outer", 1000);
        try {
            NativeSession.handle(new Config(Map.of()), query, caller, false, PhoneUseRouting.FOREGROUND_TOOL);
            fail("Wrong tool accepted");
        } catch (IllegalStateException expected) {
            assertTrue(expected.getMessage().contains("请继续使用启动此会话的工具 " + PhoneUseRouting.BACKGROUND_TOOL));
        }
        caller.cancel();
        assertFalse(sessionRun.cancelled.get());
        assertEquals(deadline, sessionRun.deadline);
        assertSame(state, activeField().get(null));
        PhoneUseRouting.verifyContinuation(PhoneUseRouting.BACKGROUND_TOOL, PhoneUseRouting.BACKGROUND_TOOL);
    }

    private static Field activeField() throws Exception {
        Field field = NativeSession.class.getDeclaredField("active"); field.setAccessible(true); return field;
    }
}
