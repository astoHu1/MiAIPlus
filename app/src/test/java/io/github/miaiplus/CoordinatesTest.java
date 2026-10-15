package io.github.miaiplus;

import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.Test;
import static org.junit.Assert.*;

public class CoordinatesTest {
    @Test public void encodedScreenshotAndPhysicalScreenTargetSamePoint() throws Exception {
        JSONObject image = new JSONObject().put("action", "tap").put("element", new JSONArray("[187.2,793.6]"));
        JSONObject screen = new JSONObject().put("action", "tap").put("element", new JSONArray("[468,1984]"));
        JSONArray a = Coordinates.normalize(image, "image_pixels",1440,3200,576,1280).getJSONArray("element");
        JSONArray b = Coordinates.normalize(screen, "screen_pixels",1440,3200,576,1280).getJSONArray("element");
        assertEquals(325,a.getDouble(0),0.0001); assertEquals(620,a.getDouble(1),0.0001);
        assertEquals(a.getDouble(0),b.getDouble(0),0.0001); assertEquals(a.getDouble(1),b.getDouble(1),0.0001);
        assertEquals(187.2,image.getJSONArray("element").getDouble(0),0.0001);
    }
    @Test public void normalizedLegacyUnchangedAndSwipeEndpointsConverted() throws Exception {
        JSONObject swipe = new JSONObject("{action:swipe,start:[72,320],end:[216,960]}");
        JSONObject normalized = Coordinates.normalize(swipe,"image_pixels",1440,3200,288,1280);
        assertEquals(250,normalized.getJSONArray("start").getDouble(0),0.0001);
        assertEquals(750,normalized.getJSONArray("end").getDouble(1),0.0001);
        assertEquals(normalized.toString(),Coordinates.normalize(normalized,"normalized",1440,3200,288,1280).toString());
    }
    @Test public void rejectsMismatchedUnitsAndInvalidCoordinates() throws Exception {
        for (String input : new String[]{"[577,200]","[-1,20]","[30,1281]","[20]"}) {
            try { Coordinates.normalize(new JSONObject().put("action","tap").put("element",new JSONArray(input)),"image_pixels",1440,3200,576,1280); fail(); }
            catch (IllegalArgumentException expected) { }
        }
        try { Coordinates.normalize(new JSONObject(),"guess",1440,3200,576,1280); fail(); }
        catch (IllegalArgumentException expected) { }
    }
}
