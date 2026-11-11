package com.codeway.auth;

/** The signed-in user for the current request thread (set by AuthInterceptor), for code far from the controller. */
public final class CurrentUser {
    private static final ThreadLocal<Long> ID = new ThreadLocal<>();
    private CurrentUser() {}
    public static Long get() { return ID.get(); }
    static void set(Long id) { ID.set(id); }
    static void clear() { ID.remove(); }
}
