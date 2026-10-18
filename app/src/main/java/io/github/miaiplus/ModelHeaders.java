package io.github.miaiplus;

import io.github.miaiplus.hook.Reflector;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.UUID;

/** Opaque session identifiers: never send native message IDs, prompts or account identifiers. */
final class ModelHeaders {
    static final String SESSION = "x-opencode-session";
    static final String USER_AGENT = "MiAIPlus/" + BuildConfig.VERSION_NAME;
    private static final LinkedHashMap<String, String> sessions = new LinkedHashMap<>(16, 0.75f, true);

    static String identifier(String identity) {
        return UUID.nameUUIDFromBytes(("MiAIPlus:" + identity).getBytes(StandardCharsets.UTF_8)).toString();
    }

    /** Native message IDs survive repeated calls and separate identical prompts in distinct chats. */
    static String nativeSession(List<?> messages) {
        java.util.ArrayList<String> ids = new java.util.ArrayList<>();
        for (Object message : messages) {
            if ("SYSTEM".equals(String.valueOf(Reflector.callMethod(message, "getRole")))) continue;
            Object id = Reflector.callMethod(message, "getId");
            if (id instanceof String value && !value.isBlank()) ids.add(identifier("message:" + value));
        }
        return sessionForMessages(ids);
    }

    static synchronized String sessionForMessages(List<String> ids) {
        String session = null;
        for (String id : ids) {
            session = sessions.get(id);
            if (session != null) break;
        }
        if (session == null) session = ids.isEmpty() ? UUID.randomUUID().toString() : ids.get(0);
        for (String id : ids) sessions.put(id, session);
        while (sessions.size() > 4096) sessions.remove(sessions.keySet().iterator().next());
        return session;
    }

    static synchronized void clear() { sessions.clear(); }
    private ModelHeaders() { }
}
