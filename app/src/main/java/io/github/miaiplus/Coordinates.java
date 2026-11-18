package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import java.util.Locale;
import java.util.Set;

/** Convert declared model coordinate units once at the executor boundary. */
public final class Coordinates {
    private Coordinates() {}
    static final class Invalid extends IllegalArgumentException {
        final String reason, space, field;
        Invalid(String reason, String space, String field, String message) {
            super(message); this.reason = reason; this.space = space; this.field = field;
        }
    }
    public static JSONObject normalize(JSONObject action, String space, int screenWidth, int screenHeight, int imageWidth, int imageHeight) throws Exception {
        double width, height;
        switch (space) {
            case "normalized" -> { width = 1000; height = 1000; }
            case "image_pixels" -> { width = imageWidth; height = imageHeight; }
            case "screen_pixels" -> { width = screenWidth; height = screenHeight; }
            default -> throw new Invalid("unknown_space", "unknown", "", "未知坐标单位，请显式选择image_pixels、screen_pixels或normalized");
        }
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("无效观察尺寸");
        JSONObject result = new JSONObject(action.toString());
        String name = action.optString("action").toLowerCase(Locale.ROOT).replace(' ', '_');
        String[] required = Set.of("tap", "double_tap", "long_press").contains(name)
            ? new String[]{"element"} : name.equals("swipe") ? new String[]{"start", "end"} : new String[0];
        for (String key : required) {
            if (!action.has(key))
                throw new Invalid("missing_point", space, key, "缺少" + key + "坐标");
        }
        for (String key : new String[]{"element", "start", "end"}) {
            if (!action.has(key)) continue;
            JSONArray point = action.optJSONArray(key);
            if (point == null || point.length() != 2)
                throw new Invalid("invalid_point", space, key, key + "坐标必须是二元数字数组");
            Object rawX = point.opt(0), rawY = point.opt(1);
            if (!(rawX instanceof Number) || !(rawY instanceof Number))
                throw new Invalid("invalid_point", space, key, key + "坐标必须是二元数字数组");
            double x = ((Number) rawX).doubleValue(), y = ((Number) rawY).doubleValue();
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x > width || y > height)
                throw new Invalid("out_of_range", space, key, key + "坐标超出声明的观察范围");
            result.put(key, new JSONArray().put(x * 1000 / width).put(y * 1000 / height));
        }
        return result;
    }
}
