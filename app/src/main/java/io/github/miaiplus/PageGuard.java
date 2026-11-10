package io.github.miaiplus;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.regex.Pattern;

/** Checks the control being acted on, rather than invalidating a whole page for an unrelated refresh. */
final class PageGuard {
    private static final Set<String> ELEMENT_ANCHORED = Set.of("tap", "double_tap", "long_press", "type");
    private static final Pattern NODE = Pattern.compile(
        "^(\\S+) \\[(-?\\d+),(-?\\d+)\\]\\[(-?\\d+),(-?\\d+)\\](.*)$");

    private PageGuard() { }

    static boolean needsFreshFrame(String action) {
        return ELEMENT_ANCHORED.contains(action);
    }

    /** Legacy exact-page comparison; execution uses the more local canExecute check below. */
    record Anchor(String foreground, String tree) { }

    static Anchor anchor(ScreenService.Frame frame) {
        return new Anchor(frame.foreground(), frame.tree());
    }

    static boolean same(Anchor before, Anchor after) {
        return Objects.equals(before.foreground(), after.foreground()) && Objects.equals(before.tree(), after.tree());
    }

    /**
     * Action coordinates have already been normalized to 0..1000. A false result means observe and replan,
     * never replay the old action. All nodes covering a point participate, including noninteractive containers:
     * comparing only the smallest clickable node misses a newly drawn dialog or transparent touch interceptor.
     */
    static boolean canExecute(Action action, ScreenService.Frame before, ScreenService.Frame current) throws Exception {
        String blocked = blockReason(action, before, current);
        // "The page changed" covers a rotation, a new dialog, a moving ad and a field that lost focus. The
        // model only needs to replan; a diagnosis needs to know which, especially when it repeats until a stop.
        if (blocked != null) PhoneUseLog.event("action_blocked", "action", action.name, "reason", blocked);
        return blocked == null;
    }

    /** Null when the action may run; otherwise a fixed label for the first check that failed. */
    static String blockReason(Action action, ScreenService.Frame before, ScreenService.Frame current) throws Exception {
        String screen = screenChange(before, current);
        if (screen != null) return screen;
        if (!needsFreshFrame(action.name)) return null;
        List<Node> oldNodes = nodes(before.tree());
        List<Node> newNodes = nodes(current.tree());
        if (action.name.equals("type")) {
            Node oldTarget = focusedEditable(oldNodes), newTarget = focusedEditable(newNodes);
            if (oldTarget == null) return "no_single_focused_field";
            if (!oldTarget.equals(newTarget)) return newTarget == null ? "focus_lost" : "field_changed";
            if (oldTarget.password()) return "password_field";
            if (oldTarget.metadata().contains("enabled=false")) return "field_disabled";
            double x = (oldTarget.left() + (double) oldTarget.right()) / 2;
            double y = (oldTarget.top() + (double) oldTarget.bottom()) / 2;
            return coverChange(covering(oldNodes, x, y), covering(newNodes, x, y), oldTarget,
                before, current, x, y);
        }
        double[] point = action.point("element");
        double x = Math.min(before.width() - 1, point[0] * before.width() / 1000);
        double y = Math.min(before.height() - 1, point[1] * before.height() / 1000);
        List<Node> oldCover = covering(oldNodes, x, y), newCover = covering(newNodes, x, y);
        Node oldTarget = target(oldCover), newTarget = target(newCover);
        if (!Objects.equals(oldTarget, newTarget)) return "target_changed";
        if (oldTarget != null && oldTarget.metadata().contains("enabled=false")) return "target_disabled";
        String cover = coverChange(oldCover, newCover, oldTarget, before, current, x, y);
        if (cover != null) return cover;
        // A canvas has no semantic target. Anchor the point and a surrounding margin in the actual JPEG,
        // rather than requiring unrelated ads elsewhere on the screen to stop animating.
        // A bare clickable container is not a semantic anchor: ads/videos often expose the same anonymous
        // FrameLayout while replacing every pixel inside it. Require local visual proof for those as well.
        return (oldTarget != null && oldTarget.labeled()) || VisualRegion.stable(before, current, x, y, x, y)
            ? null : "pixels_changed_unlabeled_target";
    }

    private static String coverChange(List<Node> before, List<Node> current, Node target,
        ScreenService.Frame oldFrame, ScreenService.Frame newFrame, double x, double y) {
        if (before.equals(current)) return null;
        if (before.size() != current.size()) return "covering_nodes_count";
        for (int index = 0; index < before.size(); index++) {
            Node old = before.get(index), now = current.get(index);
            // Content aggregated by a parent can include an unrelated ad. Only relax its content, never
            // geometry, identity, flags, order, or the target's own semantics; pixels supply the local proof.
            if (!old.structure().equals(now.structure())) return "covering_nodes_structure";
            if (old.equals(target) && !old.equals(now)) return "target_content";
        }
        return (target == null ? VisualRegion.stable(oldFrame, newFrame, x, y, x, y)
            : VisualRegion.stable(oldFrame, newFrame, target.left(), target.top(), target.right(), target.bottom()))
            ? null : "pixels_changed";
    }

    private static String screenChange(ScreenService.Frame before, ScreenService.Frame current) {
        if (before == null || current == null) return "frame_missing";
        if (before.width() <= 0 || before.height() <= 0) return "frame_size_invalid";
        if (before.width() != current.width() || before.height() != current.height()) return "screen_size_changed";
        if (before.imageWidth() != current.imageWidth() || before.imageHeight() != current.imageHeight()) return "image_size_changed";
        if (before.displayId() != current.displayId()) return "display_changed";
        if (!Objects.equals(before.generation(), current.generation())) return "display_recreated";
        if (!Objects.equals(before.foreground(), current.foreground())) return "foreground_changed";
        return null;
    }

    private record Node(String signature, int left, int top, int right, int bottom, String metadata, String content) {
        String structure() { return signature.substring(0, signature.length() - content.length()); }
        boolean labeled() { return !content.replace(" text=", "").replace(" description=", "").isBlank(); }
        boolean flag(String flag) {
            return (" " + metadata + " ").contains(" " + flag + " ");
        }
        boolean password() { return signature.contains("[password redacted]"); }
        boolean semantic() {
            return flag("clickable") || flag("editable") || content.contains("text=") || content.contains("description=");
        }
        long area() { return ((long) right - left) * ((long) bottom - top); }
    }

    private static List<Node> nodes(String tree) {
        List<Node> result = new ArrayList<>();
        if (tree == null || tree.isEmpty()) return result;
        for (String line : tree.split("\\n")) {
            var match = NODE.matcher(line);
            if (!match.matches()) continue;
            try {
                int left = Integer.parseInt(match.group(2)), top = Integer.parseInt(match.group(3));
                int right = Integer.parseInt(match.group(4)), bottom = Integer.parseInt(match.group(5));
                if (right <= left || bottom <= top) continue;
                String suffix = match.group(6);
                int text = suffix.indexOf(" text="), description = suffix.indexOf(" description=");
                int contentStart = text < 0 ? description : description < 0 ? text : Math.min(text, description);
                String metadata = (contentStart < 0 ? suffix : suffix.substring(0, contentStart)).trim();
                String content = contentStart < 0 ? "" : suffix.substring(contentStart);
                result.add(new Node(line, left, top, right, bottom, metadata, content));
            } catch (NumberFormatException ignored) {
                // A malformed accessibility line cannot supply a target anchor.
            }
        }
        return result;
    }

    private static List<Node> covering(List<Node> nodes, double x, double y) {
        List<Node> result = new ArrayList<>();
        for (Node node : nodes)
            if (node.left() <= x && x < node.right() && node.top() <= y && y < node.bottom()) result.add(node);
        return result;
    }

    private static Node target(List<Node> covering) {
        Node target = null;
        for (Node node : covering)
            if (node.semantic() && (target == null || node.area() <= target.area())) target = node;
        return target;
    }

    private static Node focusedEditable(List<Node> nodes) {
        Node result = null;
        for (Node node : nodes) {
            if (!node.flag("focused") || !node.flag("editable")) continue;
            if (result != null) return null;
            result = node;
        }
        return result;
    }
}
