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
    @Test public void cancelledReflectedRequestRemainsTheSameIoException() {
        IOException cancelled = new IOException("stream was reset: CANCEL");
        IOException result = assertThrows(IOException.class, () -> TransportBoundary.execute(() -> {
            throw new InvocationTargetError(cancelled);
        }));
        assertSame(cancelled, result);
    }
    @Test public void nestedProxyAndReflectionWrappersAreUnwrapped() {
        IOException disconnect = new java.net.SocketException("connection reset");
        assertSame(disconnect, assertThrows(IOException.class, () -> TransportBoundary.execute(() -> {
            throw new UndeclaredThrowableException(new InvocationTargetException(new InvocationTargetError(disconnect)));
        })));
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
