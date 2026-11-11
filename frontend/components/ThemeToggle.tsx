"use client";
import { useSyncExternalStore } from "react";
import { useTheme } from "next-themes";
import { Monitor, Moon, Sun } from "lucide-react";

const opts = [
  { id: "light", icon: Sun, label: "Light" },
  { id: "system", icon: Monitor, label: "Match system" },
  { id: "dark", icon: Moon, label: "Dark" },
] as const;

export default function ThemeToggle({ compact = false }: { compact?: boolean }) {
  const { theme, setTheme, resolvedTheme } = useTheme();
  const mounted = useSyncExternalStore(() => () => {}, () => true, () => false);
  if (compact) {
    const dark = mounted && resolvedTheme === "dark";
    return (
      <button className="btn btn-secondary btn-icon" aria-label={dark ? "Switch to light mode" : "Switch to dark mode"} title={dark ? "Light mode" : "Dark mode"}
        onClick={() => setTheme(dark ? "light" : "dark")}>
        {dark ? <Sun size={15} /> : <Moon size={15} />}
      </button>
    );
  }
  return (
    <div className="seg w-full" role="group" aria-label="Appearance">
      {opts.map(({ id, icon: Icon, label }) => (
        <button key={id} className="flex-1 justify-center" aria-pressed={mounted && theme === id} aria-label={label} title={label} onClick={() => setTheme(id)}>
          <Icon size={14} />
        </button>
      ))}
    </div>
  );
}
