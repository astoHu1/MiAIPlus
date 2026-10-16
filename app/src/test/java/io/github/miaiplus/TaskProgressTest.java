package io.github.miaiplus;

import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class TaskProgressTest {
    @Test public void intentIsSeparateFromConfirmationAndPrivateInput() throws Exception {
        Action action = new Action(new JSONObject().put("action", "type").put("text", "private message")
            .put("intent", "填写搜索框"));
        assertEquals("填写搜索框", TaskProgress.intent(action));
        assertFalse(action.data.has("message"));
        assertFalse(action.data.optBoolean("requires_confirmation"));
        assertFalse(TaskProgress.intent(new Action(new JSONObject().put("action", "type").put("text", "private message")))
            .contains("private message"));
    }
    @Test public void displayTextIsBoundedAndRemovesControlAndBidiCharacters() {
        String text = TaskProgress.compact("\n\u202e" + "😀".repeat(70));
        assertEquals(60, text.codePointCount(0, text.length()));
        assertEquals("😀".repeat(60), text);
    }
    @Test public void unsupportedIntentValuesUseActionFallbackAndPreserveQuotes() throws Exception {
        assertEquals("等待页面加载", TaskProgress.intent(new Action(new JSONObject().put("action", "wait")
            .put("intent", new JSONObject()))));
        String intent = "查找\"订单\"与\\记录";
        assertEquals(intent, TaskProgress.intent(new Action(new JSONObject().put("action", "wait").put("intent", intent))));
        assertEquals("点击搜索框 · 正在判断下一步", TaskProgress.waiting("点击搜索框"));
    }
    @Test public void changingIntentCannotBypassRepeatedActionDetection() throws Exception {
        Action first = new Action(new JSONObject().put("action", "wait").put("intent", "检查页面"));
        Action second = new Action(new JSONObject().put("action", "wait").put("intent", "继续检查"));
        assertEquals(first.fingerprint(), second.fingerprint());
        assertEquals("检查页面", first.data.getString("intent"));
    }
}
