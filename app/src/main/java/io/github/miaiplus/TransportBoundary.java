package io.github.miaiplus;

import java.io.IOException;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.UndeclaredThrowableException;

/** Reflection/proxy wrappers must not turn recoverable network failures into dispatcher errors. */
final class TransportBoundary {
    private TransportBoundary() {}
    interface Operation { Object run() throws Throwable; }
    static Object execute(Operation operation) throws IOException {
        try { return operation.run(); }
        catch (Throwable failure) {
            Throwable cause = failure;
            for (int depth = 0; depth < 16 && cause.getCause() != null; depth++) {
                if (!(cause instanceof InvocationTargetException || cause instanceof UndeclaredThrowableException
                    || cause.getClass().getSimpleName().equals("InvocationTargetError"))) break;
                cause = cause.getCause();
            }
            if (cause instanceof VirtualMachineError fatal) throw fatal;
            if (cause instanceof ThreadDeath fatal) throw fatal;
            if (cause instanceof IOException io) throw io;
            // Do not attach a reflective failure that may contain a request body or credentials.
            throw new IOException("外部模型网络适配失败");
        }
    }
}
