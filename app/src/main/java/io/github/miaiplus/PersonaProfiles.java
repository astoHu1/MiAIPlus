package io.github.miaiplus;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Named persona prompts stay in module-private preferences; flat keys remain the runtime snapshot the host reads. */
public final class PersonaProfiles {
    private static final String PROFILES = "personaProfiles", ACTIVE = "activePersonaProfile";
    public static final String DEFAULT_NAME = "默认人设";
    public static final int MAX_NAME_CHARS = 20;

    public static final class Profile {
        public final String id, name, text;

        public Profile(String id, String name, String text) {
            this.id = id == null ? "" : id;
            this.name = name == null ? "" : name.trim();
            this.text = text == null ? "" : text;
        }

        public static Profile blank(String name) {
            return new Profile(UUID.randomUUID().toString(), name, "");
        }

        public Profile validated() {
            if (name.isBlank()) throw new IllegalArgumentException("请填写人设名称");
            if (name.length() > MAX_NAME_CHARS) throw new IllegalArgumentException("人设名称过长");
            if (text.trim().isEmpty()) throw new IllegalArgumentException("请填写提示词内容");
            return new Profile(id, name, Config.truncatePrompt(text));
        }

        public String encode() {
            try {
                return new JSONObject().put("id", id).put("name", name).put("text", text).toString();
            } catch (JSONException e) { throw new IllegalStateException("无法保存人设"); }
        }

        public static Profile decode(String value) {
            try {
                JSONObject p = new JSONObject(value);
                Profile profile = new Profile(p.getString("id"), p.getString("name"), p.getString("text"));
                if (profile.id.isBlank() || profile.name.isBlank()) throw new JSONException("Missing identity");
                if (profile.name.length() > MAX_NAME_CHARS || profile.text.length() > Config.MAX_CUSTOM_PROMPT_CHARS)
                    throw new JSONException("Oversized profile");
                return profile;
            } catch (JSONException | IllegalArgumentException e) {
                throw new IllegalStateException("无法读取人设");
            }
        }
    }

    public final List<Profile> profiles;
    public final String activeId;

    public PersonaProfiles(SharedPreferences prefs) {
        List<Profile> loaded = new ArrayList<>();
        String selected = null;
        String encoded = prefs.getString(PROFILES, null);
        if (encoded == null) {
            Profile legacy = new Profile("legacy", DEFAULT_NAME, prefs.getString(Config.CUSTOM_PROMPT_TEXT_KEY, ""));
            loaded.add(legacy); selected = legacy.id;
        } else {
            try {
                JSONArray values = new JSONArray(encoded);
                HashSet<String> ids = new HashSet<>(), names = new HashSet<>();
                for (int i = 0; i < values.length(); i++) {
                    Profile profile = Profile.decode(values.getString(i));
                    if (!ids.add(profile.id) || !names.add(profile.name)) throw new JSONException("Duplicate profile");
                    loaded.add(profile);
                }
                if (loaded.isEmpty()) throw new JSONException("Empty store");
                selected = prefs.getString(ACTIVE, "");
            } catch (JSONException | IllegalStateException e) {
                // A corrupt store must not crash the settings screen or the host read path.
                loaded.clear();
                Profile fallback = new Profile("fallback", DEFAULT_NAME, "");
                loaded.add(fallback); selected = fallback.id;
            }
        }
        profiles = List.copyOf(loaded);
        activeId = selected;
    }

    public Profile active() { return find(activeId); }

    public Profile find(String id) {
        for (Profile profile : profiles) if (profile.id.equals(id)) return profile;
        throw new IllegalStateException("人设不存在");
    }

    /** Stage the complete collection and the active profile's text snapshot in the caller's single preferences edit. */
    public void write(SharedPreferences.Editor editor, Profile selected, boolean removeCurrent) {
        List<Profile> next = new ArrayList<>(profiles);
        if (removeCurrent) next.removeIf(p -> p.id.equals(activeId));
        int index = -1;
        for (int i = 0; i < next.size(); i++) {
            Profile profile = next.get(i);
            if (profile.id.equals(selected.id)) index = i;
            else if (profile.name.equals(selected.name)) throw new IllegalArgumentException("人设名称已存在");
        }
        if (index < 0) next.add(selected); else next.set(index, selected);
        if (next.isEmpty()) throw new IllegalArgumentException("至少保留一个人设");
        JSONArray values = new JSONArray();
        for (Profile profile : next) values.put(profile.encode());
        editor.putString(PROFILES, values.toString()).putString(ACTIVE, selected.id)
            .putString(Config.CUSTOM_PROMPT_TEXT_KEY, selected.text);
    }
}
