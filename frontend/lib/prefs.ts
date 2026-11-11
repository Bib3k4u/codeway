"use client";
import { useCallback, useSyncExternalStore } from "react";

const listeners = new Set<() => void>();
const read = (key: string): string | null => { try { return localStorage.getItem("cw:" + key); } catch { return null; } };

/** A string preference persisted in localStorage, safe for SSR (renders the fallback first). */
export function usePref(key: string, fallback: string): [string, (v: string) => void] {
  const subscribe = useCallback((cb: () => void) => {
    listeners.add(cb);
    window.addEventListener("storage", cb);
    return () => { listeners.delete(cb); window.removeEventListener("storage", cb); };
  }, []);
  const value = useSyncExternalStore(subscribe, () => read(key) ?? fallback, () => fallback);
  const set = useCallback((v: string) => {
    try { localStorage.setItem("cw:" + key, v); } catch {}
    listeners.forEach((l) => l());
  }, [key]);
  return [value, set];
}
