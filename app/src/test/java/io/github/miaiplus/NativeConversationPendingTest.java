package io.github.miaiplus;

import static org.junit.Assert.*;
import android.app.Application;
import android.content.ContentProvider;
import android.content.ContentValues;
import android.content.Context;
import android.content.pm.ProviderInfo;
import android.database.Cursor;
import android.net.Uri;
import android.os.Bundle;
import java.lang.reflect.Field;
import java.util.LinkedHashMap;
import java.util.Map;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.shadows.ShadowContentResolver;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33, application = Application.class)
public final class NativeConversationPendingTest {
    private final Map<Field, Object> saved = new LinkedHashMap<>();
    private Context context;
    private SettingsProvider provider;

    @Before public void setUp() throws Exception {
        context = RuntimeEnvironment.getApplication();
        provider = new SettingsProvider();
        ProviderInfo info = new ProviderInfo();
        info.authority = "io.github.miaiplus.bridge";
        provider.attachInfo(context, info);
        ShadowContentResolver.registerProviderInternal(info.authority, provider);
        replace("disposed", false);
        replace("policy", new ConversationPolicy(0, false));
        replace("pending", null);
        replace("launcherPending", false);
    }

    @After public void tearDown() throws Exception {
        for (var entry : saved.entrySet()) entry.getKey().set(null, entry.getValue());
    }

    @Test public void screenTaskStartingAfterWakeDiscardsNavigationInsteadOfReplayingAfterCleanup() throws Exception {
        pendingWake();
        provider.busy = true;
        NativeConversationHook.applyPending(context, null);
        assertDiscarded();
        provider.busy = false;
        // No host classes are present: a stale navigation would try to access them and fail here.
        NativeConversationHook.applyPending(context, null);
        assertDiscarded();
    }

    @Test public void disablingTimeoutBetweenWakeAndNavigationDiscardsWake() throws Exception {
        pendingWake();
        provider.minutes = 0;
        NativeConversationHook.applyPending(context, null);
        assertDiscarded();
    }

    @Test public void settingsBecomingUnavailableDiscardsWake() throws Exception {
        pendingWake();
        provider.available = false;
        NativeConversationHook.applyPending(context, null);
        assertDiscarded();
    }

    @Test public void launcherWakeWaitsForChatResumeInsteadOfConsumingAtPreRecognitionCallback() throws Exception {
        pendingWake();
        Object request = field("pending").get(null);
        // Without the resume gate this reaches absent native classes, consumes the wake too early,
        // and allows the Activity's later restored PAGE to replace the new conversation identity.
        NativeConversationHook.applyPending(context, null);
        assertSame(request, field("pending").get(null));
        assertTrue(field("launcherPending").getBoolean(null));
    }

    private void pendingWake() throws Exception {
        assertEquals(ConversationPolicy.Decision.NEW, NativeConversationHook.onUserWake(context, null, 10_000));
        assertNotNull(field("pending").get(null));
        field("launcherPending").setBoolean(null, true);
    }

    private void assertDiscarded() throws Exception {
        assertNull(field("pending").get(null));
        assertFalse(field("launcherPending").getBoolean(null));
    }

    private void replace(String name, Object value) throws Exception {
        Field field = field(name);
        saved.put(field, field.get(null));
        field.set(null, value);
    }

    private static Field field(String name) throws Exception {
        Field field = NativeConversationHook.class.getDeclaredField(name);
        field.setAccessible(true);
        return field;
    }

    public static final class SettingsProvider extends ContentProvider {
        boolean busy, available = true;
        int minutes = 1;
        @Override public boolean onCreate() { return true; }
        @Override public Bundle call(String method, String arg, Bundle extras) {
            if (!available) return null;
            Bundle result = new Bundle();
            result.putInt("conversationTimeoutMinutes", minutes);
            result.putBoolean("screenBusy", busy);
            return result;
        }
        @Override public Cursor query(Uri uri, String[] projection, String selection, String[] args, String order) { throw new UnsupportedOperationException(); }
        @Override public String getType(Uri uri) { throw new UnsupportedOperationException(); }
        @Override public Uri insert(Uri uri, ContentValues values) { throw new UnsupportedOperationException(); }
        @Override public int delete(Uri uri, String selection, String[] args) { throw new UnsupportedOperationException(); }
        @Override public int update(Uri uri, ContentValues values, String selection, String[] args) { throw new UnsupportedOperationException(); }
    }
}
