"use client";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, rememberToken, type User } from "./api";
import { supabase } from "./supabase";
import { recordVisit, type LangId } from "./lang";

interface AuthCtx {
  user: User | null;
  loading: boolean;
  /** Redirects to GitHub; the user comes back to /auth/callback, then `next` (default /dashboard). */
  signInWithGitHub: (next?: string) => Promise<void>;
  signOut: () => Promise<void>;
  /** The learner picked another language: remember it (server and here) so every page follows. */
  setLanguage: (language: LangId) => void;
  /** Change what the learner is called; throws with the server's message if it's not allowed. */
  setDisplayName: (name: string) => Promise<void>;
}
const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const sb = supabase();
    let alive = true;
    // Load the Codeway profile for the current Supabase session (if any). Called on start and on sign-in.
    const load = async (token: string | null) => {
      rememberToken(token);
      if (!token) { if (alive) { setUser(null); setLoading(false); } return; }
      try { const me = await api<User>("/auth/me"); if (alive) setUser(me); }
      catch { if (alive) setUser(null); }
      finally { if (alive) setLoading(false); }
    };
    sb.auth.getSession().then(({ data }) => load(data.session?.access_token ?? null));
    // Don't await Supabase calls inside this callback (supabase-js can deadlock); defer the profile load instead.
    const { data: sub } = sb.auth.onAuthStateChange((event, session) => {
      if (event === "SIGNED_IN") setTimeout(() => load(session?.access_token ?? null), 0);
      else if (event === "TOKEN_REFRESHED") rememberToken(session?.access_token ?? null);
      else if (event === "SIGNED_OUT") { rememberToken(null); setUser(null); }
    });
    return () => { alive = false; sub.subscription.unsubscribe(); };
  }, []);

  const signInWithGitHub = useCallback(async (next = "/dashboard") => {
    const redirectTo = `${window.location.origin}/auth/callback?next=${encodeURIComponent(next)}`;
    const { error } = await supabase().auth.signInWithOAuth({ provider: "github", options: { redirectTo } });
    if (error) throw new Error(error.message);
  }, []);

  const signOut = useCallback(async () => {
    rememberToken(null);
    setUser(null);
    await supabase().auth.signOut().catch(() => {});
  }, []);

  const setLanguage = useCallback((language: LangId) => {
    recordVisit(language);
    setUser((u) => (u && u.preferredLanguage !== language ? { ...u, preferredLanguage: language } : u));
  }, []);

  const setDisplayName = useCallback(async (name: string) => {
    const me = await api<User>("/auth/me", { displayName: name }, "PATCH");
    setUser((u) => (u ? { ...u, displayName: me.displayName } : u));
  }, []);

  const value = useMemo(() => ({ user, loading, signInWithGitHub, signOut, setLanguage, setDisplayName }), [user, loading, signInWithGitHub, signOut, setLanguage, setDisplayName]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error("useAuth outside provider");
  return c;
}
