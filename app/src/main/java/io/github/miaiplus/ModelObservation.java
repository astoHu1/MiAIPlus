package io.github.miaiplus;

import java.nio.charset.StandardCharsets;

/** Read-only model projection. The original frame remains the evidence used by every execution guard. */
final class ModelObservation {
    private static final int NATIVE_TREE_CHARS = 12000;
    private static final String CLASS_HINT = "[类名widget./view.省略android.前缀]\n";
    private static final int CLASS_HINT_BYTES = CLASS_HINT.getBytes(StandardCharsets.UTF_8).length;

    private ModelObservation() {}

    static String nativeTree(ScreenService.Frame frame) {
        return tree(frame, NATIVE_TREE_CHARS);
    }

    static String externalTree(ScreenService.Frame frame) {
        // The independent loop previously received the whole collected tree. Do not reduce its information window.
        return tree(frame, Integer.MAX_VALUE);
    }

    static String tree(ScreenService.Frame frame, int limit) {
        if (limit < 128) throw new IllegalArgumentException("模型树预算过小");
        String source = frame.tree();
        if (source.isEmpty()) return frame.isBackground()
            ? "（本机未提供后台屏的无障碍树，请只依据截图判断）"
            : "（本次未读取到无障碍节点，请依据截图判断；不代表页面没有控件）";

        int rows = 0, shortClasses = 0;
        for (int start = 0; start < source.length();) {
            rows++;
            if (source.startsWith("android.widget.", start) || source.startsWith("android.view.", start)) shortClasses++;
            int end = source.indexOf('\n', start);
            start = end < 0 ? source.length() : end + 1;
        }
        // A short dialog can cost more bytes for the legend than the prefixes save. Keep it verbatim.
        return project(source, rows, shortClasses, limit, 8L * shortClasses > CLASS_HINT_BYTES);
    }

    private static String project(String source, int rows, int shortClasses, int limit, boolean compactClasses) {
        long fullLength = (long) source.length() - (compactClasses ? 8L * shortClasses : 0)
            + (compactClasses ? CLASS_HINT.length() : 0);
        boolean clipped = fullLength > limit;

        // Reserve the complete omission notice before copying any row. Never emit half a node or surrogate pair.
        String largestNotice = omission(rows);
        StringBuilder output = new StringBuilder(Math.min(source.length(), limit));
        int included = 0;
        int shortened = 0;
        for (int start = 0; start < source.length();) {
            int end = source.indexOf('\n', start);
            if (end < 0) end = source.length();
            int prefix = compactClasses && (source.startsWith("android.widget.", start)
                || source.startsWith("android.view.", start)) ? 8 : 0;
            boolean newline = end < source.length() || clipped;
            int rowLength = end - start - prefix + (newline ? 1 : 0);
            int hintLength = shortened > 0 || prefix != 0 ? CLASS_HINT.length() : 0;
            int reserved = clipped ? largestNotice.length() : 0;
            if ((long) output.length() + rowLength + hintLength + reserved > limit) break;
            output.append(source, start + prefix, end);
            if (newline) output.append('\n');
            included++;
            if (prefix != 0) shortened++;
            start = end + 1;
        }
        // The native window may contain fewer prefixed classes than the full source. Check actual savings too.
        if (compactClasses && 8L * shortened <= CLASS_HINT_BYTES)
            return project(source, rows, shortClasses, limit, false);
        if (shortened > 0) output.insert(0, CLASS_HINT);
        if (included < rows) output.append(omission(rows - included));
        return output.toString();
    }

    private static String omission(int rows) {
        return "[模型树已截断：省略" + rows + "行已读取节点；内部读取另有250节点/约24000字符预算，未读取数量未知。未展示不代表不存在，请结合截图]\n";
    }
}
