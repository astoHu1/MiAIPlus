package io.github.miaiplus.hook;

import java.lang.reflect.Constructor;
import java.lang.reflect.Field;
import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;
import java.util.ArrayList;
import java.util.List;

/** Small reflection helper for the version-pinned host types; no legacy framework dependency. */
public final class Reflector {
    private Reflector() {}
    public static Class<?> findClass(String name, ClassLoader loader) {
        try { return Class.forName(name, false, loader); }
        catch (ClassNotFoundException e) { throw new IllegalArgumentException("Missing host class: " + name, e); }
    }
    public static HookCallback.Unhook findAndHookMethod(String name, ClassLoader loader, String method, Object... signature) {
        return findAndHookMethod(findClass(name, loader), method, signature);
    }
    public static HookCallback.Unhook findAndHookMethod(Class<?> type, String name, Object... signature) {
        Class<?>[] args = new Class<?>[signature.length - 1];
        for (int i = 0; i < args.length; i++) args[i] = (Class<?>)signature[i];
        try { return Hooks.hookMethod(type.getDeclaredMethod(name, args), (HookCallback)signature[args.length]); }
        catch (ReflectiveOperationException e) { throw new IllegalArgumentException("Missing host method: " + name, e); }
    }
    private static Field field(Class<?> type, String name) {
        for (Class<?> current = type; current != null; current = current.getSuperclass()) {
            try { Field field = current.getDeclaredField(name); field.setAccessible(true); return field; }
            catch (NoSuchFieldException ignored) {}
        }
        throw new IllegalArgumentException("Missing host field: " + name);
    }
    public static Object getObjectField(Object receiver, String name) {
        try { return field(receiver.getClass(), name).get(receiver); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    public static Object getStaticObjectField(Class<?> type, String name) {
        try { return field(type, name).get(null); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    public static void setObjectField(Object receiver, String name, Object value) {
        try { field(receiver.getClass(), name).set(receiver, value); }
        catch (IllegalAccessException e) { throw new IllegalStateException(e); }
    }
    private static Class<?> boxed(Class<?> type) {
        if (!type.isPrimitive()) return type;
        if (type == boolean.class) return Boolean.class;
        if (type == int.class) return Integer.class;
        if (type == long.class) return Long.class;
        if (type == double.class) return Double.class;
        if (type == float.class) return Float.class;
        if (type == byte.class) return Byte.class;
        if (type == short.class) return Short.class;
        if (type == char.class) return Character.class;
        return Void.class;
    }
    private static int score(Class<?>[] types, Object[] args) {
        if (types.length != args.length) return -1;
        int score = 0;
        for (int i = 0; i < types.length; i++) {
            if (args[i] == null) { if (types[i].isPrimitive()) return -1; continue; }
            Class<?> boxed = boxed(types[i]);
            if (!boxed.isInstance(args[i])) return -1;
            if (boxed == args[i].getClass()) score += 100;
            else if (boxed != Object.class) score += 10;
        }
        return score;
    }
    private static boolean moreSpecific(Class<?>[] candidate, Class<?>[] previous) {
        boolean strict = false;
        for (int i = 0; i < candidate.length; i++) {
            Class<?> left = boxed(candidate[i]), right = boxed(previous[i]);
            if (!right.isAssignableFrom(left)) return false;
            strict |= left != right;
        }
        return strict;
    }
    public static Object callMethod(Object receiver, String name, Object... args) { return invoke(receiver.getClass(), receiver, name, false, args); }
    public static Object callStaticMethod(Class<?> type, String name, Object... args) { return invoke(type, null, name, true, args); }
    private static Object invoke(Class<?> type, Object receiver, String name, boolean isStatic, Object[] args) {
        Method best = null; int bestScore = -1;
        List<Method> methods = new ArrayList<>();
        for (Class<?> current = type; current != null; current = current.getSuperclass()) methods.addAll(List.of(current.getDeclaredMethods()));
        methods.addAll(List.of(type.getMethods()));
        for (Method method : methods) {
            if (!method.getName().equals(name) || Modifier.isStatic(method.getModifiers()) != isStatic) continue;
            int score = score(method.getParameterTypes(), args);
            if (score > bestScore || (score == bestScore && best != null
                && moreSpecific(method.getParameterTypes(), best.getParameterTypes()))) { best = method; bestScore = score; }
        }
        if (best == null) throw new IllegalArgumentException("Missing compatible host method: " + type.getName() + "." + name);
        for (Method method : methods) {
            if (method.getName().equals(name) && Modifier.isStatic(method.getModifiers()) == isStatic
                && score(method.getParameterTypes(), args) == bestScore
                && !java.util.Arrays.equals(method.getParameterTypes(), best.getParameterTypes())
                && !moreSpecific(best.getParameterTypes(), method.getParameterTypes()))
                throw new IllegalArgumentException("Ambiguous host overload: " + type.getName() + "." + name);
        }
        try { best.setAccessible(true); return best.invoke(receiver, args); }
        catch (InvocationTargetException e) { throw propagate(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    public static Object newInstance(Class<?> type, Object... args) {
        Constructor<?> best = null; int bestScore = -1;
        for (var constructor : type.getDeclaredConstructors()) {
            int score = score(constructor.getParameterTypes(), args);
            if (score > bestScore || (score == bestScore && best != null
                && moreSpecific(constructor.getParameterTypes(), best.getParameterTypes()))) { best = constructor; bestScore = score; }
        }
        if (best == null) throw new IllegalArgumentException("Missing compatible host constructor: " + type.getName());
        for (var constructor : type.getDeclaredConstructors()) {
            if (score(constructor.getParameterTypes(), args) == bestScore
                && !java.util.Arrays.equals(constructor.getParameterTypes(), best.getParameterTypes())
                && !moreSpecific(best.getParameterTypes(), constructor.getParameterTypes()))
                throw new IllegalArgumentException("Ambiguous host constructor: " + type.getName());
        }
        try { best.setAccessible(true); return best.newInstance(args); }
        catch (InvocationTargetException e) { throw propagate(e.getCause()); }
        catch (ReflectiveOperationException e) { throw new IllegalStateException(e); }
    }
    private static RuntimeException propagate(Throwable failure) {
        if (failure instanceof Error error) throw error;
        if (failure instanceof RuntimeException runtime) return runtime;
        return new IllegalStateException("Host invocation failed", failure);
    }
}
