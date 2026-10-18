package io.github.miaiplus;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;
import org.junit.Test;
import static org.junit.Assert.*;

public class TransportBoundaryTest {
    private static final class InvocationTargetError extends Error {
        InvocationTargetError(Throwable cause) { super(cause); }
    }
    @Test public void ioFailuresRemainIdenticalThroughReflectionAndProxyWrappers() {
        IOException cancelled = new IOException("stream was reset: CANCEL");
        IOException disconnect = new java.net.SocketException("connection reset");
        for (IOException original : new IOException[]{cancelled, disconnect}) {
            for (Throwable wrapped : new Throwable[]{new InvocationTargetError(original),
                new UndeclaredThrowableException(new InvocationTargetException(new InvocationTargetError(original)))}) {
                assertSame(wrapped.getClass().getSimpleName(), original,
                    assertThrows(IOException.class, () -> TransportBoundary.execute(() -> { throw wrapped; })));
            }
        }
    }
    @Test public void conversionFailureBecomesSanitizedIoFailure() {
        IOException result = assertThrows(IOException.class, () -> TransportBoundary.execute(() -> {
            throw new IllegalArgumentException("private request contents");
        }));
        assertNull(result.getCause()); assertFalse(result.getMessage().contains("private"));
    }
    @Test public void fatalVmErrorsAreNotMasked() {
        OutOfMemoryError fatal = new OutOfMemoryError();
        assertSame(fatal, assertThrows(OutOfMemoryError.class, () -> TransportBoundary.execute(() -> {
            throw new InvocationTargetError(fatal);
        })));
    }
}
