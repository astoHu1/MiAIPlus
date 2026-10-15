package io.github.miaiplus;

import java.net.URI;

public final class Endpoint {
    private Endpoint() {}
    public static String validate(String endpoint) {
        URI u = URI.create(endpoint);
        if (u.getHost() == null || u.getUserInfo() != null || u.getQuery() != null || u.getFragment() != null)
            throw new IllegalArgumentException("API 地址必须是无查询参数的完整 URL");
        if (!"https".equals(u.getScheme()) && !"http".equals(u.getScheme()))
            throw new IllegalArgumentException("API 仅支持 HTTP(S)");
        if ("http".equals(u.getScheme())) {
            String h = u.getHost();
            boolean local = h.equals("localhost") || h.equals("127.0.0.1") || h.equals("[::1]");
            if (h.matches("[0-9]{1,3}(\\.[0-9]{1,3}){3}")) {
                String[] octets = h.split("\\.");
                int[] ip = new int[4]; boolean valid = true;
                for (int i = 0; i < 4; i++) { ip[i] = Integer.parseInt(octets[i]); if (ip[i] > 255) valid = false; }
                local |= valid && (ip[0] == 10 || (ip[0] == 192 && ip[1] == 168) || (ip[0] == 172 && ip[1] >= 16 && ip[1] <= 31));
            }
            if (!local) throw new IllegalArgumentException("公网 API 请使用 HTTPS；HTTP 仅供本机/局域网推理");
        }
        return endpoint.replaceAll("/+$", "");
    }
}
