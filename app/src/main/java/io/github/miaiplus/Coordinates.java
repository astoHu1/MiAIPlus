package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;

/** Convert declared model coordinate units once at the executor boundary. */
public final class Coordinates {
    private Coordinates() {}
    public static JSONObject normalize(JSONObject action, String space, int screenWidth, int screenHeight, int imageWidth, int imageHeight) throws Exception {
        double width, height;
        switch (space) {
            case "normalized" -> { width = 1000; height = 1000; }
            case "image_pixels" -> { width = imageWidth; height = imageHeight; }
            case "screen_pixels" -> { width = screenWidth; height = screenHeight; }
            default -> throw new IllegalArgumentException("未知坐标单位");
        }
        if (width <= 0 || height <= 0) throw new IllegalArgumentException("无效观察尺寸");
        JSONObject result = new JSONObject(action.toString());
        for (String key : new String[]{"element", "start", "end"}) {
            if (!action.has(key)) continue;
            JSONArray point = action.getJSONArray(key);
            if (point.length() != 2) throw new IllegalArgumentException("坐标必须是二元数组");
            double x = point.getDouble(0), y = point.getDouble(1);
            if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x > width || y > height)
                throw new IllegalArgumentException("坐标超出声明的观察范围");
            result.put(key, new JSONArray().put(x * 1000 / width).put(y * 1000 / height));
        }
        return result;
    }
}
