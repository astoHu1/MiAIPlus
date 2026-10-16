package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.json.JSONTokener;
import java.util.Locale;
import java.util.Set;

/** A data-only parser. Never evaluate model-produced Python, JavaScript, or shell. */
public final class Action {
    public final JSONObject data;
    public final String name;
    private static final Set<String> ALLOWED = Set.of("tap", "double_tap", "long_press", "swipe", "type",
        "back", "home", "launch", "wait", "finish", "take_over", "ask_user");
    public Action(JSONObject data) throws Exception {
        this.data = data;
        String n = data.optString("action").toLowerCase(Locale.ROOT).replace(' ', '_');
        if (data.optString("_metadata").equals("finish")) n = "finish";
        if (n.equals("type_name")) n = "type";
        if (!ALLOWED.contains(n)) throw new IllegalArgumentException("不支持的动作: " + n);
        name = n;
        if (Set.of("tap", "double_tap", "long_press").contains(name)) point("element");
        if (name.equals("swipe")) { point("start"); point("end"); }
        if (name.equals("type") && !data.has("text")) throw new IllegalArgumentException("缺少 text");
        if (name.equals("launch") && data.optString("app").isBlank()) throw new IllegalArgumentException("缺少 app");
    }
    public double[] point(String key) throws Exception {
        JSONArray a = data.getJSONArray(key);
        if (a.length() != 2) throw new IllegalArgumentException("坐标必须是二元数组");
        double x = a.getDouble(0), y = a.getDouble(1);
        if (!Double.isFinite(x) || !Double.isFinite(y) || x < 0 || y < 0 || x > 1000 || y > 1000)
            throw new IllegalArgumentException("坐标必须在 0..1000 内");
        return new double[]{x, y};
    }
    public String fingerprint() throws Exception {
        JSONObject execution = new JSONObject(data.toString());
        execution.remove("intent");
        return execution.toString();
    }
    public static Action parseAutoGlm(String output) throws Exception {
        String s = output.trim();
        int answer = s.lastIndexOf("<answer>");
        if (answer >= 0) s = s.substring(answer + 8).replace("</answer>", "").trim();
        // Upstream models also emit <think>...</think> followed directly by do(...).
        int thinkEnd = s.lastIndexOf("</think>");
        if (thinkEnd >= 0) s = s.substring(thinkEnd + 8).trim();
        boolean finish = s.startsWith("finish(");
        if (!finish && !s.startsWith("do(")) throw new IllegalArgumentException("AutoGLM 返回值缺少 do()/finish()");
        int start = s.indexOf('(');
        if (!s.endsWith(")")) throw new IllegalArgumentException("动作未闭合");
        String args = s.substring(start + 1, s.length() - 1);
        JSONObject o = new JSONObject();
        // Parse named literal arguments with JSONTokener (supports single quoted strings).
        int pos = 0;
        while (pos < args.length()) {
            while (pos < args.length() && Character.isWhitespace(args.charAt(pos))) pos++;
            if (pos == args.length()) break;
            int keyStart = pos;
            while (pos < args.length() && (Character.isLetterOrDigit(args.charAt(pos)) || args.charAt(pos) == '_')) pos++;
            String key = args.substring(keyStart, pos);
            while (pos < args.length() && Character.isWhitespace(args.charAt(pos))) pos++;
            if (key.isEmpty() || pos == args.length() || args.charAt(pos++) != '=') throw new IllegalArgumentException("非法动作参数");
            int valueStart = pos, depth = 0;
            char quote = 0;
            boolean escaped = false;
            while (pos < args.length()) {
                char c = args.charAt(pos);
                if (quote != 0) {
                    if (escaped) escaped = false;
                    else if (c == '\\') escaped = true;
                    else if (c == quote) quote = 0;
                } else if (c == '\'' || c == '"') quote = c;
                else if (c == '[' || c == '{') depth++;
                else if (c == ']' || c == '}') depth--;
                else if (c == ',' && depth == 0) break;
                pos++;
            }
            String literal = args.substring(valueStart, pos).trim();
            if (quote != 0 || depth != 0 || literal.isEmpty()) throw new IllegalArgumentException("非法参数字面量");
            if (o.has(key)) throw new IllegalArgumentException("重复参数");
            char first = literal.charAt(0);
            if (first != '\'' && first != '"' && first != '[' && first != '{'
                && !literal.matches("-?[0-9]+(?:\\.[0-9]+)?") && !Set.of("True", "False", "None").contains(literal))
                throw new IllegalArgumentException("仅允许数据字面量");
            if (literal.equals("True")) literal = "true";
            if (literal.equals("False")) literal = "false";
            if (literal.equals("None")) literal = "null";
            JSONTokener tokener = new JSONTokener(literal);
            Object v = tokener.nextValue();
            if (tokener.nextClean() != 0) throw new IllegalArgumentException("参数后有额外代码");
            o.put(key, v);
            if (pos < args.length()) pos++;
        }
        if (finish) o.put("action", "finish");
        return new Action(o);
    }
}
