package android.util;
/** Test shim for android.util.Log (the framework version is native). */
public final class Log {
    private Log() {}
    private static int p(String l, String t, String m) { System.err.println(l + "/" + t + ": " + m); return 0; }
    public static int e(String t, String m) { return p("E", t, m); }
    public static int e(String t, String m, Throwable tr) { return p("E", t, m + " " + tr); }
    public static int w(String t, String m) { return p("W", t, m); }
    public static int i(String t, String m) { return p("I", t, m); }
    public static int d(String t, String m) { return p("D", t, m); }
    public static String getStackTraceString(Throwable tr) { java.io.StringWriter sw = new java.io.StringWriter(); tr.printStackTrace(new java.io.PrintWriter(sw)); return sw.toString(); }
}
