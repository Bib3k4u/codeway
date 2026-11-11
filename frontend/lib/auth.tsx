"use client";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api, tokenStore, type User } from "./api";

interface AuthCtx {
  user: User | null;
  loading: boolean;
  signIn: (mode: "login" | "register", email: string, password: string, name?: string) => Promise<void>;
  signOut: () => void;
}
const Ctx = createContext<AuthCtx | null>(null);

export function AuthProvider({ children }: { children: ReactNode }) {
  const [user, setUser] = useState<User | null>(null);
  const [loading, setLoading] = useState(true);

  useEffect(() => {
    const check = tokenStore.get() ? api<User>("/auth/me") : Promise.resolve(null);
    check.then(setUser).catch(() => tokenStore.clear()).finally(() => setLoading(false));
  }, []);

  const signIn = useCallback(async (mode: "login" | "register", email: string, password: string, name?: string) => {
    const r = await api<{ token: string; user: User }>(`/auth/${mode}`, { email, password, displayName: name });
    tokenStore.set(r.token);
    setUser(r.user);
  }, []);
  const signOut = useCallback(() => { tokenStore.clear(); setUser(null); }, []);

  const value = useMemo(() => ({ user, loading, signIn, signOut }), [user, loading, signIn, signOut]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export function useAuth() {
  const c = useContext(Ctx);
  if (!c) throw new Error("useAuth outside provider");
  return c;
}
