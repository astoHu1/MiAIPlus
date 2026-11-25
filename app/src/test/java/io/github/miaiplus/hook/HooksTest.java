package io.github.miaiplus.hook;

import static org.junit.Assert.*;
import io.github.libxposed.api.XposedInterface;
import java.lang.reflect.Proxy;
import java.util.List;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;

public class HooksTest {
    private XposedInterface.Hooker interceptor;
    private Object[] passed;
    private int originals;
    private boolean unhooked;
    interface Invocation { Object call(Object proxy, java.lang.reflect.Method method, Object[] args) throws Throwable; }
    private <T> T proxy(Class<T> type, Invocation invocation) {
        return type.cast(Proxy.newProxyInstance(type.getClassLoader(), new Class<?>[]{type}, invocation::call));
    }
    static String target(String value) { return value; }
    @Before public void setup() {
        var handle = proxy(XposedInterface.HookHandle.class, (p, m, a) -> { if (m.getName().equals("unhook")) unhooked = true; return null; });
        var builder = proxy(XposedInterface.HookBuilder.class, (p, m, a) -> {
            if (m.getName().equals("intercept")) { interceptor = (XposedInterface.Hooker)a[0]; return handle; }
            return p;
        });
        Hooks.initialize(proxy(XposedInterface.class, (p, m, a) -> builder));
    }
    @After public void cleanup() { Hooks.unhookAll(); Hooks.thaw(); }
    private Object call(boolean failure) throws Throwable {
        return interceptor.intercept(proxy(XposedInterface.Chain.class, (p, m, a) -> switch (m.getName()) {
            case "getThisObject" -> null;
            case "getExecutable" -> HooksTest.class.getDeclaredMethod("target", String.class);
            case "getArgs" -> List.of("initial");
            case "proceed" -> {
                originals++;
                assertFalse("active hook must reject reload", Hooks.freeze());
                passed = a == null ? new Object[]{"initial"} : (Object[])a[0];
                if (failure) throw new IllegalArgumentException("original failed");
                yield passed[0];
            }
            default -> throw new AssertionError(m.getName());
        }));
    }
    @Test public void copiesArgumentsAndPreservesBeforeAfterState() throws Throwable {
        Hooks.hookMethod(HooksTest.class.getDeclaredMethod("target", String.class), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.args[0] = "changed"; p.setObjectExtra("marker", "kept"); }
            @Override protected void afterHookedMethod(MethodHookParam p) { assertEquals("kept", p.getObjectExtra("marker")); p.setResult(p.getResult() + " after"); }
        });
        assertEquals("changed after", call(false));
        assertEquals("changed", passed[0]); assertEquals(1, originals); assertTrue(Hooks.freeze());
    }
    @Test public void earlyResultSkipsOriginal() throws Throwable {
        Hooks.hookMethod(HooksTest.class.getDeclaredMethod("target", String.class), new HookCallback() {
            @Override protected void beforeHookedMethod(MethodHookParam p) { p.setResult("blocked"); }
        });
        assertEquals("blocked", call(false)); assertEquals(0, originals); assertTrue(Hooks.freeze());
    }
    @Test public void propagatesOriginalFailureAndReleasesBusyGuard() throws Throwable {
        Hooks.hookMethod(HooksTest.class.getDeclaredMethod("target", String.class), new HookCallback() {});
        try { call(true); fail("Expected failure"); } catch (IllegalArgumentException expected) { assertEquals("original failed", expected.getMessage()); }
        assertTrue(Hooks.freeze()); Hooks.unhookAll(); assertTrue(unhooked);
    }
    @Test public void retiringGenerationRejectsNewExternalWork() {
        Hooks.enter(); assertFalse(Hooks.freeze()); Hooks.leave();
        assertTrue(Hooks.freeze()); assertThrows(IllegalStateException.class, Hooks::enter);
        Hooks.thaw(); Hooks.enter(); Hooks.leave();
    }

    static class Parent { private String hidden = "old"; private String inherited() { return hidden; } }
    static class Host extends Parent {
        private final String selected;
        Host(Object value) { selected = "object"; }
        Host(CharSequence value) { selected = "text"; }
        private String pick(Object value) { return "object"; }
        private String pick(CharSequence value) { return "text"; }
        private String integer(int value) { return String.valueOf(value); }
        private String ambiguous(String value) { return "string"; }
        private String ambiguous(Integer value) { return "integer"; }
        private void fail() { throw new IllegalStateException("host error"); }
    }
    @Test public void reflectorSelectsSpecificOverloadsAndPrivateSuperclassMembers() {
        Host host = (Host)Reflector.newInstance(Host.class, new StringBuilder());
        assertEquals("text", host.selected);
        assertEquals("text", Reflector.callMethod(host, "pick", new StringBuilder()));
        assertEquals("text", Reflector.callMethod(host, "pick", (Object)null));
        assertEquals("7", Reflector.callMethod(host, "integer", 7));
        Reflector.setObjectField(host, "hidden", "new");
        assertEquals("new", Reflector.getObjectField(host, "hidden"));
        assertEquals("new", Reflector.callMethod(host, "inherited"));
    }
    @Test public void reflectorRejectsAmbiguousOverloadsAndPreservesHostFailure() {
        assertThrows(IllegalArgumentException.class, () -> Reflector.callMethod(new Host(""), "ambiguous", (Object)null));
        assertEquals("host error", assertThrows(IllegalStateException.class, () -> Reflector.callMethod(new Host(""), "fail")).getMessage());
    }
}
