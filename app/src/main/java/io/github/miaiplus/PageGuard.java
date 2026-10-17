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
        if (!sameScreen(before, current)) return false;
        if (!needsFreshFrame(action.name)) return true;
        List<Node> oldNodes = nodes(before.tree());
        List<Node> newNodes = nodes(current.tree());
        if (action.name.equals("type")) {
            Node oldTarget = focusedEditable(oldNodes), newTarget = focusedEditable(newNodes);
            if (oldTarget == null || !oldTarget.equals(newTarget) || oldTarget.password() || oldTarget.metadata().contains("enabled=false")) return false;
            double x = (oldTarget.left() + (double) oldTarget.right()) / 2;
            double y = (oldTarget.top() + (double) oldTarget.bottom()) / 2;
            return covering(oldNodes, x, y).equals(covering(newNodes, x, y));
        }
        double[] point = action.point("element");
        double x = Math.min(before.width() - 1, point[0] * before.width() / 1000);
        double y = Math.min(before.height() - 1, point[1] * before.height() / 1000);
        List<Node> oldCover = covering(oldNodes, x, y), newCover = covering(newNodes, x, y);
        if (!oldCover.equals(newCover)) return false;
        Node oldTarget = target(oldCover), newTarget = target(newCover);
        if (oldTarget != null) return !oldTarget.metadata().contains("enabled=false") && oldTarget.equals(newTarget);
        // Canvas / image-only pages have no accessible target to anchor to. Never guess from an empty tree.
        return before.image() != null && !before.image().isBlank() && before.image().equals(current.image());
    }

    private static boolean sameScreen(ScreenService.Frame before, ScreenService.Frame current) {
        return before != null && current != null && before.width() > 0 && before.height() > 0
            && before.width() == current.width() && before.height() == current.height()
            && before.imageWidth() == current.imageWidth() && before.imageHeight() == current.imageHeight()
            && before.displayId() == current.displayId() && Objects.equals(before.generation(), current.generation())
            && Objects.equals(before.foreground(), current.foreground());
    }

    private record Node(String signature, int left, int top, int right, int bottom, String metadata, String content) {
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
