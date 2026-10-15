package io.github.miaiplus;

import org.junit.Test;
import static org.junit.Assert.*;

public class EndpointTest {
    @Test public void supportsUserServiceAndLan() {
        assertEquals("https://api.example.com/v1", Endpoint.validate("https://api.example.com/v1/"));
        for (String host : new String[]{"127.0.0.1", "localhost", "[::1]", "10.1.2.3", "192.168.1.1", "172.16.2.1", "172.31.2.1"})
            assertEquals("http://" + host + ":8000/v1", Endpoint.validate("http://" + host + ":8000/v1"));
    }
    @Test public void rejectsRemoteCleartextAndCredentialUrls() {
        for (String url : new String[]{"http://api.example.com/v1", "http://10.evil.com/v1", "http://192.168.evil.com/v1",
            "http://172.32.1.1/v1", "http://10.999.2.1/v1", "https://key@api.example.com/v1", "https://api.example.com/v1?key=secret",
            "https://api.example.com/v1#secret", "file:///etc/passwd", "api.example.com"}) {
            try { Endpoint.validate(url); fail("Accepted " + url); } catch (IllegalArgumentException expected) { }
        }
    }
}
