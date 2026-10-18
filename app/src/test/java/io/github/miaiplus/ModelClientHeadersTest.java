package io.github.miaiplus;

import java.io.BufferedReader;
import java.io.InputStreamReader;
import java.net.ServerSocket;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import org.json.JSONArray;
import org.junit.Test;
import org.junit.runner.RunWith;
import org.robolectric.RobolectricTestRunner;
import static org.junit.Assert.*;

@RunWith(RobolectricTestRunner.class)
@org.robolectric.annotation.Config(sdk = 33)
public class ModelClientHeadersTest {
    @Test public void bothProtocolsSendStableTaskSessionAndOwnUserAgent() throws Exception {
        List<String> sessions = new ArrayList<>(), agents = new ArrayList<>(), paths = new ArrayList<>();
        var executor = java.util.concurrent.Executors.newSingleThreadExecutor();
        try (ServerSocket server = new ServerSocket(0, 3, java.net.InetAddress.getByName("127.0.0.1"))) {
            server.setSoTimeout(10000);
            var received = executor.submit(() -> {
                for (int request = 0; request < 3; request++) {
                    try (var socket = server.accept()) {
                        socket.setSoTimeout(5000);
                        var input = new BufferedReader(new InputStreamReader(socket.getInputStream(), StandardCharsets.UTF_8));
                        String path = input.readLine().split(" ")[1];
                        Map<String, String> headers = new java.util.TreeMap<>(String.CASE_INSENSITIVE_ORDER);
                        String line;
                        while ((line = input.readLine()) != null && !line.isEmpty()) {
                            int colon = line.indexOf(':');
                            headers.put(line.substring(0, colon), line.substring(colon + 1).trim());
                        }
                        int length = Integer.parseInt(headers.get("Content-Length"));
                        for (int i = 0; i < length; i++) if (input.read() < 0) throw new java.io.EOFException();
                        sessions.add(headers.get(ModelHeaders.SESSION)); agents.add(headers.get("User-Agent")); paths.add(path);
                        String response = path.endsWith("/responses")
                            ? "{\"id\":\"test\",\"status\":\"completed\",\"output\":[{\"type\":\"message\",\"role\":\"assistant\",\"content\":[{\"type\":\"output_text\",\"text\":\"ok\"}]}]}"
                            : "{\"choices\":[{\"message\":{\"role\":\"assistant\",\"content\":\"ok\"}}]}";
                        byte[] bytes = response.getBytes(StandardCharsets.UTF_8);
                        socket.getOutputStream().write(("HTTP/1.1 200 OK\r\nContent-Length: " + bytes.length
                            + "\r\nConnection: close\r\nContent-Type: application/json\r\n\r\n").getBytes(StandardCharsets.UTF_8));
                        socket.getOutputStream().write(bytes);
                    }
                }
                return null;
            });
            String endpoint = "http://127.0.0.1:" + server.getLocalPort() + "/v1";
            RunState run = new RunState("first-task", 1000);
            JSONArray messages = new JSONArray().put(ModelClient.message("user", "fixture"));
            for (String api : new String[]{"chat", "responses"}) {
                Config config = new Config(Map.of("endpoint", endpoint, "model", "fixture", "api", api));
                assertEquals("ok", ModelClient.complete(config, messages, null, run).getString("content"));
            }
            ModelClient.complete(new Config(Map.of("endpoint", endpoint, "model", "fixture")),
                messages, null, new RunState("second-task", 1000));
            received.get(10, java.util.concurrent.TimeUnit.SECONDS);
            assertEquals(List.of("/v1/chat/completions", "/v1/responses", "/v1/chat/completions"), paths);
            assertEquals(sessions.get(0), sessions.get(1));
            assertNotEquals(sessions.get(0), sessions.get(2));
            assertFalse(sessions.get(0).isBlank());
            assertFalse(sessions.get(0).contains("first-task"));
            assertEquals(List.of(ModelHeaders.USER_AGENT, ModelHeaders.USER_AGENT, ModelHeaders.USER_AGENT), agents);
        } finally { executor.shutdownNow(); }
    }
}
