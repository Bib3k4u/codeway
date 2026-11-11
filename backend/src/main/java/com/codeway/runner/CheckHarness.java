package com.codeway.runner;

/** Source of the tiny test harness injected next to learner code. Hidden tests call Check.run(...). */
final class CheckHarness {
    static final String PATH = "Check.java";
    static final String SOURCE = """
            public final class Check {
                public interface Body { void run() throws Throwable; }
                private static final String NONCE = System.getProperty("check.nonce", "");

                public static void run(String name, Body body) {
                    try { body.run(); emit(name, true, ""); }
                    catch (Throwable t) {
                        String m = t.getMessage() != null ? t.getMessage() : t.toString();
                        emit(name, false, m);
                    }
                }
                public static void eq(Object expected, Object actual) {
                    if (expected == null ? actual != null : !expected.equals(actual))
                        throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
                }
                public static void eq(double expected, double actual, double eps) {
                    if (Math.abs(expected - actual) > eps)
                        throw new AssertionError("expected <" + expected + "> but got <" + actual + ">");
                }
                public static void isTrue(boolean cond, String message) {
                    if (!cond) throw new AssertionError(message);
                }
                public static <T extends Throwable> void throwsEx(Class<T> type, Body body) {
                    try { body.run(); }
                    catch (Throwable t) {
                        if (type.isInstance(t)) return;
                        throw new AssertionError("expected " + type.getSimpleName() + " but got " + t);
                    }
                    throw new AssertionError("expected " + type.getSimpleName() + " but nothing was thrown");
                }
                private static void emit(String name, boolean ok, String msg) {
                    String clean = msg.replace('\\t', ' ').replace('\\n', ' ').replace('\\r', ' ');
                    if (clean.length() > 300) clean = clean.substring(0, 300);
                    System.out.println("@@T" + NONCE + "\\t" + name.replace('\\t', ' ') + "\\t" + ok + "\\t" + clean);
                }
            }
            """;
    private CheckHarness() {}
}
