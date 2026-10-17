package io.github.miaiplus;

/** Explicit APK profiles. Never infer compatibility from a version prefix or class availability. */
public final class HostCompatibility {
    private static volatile boolean modern;
    private HostCompatibility() {}

    static boolean select(String versionName, long versionCode) {
        modern = false;
        if (versionCode == 508002065L && "8.2.65.4714".equals(versionName)) return true;
        if (versionCode == 508003030L && "8.3.30.4420".equals(versionName)) {
            modern = true;
            return true;
        }
        return false;
    }

    static boolean modern() { return modern; }
    static String choose(String legacy, String current) { return modern ? current : legacy; }
    static String chatActivity() {
        return choose("com.xiaomi.voiceassistant.TabHostMainActivity",
            "com.xiaomi.voiceassistant.mainui.navigator.MainNavigatorActivity");
    }

    /** Keys are the original 8.2.65 bindings; values were checked against the 8.3.30 APK. */
    public static String name(String legacy) {
        if (!modern) return legacy;
        return switch (legacy) {
            case "as.a" -> "rs.a";
            case "bk.a" -> "mk.a";
            case "ck.a" -> "nk.a";
            case "com.xiaomi.voiceassistant.a5" -> "com.xiaomi.voiceassistant.g5";
            case "com.xiaomi.voiceassistant.mainui.c4" -> "com.xiaomi.voiceassistant.mainui.l4";
            case "com.xiaomi.voiceassistant.widget.s1" -> "com.xiaomi.voiceassistant.widget.a2";
            case "dp.a" -> "rp.a";
            case "ep.j" -> "sp.j";
            case "er.a0" -> "ur.e0";
            case "g51.a" -> "y51.a";
            case "gg2.d0$a" -> "mi2.d0$a";
            case "gg2.f" -> "mi2.f";
            case "gg2.f0$a" -> "mi2.f0$a";
            case "gg2.g0" -> "mi2.g0";
            case "gg2.i0" -> "mi2.i0";
            case "gg2.w" -> "mi2.w";
            case "gg2.z" -> "mi2.z";
            case "go.c" -> "uo.c";
            case "gp.e" -> "up.e";
            case "gp1.c" -> "cr1.c";
            case "hj.e" -> "sj.e";
            case "hl1.x" -> "mm1.z";
            case "io1.a" -> "up1.a";
            case "io1.h" -> "up1.h";
            case "kj.g" -> "vj.g";
            case "kj.i$b" -> "vj.i$b";
            case "ll1.f0" -> "rm1.f0";
            case "n02.e" -> "k22.e";
            case "oj.b" -> "zj.b";
            case "p31.g" -> "h41.g";
            case "pe1.q" -> "sf1.r";
            case "qm.b" -> "dn.b";
            case "qm.c" -> "dn.c";
            case "qm.i" -> "dn.i";
            case "qm.j" -> "dn.j";
            case "r32.e" -> "o52.e";
            case "s92.b" -> "pb2.c";
            case "sm.a" -> "fn.a";
            case "sm.b$a" -> "fn.b$a";
            case "sm.b$b" -> "fn.b$b";
            case "sm.b$d" -> "fn.b$d";
            case "sm.b$f" -> "fn.b$f";
            case "sm.f" -> "fn.f";
            case "sr.q0" -> "is.q0";
            case "um.h" -> "hn.k";
            case "uo1.f" -> "qq1.f";
            case "uo1.f$a" -> "qq1.f$a";
            case "xh1.a" -> "bj1.a";
            case "z82.j" -> "wa2.j";
            case "zd1.b0" -> "cf1.f1";
            case "zg2.l" -> "fj2.l";
            case "zg2.l0" -> "fj2.l0";
            case "com.aios.osbot.router.t" -> "com.aios.osbot.router.x";
            case "com.aios.osbot.router.a" -> "com.aios.osbot.router.d";
            case "com.aios.osbot.router.d0" -> "com.aios.osbot.router.j0";
            case "com.aios.osbot.router.n1" -> "com.aios.osbot.router.q1";
            default -> legacy;
        };
    }
}
