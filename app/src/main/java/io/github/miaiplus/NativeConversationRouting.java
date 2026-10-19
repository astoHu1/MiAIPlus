package io.github.miaiplus;

import io.github.miaiplus.hook.Reflector;
import java.util.Objects;

/** Keeps the host's separate popup controller on the selected native conversation. */
final class NativeConversationRouting {
    private static Object ownedMainPage;
    private static Object ownedTracker;

    private NativeConversationRouting() { }

    static Object page(ClassLoader loader, Object info) {
        return Reflector.newInstance(Reflector.findClass(
            "com.xiaomi.voiceassistant.data.model.PageNavigationData", loader), info, "quick");
    }

    static Object popupController(Object manager) {
        Object controller = Reflector.callMethod(manager, "getResultCardManagerController");
        // Both pinned APKs keep FloatUiResultCardController in c. The public
        // getTopicDialogCardManager accessor instead belongs to the full-screen controller.
        return controller == null ? null : Reflector.getObjectField(controller, "c");
    }

    static Object popupInfo(Object manager) {
        Object controller = popupController(manager);
        Object cards = controller == null ? null : Reflector.getObjectField(controller,
            HostCompatibility.choose("l", "m"));
        return cards == null ? null : Reflector.callMethod(cards, "getTopicGroupInfo");
    }

    static Object popupPage(Object manager) {
        Object controller = popupController(manager);
        Object view = controller == null ? null : Reflector.callMethod(controller, "getFloatResultCardView");
        return view == null ? null : Reflector.getObjectField(view, HostCompatibility.choose("Q", "V"));
    }

    /** Seed lazy popup initialization and update a popup that has already initialized. */
    static void syncPopup(Object manager, ClassLoader loader, Object info) {
        if (info == null) return;
        Object navigation = page(loader, info);
        Object foreground = Reflector.getStaticObjectField(Reflector.findClass(
            "com.xiaomi.voiceassistant.a5", loader), "a");
        // FloatUiResultCardController.w consumes this when its result view is first needed.
        seedMainPage(foreground, navigation);
        Object controller = popupController(manager);
        if (controller != null) syncPopupController(controller, manager, loader, info, navigation);
    }

    /** Also called after the native lazy view initialization, which can restore old popup data. */
    static void syncPopupController(Object controller, Object manager, ClassLoader loader, Object info) {
        if (info != null) syncPopupController(controller, manager, loader, info, page(loader, info));
    }

    private static void syncPopupController(Object controller, Object manager, ClassLoader loader,
                                            Object info, Object navigation) {
        Object view = Reflector.callMethod(controller, "getFloatResultCardView");
        if (view == null) return;
        String field = HostCompatibility.choose("l", "m");
        Object cards = Reflector.getObjectField(controller, field);
        Object previous = cards == null ? null : Reflector.callMethod(cards, "getTopicGroupInfo");
        if (!refreshPopupTopic(cards, previous, info)) {
            if (cards != null) Reflector.callMethod(cards, "clean");
            cards = Reflector.newInstance(Reflector.findClass(
                HostCompatibility.choose("el1.s2", "jm1.v2"), loader), manager);
            Reflector.setObjectField(controller, field, cards);
            Reflector.callMethod(view, "clearAllCards");
            // This native path attaches only popup identity/listeners. It neither starts another
            // engine session nor reopens a full-screen topic with unfinished agent/chat metadata.
            Reflector.callMethod(cards, "startTopicDialogForFloatView", info);
        }
        Object topicPage = Reflector.getStaticObjectField(Reflector.findClass(
            HostCompatibility.choose("hk1.j$a", "ll1.j$a"), loader), "RESULT_TOPIC_PAGE");
        Reflector.callMethod(view, "updatePageType", topicPage, false);
        Reflector.callMethod(view, "setPageNavigationData", navigation);
    }

    /** Hydration of the current topic must preserve its live request and title/session jobs. */
    static boolean refreshPopupTopic(Object cards, Object previous, Object info) {
        if (cards == null || previous == null || info == null || !Objects.equals(
            Reflector.callMethod(previous, "getTopicId"), Reflector.callMethod(info, "getTopicId"))) return false;
        if (previous != info) Reflector.callMethod(cards, HostCompatibility.choose("h3", "p3"),
            Reflector.callMethod(info, "getTopicId"), Reflector.callMethod(info, "getAgentId"),
            Reflector.callMethod(info, "getTopicName"), Reflector.callMethod(info, "getChatId"),
            Reflector.callMethod(info, "getAvatar"), Reflector.callMethod(info, "getType"));
        return true;
    }

    static void seedMainPage(Object tracker, Object navigation) {
        Reflector.callMethod(tracker, "setMainPageNavigationData", navigation);
        ownedTracker = tracker;
        ownedMainPage = navigation;
    }

    /** A history route or disabled/disposed hook must not leave a seed for a later popup. */
    static void clearOwnedSeed() {
        Object tracker = ownedTracker;
        Object navigation = ownedMainPage;
        ownedTracker = null;
        ownedMainPage = null;
        // Field e is the one-shot main page in both exact APKs; reading it does not consume
        // screen-recognition, standby, or a newer native/explicit main-page selection.
        if (tracker != null && navigation != null && Reflector.getStaticObjectField(
            tracker.getClass(), "e") == navigation)
            Reflector.callMethod(tracker, "setMainPageNavigationData", (Object)null);
    }
}
