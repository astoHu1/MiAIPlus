package io.github.miaiplus;

import android.content.SharedPreferences;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.UUID;
import org.json.JSONArray;
import org.json.JSONException;
import org.json.JSONObject;

/** Named endpoints stay in module-private preferences; flat keys remain the runtime snapshot. */
public final class EndpointProfiles {
    private static final String PROFILES = "endpointProfiles", ACTIVE = "activeEndpointProfile";

    public static final class Profile {
        public final String id, name, endpoint, apiKey, model, api;

        public Profile(String id, String name, String endpoint, String apiKey, String model, String api) {
            this.id = id; this.name = name.trim(); this.endpoint = endpoint.trim();
            this.apiKey = apiKey; this.model = model.trim(); this.api = api;
        }

        public static Profile blank(String name) {
            return new Profile(UUID.randomUUID().toString(), name, "", "", "", "auto");
        }

        public Profile validated(boolean external) {
            if (name.isBlank()) throw new IllegalArgumentException("请填写配置名称");
            String url = external || !endpoint.isBlank() ? Endpoint.validate(endpoint) : "";
            if (external && model.isBlank()) throw new IllegalArgumentException("请填写模型名称");
            ModelProtocol.resolve(api, model);
            return new Profile(id, name, url, apiKey, model, api);
        }

        public String encode() {
            try {
                return new JSONObject().put("id", id).put("name", name).put("endpoint", endpoint)
                    .put("apiKey", apiKey).put("model", model).put("api", api).toString();
            } catch (JSONException e) { throw new IllegalStateException("无法保存 API 配置"); }
        }

        public static Profile decode(String value) {
            try {
                JSONObject p = new JSONObject(value);
                Profile profile = new Profile(p.getString("id"), p.getString("name"), p.getString("endpoint"),
                    p.getString("apiKey"), p.getString("model"), p.getString("api"));
                if (profile.id.isBlank() || profile.name.isBlank()) throw new JSONException("Missing identity");
                ModelProtocol.resolve(profile.api, profile.model);
                return profile;
            } catch (JSONException | IllegalArgumentException e) {
                throw new IllegalStateException("无法读取 API 配置，请保留应用数据后重试");
            }
        }
    }

    public final List<Profile> profiles;
    public final String activeId;

    public EndpointProfiles(SharedPreferences prefs) {
        profiles = new ArrayList<>();
        String encoded = prefs.getString(PROFILES, null);
        if (encoded == null) {
            Profile legacy = new Profile("legacy", "默认配置", prefs.getString("endpoint", ""),
                prefs.getString("apiKey", ""), prefs.getString("model", ""), prefs.getString("api", "auto"));
            profiles.add(legacy); activeId = legacy.id;
        } else {
            try {
                JSONArray values = new JSONArray(encoded);
                HashSet<String> ids = new HashSet<>(), names = new HashSet<>();
                for (int i = 0; i < values.length(); i++) {
                    Profile profile = Profile.decode(values.getString(i));
                    if (!ids.add(profile.id) || !names.add(profile.name)) throw new JSONException("Duplicate profile");
                    profiles.add(profile);
                }
                activeId = prefs.getString(ACTIVE, "");
                active();
            } catch (JSONException e) { throw new IllegalStateException("无法读取 API 配置，请保留应用数据后重试"); }
        }
    }

    public Profile active() { return find(activeId); }

    public Profile find(String id) {
        for (Profile profile : profiles) if (profile.id.equals(id)) return profile;
        throw new IllegalStateException("API 配置不存在");
    }

    /** Stage the complete collection and active snapshot in the caller's single preferences edit. */
    public void write(SharedPreferences.Editor editor, Profile selected, boolean removeCurrent) {
        List<Profile> next = new ArrayList<>(profiles);
        if (removeCurrent) next.removeIf(p -> p.id.equals(activeId));
        int index = -1;
        for (int i = 0; i < next.size(); i++) {
            Profile profile = next.get(i);
            if (profile.id.equals(selected.id)) index = i;
            else if (profile.name.equals(selected.name)) throw new IllegalArgumentException("配置名称已存在，请换一个名称");
        }
        if (index < 0) next.add(selected); else next.set(index, selected);
        JSONArray values = new JSONArray();
        for (Profile profile : next) values.put(profile.encode());
        editor.putString(PROFILES, values.toString()).putString(ACTIVE, selected.id)
            .putString("endpoint", selected.endpoint).putString("apiKey", selected.apiKey)
            .putString("model", selected.model).putString("api", selected.api);
    }
}
