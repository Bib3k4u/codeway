package com.codeway.auth;

/** The signed-in user for the current request thread (set by AuthInterceptor), for code far from the controller. */
public final class CurrentUser {
    private static final ThreadLocal<Long> ID = new ThreadLocal<>();
    private CurrentUser() {}
    public static Long get() { return ID.get(); }
    static void set(Long id) { ID.set(id); }
    static void clear() { ID.remove(); }

    /** Runs {@code job} on another thread as this user (so AI calls are counted against them), then restores the thread. */
    public static <T> T as(Long id, java.util.function.Supplier<T> job) {
        Long before = ID.get();
        if (id == null) ID.remove(); else ID.set(id);
        try { return job.get(); } finally { if (before == null) ID.remove(); else ID.set(before); }
    }
}
