package io.github.miaiplus;

import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Set;
import java.util.UUID;
import java.util.regex.Pattern;
import org.json.JSONArray;
import org.json.JSONObject;

/** Observation-local aliases. Resolving one still produces an ordinary guarded coordinate action. */
final class NodeTargets {
    private static final Set<String> ACTIONS = Set.of("tap", "double_tap", "long_press");
    private static final Pattern NODE = Pattern.compile(
        "^(\\S+) \\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\](.*)$");
    private final ScreenService.Frame frame;
    private final Map<String, Point> points;
    private final String tree;

    private record Point(double x, double y) { }

    static final class InvalidTarget extends IllegalArgumentException {
        final String code;
        InvalidTarget(String code, String message) { super(message); this.code = code; }
    }

    private NodeTargets(ScreenService.Frame frame, Map<String, Point> points, String tree) {
        this.frame = frame; this.points = points; this.tree = tree;
    }

    static NodeTargets capture(ScreenService.Frame frame) {
        String scope = UUID.randomUUID().toString().replace("-", "");
        Map<String, Point> points = new LinkedHashMap<>();
        StringBuilder annotated = new StringBuilder();
        int row = 0;
        for (String line : frame.tree().split("\n", -1)) {
            if (row++ > 0) annotated.append('\n');
            var match = NODE.matcher(line);
            Point point = match.matches() ? point(match, frame) : null;
            if (point == null) { annotated.append(line); continue; }
            String reference = scope + "-" + row;
            String suffix = match.group(6);
            int content = contentStart(suffix);
            int insert = content < 0 ? line.length() : line.length() - suffix.length() + content;
            annotated.append(line, 0, insert).append(" target=").append(reference).append(line.substring(insert));
            points.put(reference, point);
        }
        ScreenService.Frame projection = new ScreenService.Frame(frame.image(), annotated.toString(), frame.width(),
            frame.height(), frame.foreground(), frame.imageWidth(), frame.imageHeight(), frame.displayId(), frame.generation());
        String tree = ModelObservation.nativeTree(projection);
        // Read metadata, not page text, to retain only aliases visible in the bounded model window.
        Set<String> shown = new HashSet<>();
        for (String line : tree.split("\n")) {
            var match = NODE.matcher(line);
            if (!match.matches()) continue;
            String suffix = match.group(6);
            int content = contentStart(suffix);
            String metadata = content < 0 ? suffix : suffix.substring(0, content);
            for (String field : metadata.trim().split(" +"))
                if (field.startsWith("target=")) shown.add(field.substring("target=".length()));
        }
        points.keySet().retainAll(shown);
        return new NodeTargets(frame, points, tree);
    }

    String tree() { return tree; }

    JSONObject normalize(JSONObject request, ScreenService.Frame current) throws Exception {
        JSONObject action = request.getJSONObject("action");
        if (!action.has("target")) return Coordinates.normalize(action, request.optString("coordinate_space", "normalized"),
            current.width(), current.height(), current.imageWidth(), current.imageHeight());
        if (current != frame) throw new InvalidTarget("stale_target", "目标引用不属于当前观察，请使用本次新观察中的target");
        if (!ACTIONS.contains(action.optString("action").toLowerCase(Locale.ROOT).replace(' ', '_')))
            throw new InvalidTarget("unsupported_target_action", "target只支持tap、double_tap或long_press");
        if (action.has("element") || action.has("start") || action.has("end"))
            throw new InvalidTarget("ambiguous_target", "target不能与element/start/end坐标同时提交");
        Object reference = action.get("target");
        Point point = reference instanceof String ? points.get(reference) : null;
        if (point == null) throw new InvalidTarget("unknown_target", "目标引用无效或已过期，请使用本次新观察中的target");
        JSONObject resolved = new JSONObject(action.toString());
        resolved.remove("target");
        resolved.put("element", new JSONArray().put(point.x()).put(point.y()));
        return resolved;
    }

    private static Point point(java.util.regex.Matcher match, ScreenService.Frame frame) {
        String suffix = match.group(6);
        int content = contentStart(suffix);
        String metadata = content < 0 ? suffix : suffix.substring(0, content);
        if (!flag(metadata, "enabled=true") || (!flag(metadata, "clickable") && !flag(metadata, "editable"))
            || metadata.contains("[password redacted]") || frame.width() <= 0 || frame.height() <= 0) return null;
        try {
            int left = Math.max(0, Integer.parseInt(match.group(2))), top = Math.max(0, Integer.parseInt(match.group(3)));
            int right = Math.min(frame.width(), Integer.parseInt(match.group(4)));
            int bottom = Math.min(frame.height(), Integer.parseInt(match.group(5)));
            if (right <= left || bottom <= top) return null;
            return new Point((left + (double) right) * 500 / frame.width(),
                (top + (double) bottom) * 500 / frame.height());
        } catch (NumberFormatException malformed) { return null; }
    }

    private static boolean flag(String metadata, String flag) { return (" " + metadata.trim() + " ").contains(" " + flag + " "); }

    private static int contentStart(String suffix) {
        int text = suffix.indexOf(" text="), description = suffix.indexOf(" description=");
        return text < 0 ? description : description < 0 ? text : Math.min(text, description);
    }
}
