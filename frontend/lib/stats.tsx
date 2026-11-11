"use client";
import { createContext, useCallback, useContext, useEffect, useMemo, useState, type ReactNode } from "react";
import { api } from "./api";
import { useAuth } from "./auth";

export interface RecentRow { title: string; concept: string; conceptTitle: string; passed: number; total: number; delta: number; at: string }
export interface Stats {
  today: string; days: Record<string, number>; currentStreak: number; longestStreak: number; activeToday: number;
  activeDays: number; yearTotal: number; solved: number; submissions: number; runs: number; hints: number;
  minutesCoding: number; mastered: number; conceptCount: number; avgScore: number; joinedAt: string | null; recent: RecentRow[];
}

const Ctx = createContext<{ stats: Stats | null; error: string; refresh: () => void }>({ stats: null, error: "", refresh: () => {} });

export function StatsProvider({ children }: { children: ReactNode }) {
  const { user } = useAuth();
  const language = user?.preferredLanguage ?? "java";
  const [stats, setStats] = useState<Stats | null>(null);
  const [error, setError] = useState("");
  const refresh = useCallback(() => {
    api<Stats>(`/stats?language=${language}&tz=${new Date().getTimezoneOffset()}`).then((s) => { setStats(s); setError(""); }).catch((e) => setError(e instanceof Error ? e.message : "Could not load your stats"));
  }, [language]);
  useEffect(() => { if (user) refresh(); }, [user, refresh]);
  const value = useMemo(() => ({ stats: user ? stats : null, error, refresh }), [stats, error, refresh, user]);
  return <Ctx.Provider value={value}>{children}</Ctx.Provider>;
}

export const useStats = () => useContext(Ctx);
