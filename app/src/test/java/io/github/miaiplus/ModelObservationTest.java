package io.github.miaiplus;

import java.nio.charset.StandardCharsets;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class ModelObservationTest {
    private static final String BUTTON = "android.widget.Button [100,100][300,300] window=3 id=submit enabled=true clickable text=提交\n";

    @Test public void onlyKnownClassPrefixesAreShortenedAndAllEvidenceFieldsArePreserved() {
        String source = BUTTON.repeat(10)
            + "android.view.View [0,0][100,100] window=9 id=overlay enabled=false description=android.widget.Button\\n不是新节点\n"
            + "com.example.android.widget.Button [0,300][300,500] window=3 enabled=true editable focused [password redacted]\n";
        String output = ModelObservation.externalTree(frame(source));
        assertTrue(output.contains("widget.Button [100,100][300,300] window=3 id=submit enabled=true clickable text=提交\n"));
        assertTrue(output.contains("view.View [0,0][100,100] window=9 id=overlay enabled=false description=android.widget.Button\\n不是新节点\n"));
        assertTrue(output.contains("com.example.android.widget.Button [0,300][300,500] window=3 enabled=true editable focused [password redacted]\n"));
        assertFalse(output.contains("模型树已截断"));
    }

    @Test public void nativeBudgetKeepsWholeRowsAndCountsOnlyReadButOmittedRows() {
        StringBuilder source = new StringBuilder();
        for (int i = 0; i < 250; i++) source.append(BUTTON.replace("id=submit", "id=item" + i));
        ScreenService.Frame frame = frame(source.toString());
        String output = ModelObservation.nativeTree(frame);
        assertTrue(output.length() <= 12000);
        assertTrue(output.contains("模型树已截断：省略"));
        assertTrue(output.contains("未读取数量未知"));
        int included = 0;
        for (String line : output.split("\n")) {
            if (line.startsWith("widget.")) {
                assertTrue(line.endsWith("text=提交"));
                assertTrue(line.contains("id=item" + included + " "));
                included++;
            }
        }
        assertTrue(included > 0 && included < 250);
        assertTrue(output.contains("省略" + (250 - included) + "行已读取节点"));
        assertEquals(source.toString(), frame.tree());
    }

    @Test public void exactBudgetDoesNotDropRowsToReserveAnUnneededTruncationNotice() {
        ScreenService.Frame frame = frame(BUTTON.repeat(3));
        String full = ModelObservation.externalTree(frame);
        assertEquals(full, ModelObservation.tree(frame, full.length()));
        String clipped = ModelObservation.tree(frame, full.length() - 1);
        assertTrue(clipped.length() < full.length());
        assertTrue(clipped.contains("模型树已截断"));
    }

    @Test public void twelveThousandCharacterBoundaryIsExactAndNeverCutsALongRow() {
        for (int size : new int[]{11999, 12000, 12001}) {
            String source = "custom.Node text=" + "字".repeat(size - "custom.Node text=".length() - 1) + "\n";
            String output = ModelObservation.nativeTree(frame(source));
            assertTrue(output.length() <= 12000);
            if (size <= 12000) assertEquals(source, output);
            else {
                assertTrue(output.contains("省略1行已读取节点"));
                assertFalse(output.contains("custom.Node"));
            }
        }
    }

    @Test public void anOversizedSingleNodeIsOmittedInsteadOfEmittingPartialMetadataOrEmoji() {
        String source = BUTTON.replace("提交", "😀\\n\\r\\\\".repeat(2000));
        String output = ModelObservation.nativeTree(frame(source));
        assertTrue(output.length() <= 12000);
        assertTrue(output.contains("省略1行已读取节点"));
        assertFalse(output.contains("id=submit"));
        assertFalse(output.contains("😀"));
        assertFalse(Character.isHighSurrogate(output.charAt(output.length() - 1)));
    }

    @Test public void chineseEmojiEscapingAndIdenticalNodesRemainInOriginalOrder() {
        String row = BUTTON.replace("提交", "否：不得提交😀\\n二\\r三\\\\四");
        String output = ModelObservation.externalTree(frame(row + row));
        assertEquals(2, output.lines().filter(line -> line.startsWith("android.widget.Button")).count());
        assertTrue(output.contains("text=否：不得提交😀\\n二\\r三\\\\四\n"));
        assertFalse(output.contains("\n二"));
        assertTrue(output.endsWith(row));
    }

    @Test public void externalProjectionKeepsAllCollectedRowsRatherThanAdoptingTheNativeWindow() {
        String source = BUTTON.repeat(250);
        String output = ModelObservation.externalTree(frame(source));
        assertTrue(output.length() > 12000);
        assertTrue(output.length() < source.length());
        assertEquals(250, output.lines().filter(line -> line.startsWith("widget.Button")).count());
        assertFalse(output.contains("模型树已截断"));
    }

    @Test public void unavailableBackgroundTreeIsDistinctFromAClippedModelView() {
        String background = ModelObservation.nativeTree(new ScreenService.Frame("image", "", 1000, 1000,
            "app", 500, 500, 7, "generation"));
        String foreground = ModelObservation.nativeTree(frame(""));
        assertTrue(background.contains("本机未提供后台屏"));
        assertFalse(background.contains("模型树已截断"));
        assertTrue(foreground.contains("未读取到无障碍节点"));
        assertFalse(foreground.contains("本机未提供后台屏"));
    }

    @Test public void aLastRowWithoutNewlineIsPreservedAsOneWholeRow() {
        String source = BUTTON.substring(0, BUTTON.length() - 1);
        assertEquals(source, ModelObservation.externalTree(frame(source)));
    }

    @Test public void shortTreesRemainVerbatimUntilPrefixSavingsExceedTheUtf8LegendCost() {
        String longOutput = ModelObservation.externalTree(frame(BUTTON.repeat(20)));
        String legend = longOutput.substring(0, longOutput.indexOf('\n') + 1);
        int legendBytes = legend.getBytes(StandardCharsets.UTF_8).length;
        int below = legendBytes / 8;
        for (int count : new int[]{1, 2, below}) {
            String source = BUTTON.repeat(count);
            assertEquals(source, ModelObservation.externalTree(frame(source)));
            assertEquals(source, ModelObservation.nativeTree(frame(source)));
        }
        String source = BUTTON.repeat(below + 1);
        String output = ModelObservation.externalTree(frame(source));
        assertTrue(output.startsWith(legend));
        assertTrue(output.getBytes(StandardCharsets.UTF_8).length < source.getBytes(StandardCharsets.UTF_8).length);
    }

    @Test public void clippedNativeTreeDoesNotPayForALegendWhenOnlyOnePrefixWouldBeShown() {
        String first = BUTTON.replace("提交", "字".repeat(11800));
        String output = ModelObservation.nativeTree(frame(first + BUTTON.repeat(20)));
        assertTrue(output.startsWith("android.widget.Button"));
        assertTrue(output.contains("模型树已截断"));
        assertFalse(output.contains("省略android.前缀"));
        assertTrue(output.length() <= 12000);
    }

    @Test public void projectionCannotChangeTheFrameOrTheExecutionGuardVerdict() throws Exception {
        ScreenService.Frame planned = frame(BUTTON.repeat(160));
        ScreenService.Frame same = frame(BUTTON.repeat(160));
        ScreenService.Frame changed = frame(BUTTON.repeat(159) + BUTTON.replace("提交", "删除"));
        Action action = new Action(new JSONObject().put("action", "tap").put("element", new JSONArray().put(200).put(200)));
        assertTrue(PageGuard.canExecute(action, planned, same));
        assertFalse(PageGuard.canExecute(action, planned, changed));
        ModelObservation.nativeTree(planned);
        ModelObservation.externalTree(same);
        ModelObservation.nativeTree(changed);
        assertEquals(BUTTON.repeat(160), planned.tree());
        assertEquals("image", planned.image());
        assertEquals(500, planned.imageWidth());
        assertEquals(1000, planned.width());
        assertTrue(PageGuard.canExecute(action, planned, same));
        assertFalse(PageGuard.canExecute(action, planned, changed));
    }

    private static ScreenService.Frame frame(String source) {
        return new ScreenService.Frame("image", source, 1000, 1000, "app", 500, 500, 0, "");
    }
}
