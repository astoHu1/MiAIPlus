package io.github.miaiplus;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ActionTest {
    @Test public void upstreamTapPreservesCoordinates() throws Exception {
        Action tap = Action.parseAutoGlm("<think>点击搜索</think><answer>do(action=\"Tap\", element=[500, 125])</answer>");
        assertEquals("tap", tap.name); assertArrayEquals(new double[]{500,125}, tap.point("element"), .001);
    }
    @Test public void literalTextPreservesUnicodeAndEscapedDelimiters() throws Exception {
        for (String[] sample : new String[][]{
            {"do(action='Type', text='你好,世界（A=B）')", "你好,世界（A=B）"},
            {"do(action=\"Type\", text=\"a, [b] \\\"c\\\" = d\")", "a, [b] \"c\" = d"}
        }) {
            assertEquals(sample[0], sample[1], Action.parseAutoGlm(sample[0]).data.getString("text"));
        }
    }
    @Test public void finishAndConfirm() throws Exception {
        assertEquals("finish", Action.parseAutoGlm("finish(message=\"已打开\")").name);
        Action a = Action.parseAutoGlm("do(action=\"Tap\", element=[1,999], message=\"发送消息\")");
        assertEquals("发送消息", a.data.getString("message"));
        assertEquals("take_over", Action.parseAutoGlm("do(action='Take_over', message='请登录')").name);
    }
    @Test public void invalidCoordinatesAndCodeRejected() throws Exception {
        for (String s : new String[]{
            "do(action='Tap', element=[-1,20])", "do(action='Tap', element=[1001,20])",
            "do(action='Tap', element=[1])", "do(action='Type', text=__import__('os').system('id'))",
            "do(action='Type', text='hello'); evil()", "do(action='Type', text='hello' + 'world')",
            "do(action='Tap', action='Home')", "do(action='Run', command='rm -rf /')",
            "do(action='Type', text='unterminated)", "do(action='Swipe', start=[1,2])"
        }) {
            try { Action.parseAutoGlm(s); fail("Accepted invalid action: " + s); }
            catch (IllegalArgumentException | org.json.JSONException expected) { }
        }
    }
    @Test public void phoneUseContractRequiresKnownAction() throws Exception {
        assertEquals("swipe", new Action(new JSONObject("{\"action\":\"swipe\",\"start\":[0,1000],\"end\":[1000,0]}")).name);
        try { new Action(new JSONObject("{\"action\":\"launch\"}")); fail(); } catch (IllegalArgumentException expected) { }
    }
}
