package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import androidx.activity.ComponentActivity;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.Robolectric;
import org.robolectric.RobolectricTestRunner;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class NativeConversationHostLifecycleTest {
    @Test public void homePauseCannotNavigateOldChatEvenWhileNativeScreenFlagIsStale() {
        var controller = Robolectric.buildActivity(ComponentActivity.class).setup();
        ComponentActivity activity = controller.get();
        assertTrue(NativeConversationHook.isResumedConversationHost(activity, activity.getLifecycle()));
        controller.pause();
        assertFalse(NativeConversationHook.isResumedConversationHost(activity, activity.getLifecycle()));
        controller.resume();
        assertTrue(NativeConversationHook.isResumedConversationHost(activity, activity.getLifecycle()));
        controller.pause().stop().destroy();
        assertFalse(NativeConversationHook.isResumedConversationHost(activity, activity.getLifecycle()));
    }

    @Test public void finishingChatCannotReceiveAnAutomaticWakeRoute() {
        var controller = Robolectric.buildActivity(ComponentActivity.class).setup();
        ComponentActivity activity = controller.get();
        activity.finish();
        assertFalse(NativeConversationHook.isResumedConversationHost(activity, activity.getLifecycle()));
        controller.pause().stop().destroy();
    }

    @Test public void unavailableConversationHostOrLifecycleDoesNotInferFullscreen() {
        var controller = Robolectric.buildActivity(ComponentActivity.class).setup();
        ComponentActivity activity = controller.get();
        assertFalse(NativeConversationHook.isResumedConversationHost(null, activity.getLifecycle()));
        assertFalse(NativeConversationHook.isResumedConversationHost(activity, null));
        assertFalse(NativeConversationHook.isResumedConversationHost(new Object(), activity.getLifecycle()));
        controller.pause().stop().destroy();
    }
}
