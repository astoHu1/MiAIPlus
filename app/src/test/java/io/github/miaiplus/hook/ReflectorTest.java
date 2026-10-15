package io.github.miaiplus.hook;

import static org.junit.Assert.*;
import org.junit.Test;

public class ReflectorTest {
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
    @Test public void selectsSpecificOverloadIncludingNull() {
        Host host = (Host)Reflector.newInstance(Host.class, new StringBuilder());
        assertEquals("text", host.selected);
        assertEquals("text", Reflector.callMethod(host, "pick", new StringBuilder()));
        assertEquals("text", Reflector.callMethod(host, "pick", (Object)null));
        assertEquals("7", Reflector.callMethod(host, "integer", 7));
    }
    @Test public void supportsPrivateSuperclassMembers() {
        Host host = new Host("value");
        Reflector.setObjectField(host, "hidden", "new");
        assertEquals("new", Reflector.getObjectField(host, "hidden"));
        assertEquals("new", Reflector.callMethod(host, "inherited"));
    }
    @Test public void rejectsUnrelatedAmbiguousOverloads() {
        assertThrows(IllegalArgumentException.class, () -> Reflector.callMethod(new Host(""), "ambiguous", (Object)null));
    }
    @Test public void preservesHostFailure() {
        assertEquals("host error", assertThrows(IllegalStateException.class, () -> Reflector.callMethod(new Host(""), "fail")).getMessage());
    }
}
