package io.github.miaiplus;

import org.junit.After;
import org.junit.Test;
import static org.junit.Assert.*;

public final class NativeConversationPopupTest {
    @After public void restoreLegacyProfile() {
        NativeConversationRouting.clearOwnedSeed();
        HostCompatibility.select("8.2.65.4714", 508002065L);
    }

    @Test public void popupIdentityComesFromActualPopupInsteadOfFullscreenController() {
        verifyPopupIdentity(false);
        verifyPopupIdentity(true);
    }

    @Test public void missingLazyPopupIsNotReportedAsTheHiddenFullscreenConversation() {
        Manager manager = new Manager(null);
        assertNull(NativeConversationRouting.popupInfo(manager));
        assertNull(NativeConversationRouting.popupPage(manager));
        manager.controller.c = new Popup(null, null);
        assertNull(NativeConversationRouting.popupInfo(manager));
        assertNull(NativeConversationRouting.popupPage(manager));
    }

    @Test public void hydratingCurrentPopupPreservesItsLiveRequestForBothHostVersions() {
        for (boolean modern : new boolean[]{false, true}) {
            assertTrue(HostCompatibility.select(modern ? "8.3.30.4420" : "8.2.65.4714",
                modern ? 508003030L : 508002065L));
            Topic placeholder = new Topic("topic", null, null);
            Topic hydrated = new Topic("topic", "agent", "chat");
            LiveCards cards = new LiveCards();
            assertTrue(NativeConversationRouting.refreshPopupTopic(cards, placeholder, hydrated));
            assertEquals("agent", cards.agent);
            assertEquals("chat", cards.chat);
            assertEquals(1, cards.metadataUpdates);
            assertTrue(cards.liveRequest);
            assertFalse(NativeConversationRouting.refreshPopupTopic(cards, hydrated,
                new Topic("different-topic", "other-agent", "other-chat")));
            assertEquals(1, cards.metadataUpdates);
        }
    }

    @Test public void cleanupOnlyClearsAnUnconsumedOwnedMainPageSeed() {
        Tracker tracker = new Tracker();
        Object owned = new Object();
        NativeConversationRouting.seedMainPage(tracker, owned);
        NativeConversationRouting.clearOwnedSeed();
        assertNull(Tracker.e);

        NativeConversationRouting.seedMainPage(tracker, owned);
        Object explicitHistory = new Object();
        tracker.setMainPageNavigationData(explicitHistory);
        NativeConversationRouting.clearOwnedSeed();
        assertSame(explicitHistory, Tracker.e);
        Tracker.e = null;
    }

    private static void verifyPopupIdentity(boolean modern) {
        assertTrue(HostCompatibility.select(modern ? "8.3.30.4420" : "8.2.65.4714",
            modern ? 508003030L : 508002065L));
        Object popupInfo = new Object();
        Object popupPage = new Object();
        Popup popup = new Popup(new Cards(popupInfo), new PopupView(popupPage));
        Manager manager = new Manager(popup);
        assertSame(popup, NativeConversationRouting.popupController(manager));
        assertSame(popupInfo, NativeConversationRouting.popupInfo(manager));
        assertSame(popupPage, NativeConversationRouting.popupPage(manager));
    }

    static final class Manager {
        final Controller controller;
        Manager(Popup popup) { controller = new Controller(popup); }
        public Controller getResultCardManagerController() { return controller; }
        public Object getTopicDialogCardManager() {
            throw new AssertionError("The fullscreen topic manager cannot identify the popup");
        }
    }

    static final class Controller {
        Popup c;
        Controller(Popup popup) { c = popup; }
    }

    static final class Popup {
        final Cards l, m;
        final PopupView view;
        Popup(Cards cards, PopupView view) { l = cards; m = cards; this.view = view; }
        public PopupView getFloatResultCardView() { return view; }
    }

    static final class Cards {
        final Object info;
        Cards(Object info) { this.info = info; }
        public Object getTopicGroupInfo() { return info; }
    }

    static final class PopupView {
        final Object Q, V;
        PopupView(Object page) { Q = page; V = page; }
    }

    static final class Topic {
        final String topic, agent, chat;
        Topic(String topic, String agent, String chat) {
            this.topic = topic; this.agent = agent; this.chat = chat;
        }
        public String getTopicId() { return topic; }
        public String getAgentId() { return agent; }
        public String getChatId() { return chat; }
        public String getTopicName() { return "name"; }
        public String getAvatar() { return "avatar"; }
        public Integer getType() { return 1; }
    }

    static final class LiveCards {
        boolean liveRequest = true;
        String agent, chat;
        int metadataUpdates;
        public void h3(String topic, String agent, String name, String chat, String avatar, Integer type) {
            this.agent = agent; this.chat = chat; metadataUpdates++;
        }
        public void p3(String topic, String agent, String name, String chat, String avatar, Integer type) {
            h3(topic, agent, name, chat, avatar, type);
        }
        public void startTopicDialogForFloatView(Object info) {
            liveRequest = false;
            throw new AssertionError("Hydration must preserve the current popup request");
        }
    }

    static final class Tracker {
        static Object e;
        public void setMainPageNavigationData(Object page) { e = page; }
    }
}
