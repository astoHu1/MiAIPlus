package io.github.miaiplus;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public class HostCompatibilityTest {
    @After public void restoreLegacyProfile() {
        HostCompatibility.select("8.2.65.4714", 508002065L);
    }

    @Test public void requiresBothExactVersionNameAndCode() {
        assertTrue(HostCompatibility.select("8.3.30.4420", 508003030L));
        assertFalse(HostCompatibility.select("8.3.30.4421", 508003030L));
        assertFalse(HostCompatibility.modern());
        assertFalse(HostCompatibility.select("8.3.30.4420", 508003031L));
        assertFalse(HostCompatibility.select("8.2.65.4714", 508003030L));
        assertFalse(HostCompatibility.select(null, 508003030L));
    }

    @Test public void profileSwitchRestoresLegacyBindingsAndLeavesStableTypesIntact() {
        assertTrue(HostCompatibility.select("8.3.30.4420", 508003030L));
        assertEquals("dn.i", HostCompatibility.name("qm.i"));
        assertEquals("B", HostCompatibility.choose("A", "B"));
        assertEquals("com.xiaomi.voiceassistant.mainui.navigator.MainNavigatorActivity", HostCompatibility.chatActivity());
        assertEquals("com.aios.osbot.agent.model.Message",
            HostCompatibility.name("com.aios.osbot.agent.model.Message"));
        assertTrue(HostCompatibility.select("8.2.65.4714", 508002065L));
        assertEquals("qm.i", HostCompatibility.name("qm.i"));
        assertEquals("A", HostCompatibility.choose("A", "B"));
        assertEquals("com.xiaomi.voiceassistant.TabHostMainActivity", HostCompatibility.chatActivity());
    }
}
