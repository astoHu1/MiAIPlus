package io.github.miaiplus;

import android.app.Application;
import android.os.Bundle;
import java.io.InputStream;
import java.net.HttpURLConnection;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.zip.GZIPInputStream;
import org.json.JSONArray;
import org.json.JSONObject;
import org.junit.After;
import org.junit.Before;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import org.robolectric.RuntimeEnvironment;
import org.robolectric.annotation.Config;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@Config(sdk = 35, application = Application.class)
public class TermuxMcpTest {
    private static final String TOKEN = "d".repeat(64);
    private static final String ENDPOINT = "http://127.0.0.1:18872/mcp";
    private final Application app = RuntimeEnvironment.getApplication();

    @Before @After public void clear() { io.github.miaiplus.Config.prefs(app).edit().clear().commit(); }

    private static PluginToolSpec tool(String source) {
        return new PluginToolSpec(PluginSettings.TERMUX_ID, "mcp",
            PluginToolSpec.mcpName(PluginSettings.TERMUX_ID, source), source, source, "Fixture",
            "{\"type\":\"object\"}", 60_000);
    }

    private static PluginSettings.Entry configured() {
        return new PluginSettings.Entry(PluginSettings.TERMUX_ID, PluginSettings.TERMUX_KIND, "Termux MCP",
            true, ENDPOINT, TOKEN, TermuxMcp.PROTOCOL, List.of(tool("run"), tool("run_digest"), tool("out_read")));
    }

    @Test public void configuredToolIsIndependentOfPhoneUseAndKeepsCredentialsInModule() throws Exception {
        assertNull(TermuxMcp.find(PluginSettings.load(app)));
        assertFalse(PluginSettings.termuxAutoStart(app));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.setTermuxAutoStart(app, true));
        var snapshot = PluginSettings.load(app);
        PluginSettings.saveTermux(app, snapshot.revision(), configured());
        assertFalse(PluginSettings.termuxAutoStart(app));
        Bundle catalog = PluginRuntime.catalog(app);
        String json = catalog.getString("tools");
        assertEquals(3, new JSONArray(json).length());
        assertTrue(json.contains("miaiplus_termux_run_digest"));
        assertFalse(json.contains(TOKEN));
        assertFalse(json.contains(ENDPOINT));
        PluginSettings.setTermuxAutoStart(app, true);
        assertTrue(PluginSettings.termuxAutoStart(app));
        PluginSettings.setEnabled(app, PluginSettings.TERMUX_ID, false);
        assertEquals(0, new JSONArray(PluginRuntime.catalog(app).getString("tools")).length());
        assertTrue(PluginSettings.termuxAutoStart(app));
    }

    @Test public void managedEntryCannotBeRedirectedOrEditedAsRemote() {
        assertThrows(IllegalArgumentException.class, () -> new PluginSettings.Entry(PluginSettings.TERMUX_ID,
            PluginSettings.TERMUX_KIND, "Termux MCP", true, "https://other.test/mcp", TOKEN, TermuxMcp.PROTOCOL, List.of()));
        assertThrows(IllegalArgumentException.class, () -> new PluginSettings.Entry(PluginSettings.TERMUX_ID,
            "mcp", "Termux MCP", true, ENDPOINT, TOKEN, TermuxMcp.PROTOCOL, List.of()));
        var snapshot = PluginSettings.load(app);
        var saved = PluginSettings.saveTermux(app, snapshot.revision(), configured());
        assertThrows(IllegalStateException.class, () -> PluginSettings.saveTermux(app, snapshot.revision(), configured()));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.deleteMcp(app, PluginSettings.TERMUX_ID));
        assertThrows(IllegalArgumentException.class, () -> PluginSettings.saveMcp(app, PluginSettings.TERMUX_ID,
            "Remote", "https://other.test/mcp", "", PluginSettings.MCP_PROTOCOL_VERSION));
        assertEquals(saved, PluginSettings.load(app));
    }

    @Test public void fixedServiceLauncherRejectsUntrustedInputsAndNeverOpensTermuxUi() {
        byte[] script = "fixture".getBytes(StandardCharsets.US_ASCII);
        String version = "a".repeat(64);
        String start = TermuxMcp.rootCommand(script, 10351, 18872, version);
        assertTrue(start.contains("start-foreground-service --user 0 -n com.termux/.app.TermuxService"));
        assertFalse(start.contains("TermuxActivity"));
        assertFalse(start.contains(TOKEN));
        assertTrue(start.contains("trap restore EXIT"));
        assertThrows(IllegalArgumentException.class, () -> TermuxMcp.rootCommand(script, 0, 18872, version));
        assertThrows(IllegalArgumentException.class, () -> TermuxMcp.rootCommand(script, 10351, 18872,
            "'; touch /data/local/tmp/x; #"));
        assertThrows(IllegalArgumentException.class, () -> TermuxMcp.rootCommand(script, 10351, 18872, version,
            "http://example.test/" + version, TOKEN, "YQ==", version));
        assertFalse(TermuxMcp.rootCommand(script, 10351, 18872, version,
            "http://127.0.0.1:18871/" + version, TOKEN, "YQ==", version).contains("TermuxActivity"));
    }

    @Test public void modelCannotConfirmItsOwnCommandOrPassHiddenUpstreamArguments() throws Exception {
        var tool = new PluginToolSpec(PluginSettings.TERMUX_ID, "mcp", "miaiplus_termux_run_digest",
            "run_digest", "run_digest", "Fixture",
            "{\"type\":\"object\",\"properties\":{\"cmd\":{\"type\":\"string\"},\"confirmed\":{\"type\":\"boolean\"}}}", 60_000);
        assertTrue(TermuxMcp.acceptsArguments(tool, new JSONObject().put("cmd", "id -u")));
        assertFalse(TermuxMcp.acceptsArguments(tool, new JSONObject().put("cmd", "id -u").put("confirmed", true)));
        assertFalse(TermuxMcp.acceptsArguments(tool, new JSONObject().put("cmd", "id -u").put("timeout", 200)));
    }

    @Test public void transferRequiresOneTimePathAndClosesAfterServingTheArchive() throws Exception {
        byte[] archive = "fixture data".getBytes(StandardCharsets.US_ASCII);
        try (var transfer = new TermuxMcp.Transfer(archive)) {
            URI url = URI.create(transfer.url());
            assertEquals("127.0.0.1", url.getHost());
            var incorrect = (HttpURLConnection) url.resolve("/wrong").toURL().openConnection();
            assertEquals(404, incorrect.getResponseCode());
            incorrect.disconnect();
            try (InputStream input = url.toURL().openStream()) {
                assertArrayEquals(archive, input.readAllBytes());
            }
        }
    }

    @Test public void apkAssetCanBeReadAsTheProvisionerArchive() throws Exception {
        try (var archive = new GZIPInputStream(app.getAssets().open("termux-mcp/upstream.bin"))) {
            assertEquals("LICENSE", new String(archive.readNBytes(7), StandardCharsets.US_ASCII));
        }
    }
}
