"use client";
import Link from "next/link";
import { useRouter } from "next/navigation";
import { useEffect, useState, type ReactNode } from "react";
import { CalendarCheck, Flame, Menu, Trophy, X } from "lucide-react";
import { useAuth } from "@/lib/auth";
import { usePref } from "@/lib/prefs";
import { useStats } from "@/lib/stats";
import Sidebar, { Logo } from "./Sidebar";
import ThemeToggle from "./ThemeToggle";

export { Logo };

/** Live status shown in every top bar: streak, today's activity, mastery. */
export function StatusChips({ compact = false }: { compact?: boolean }) {
  const { stats } = useStats();
  if (!stats) return null;
  const s = stats.currentStreak;
  return (
    <div className="flex items-center gap-1.5">
      <span className={`chip ${s > 0 ? "chip-accent" : ""} !h-7 !px-2.5`} title={`Longest streak: ${stats.longestStreak} days`}>
        <Flame size={14} className={s > 0 ? "" : "text-label3"} /><span className="tabular-nums">{s}</span>{!compact && <span className="font-normal">day streak</span>}
      </span>
      {!compact && (
        <>
          <span className="chip !h-7 !px-2.5 max-md:hidden" title="Actions today"><CalendarCheck size={14} /><span className="tabular-nums">{stats.activeToday}</span><span className="font-normal">today</span></span>
          <span className="chip !h-7 !px-2.5 max-sm:hidden" title="Concepts mastered"><Trophy size={14} /><span className="tabular-nums">{stats.mastered}/{stats.conceptCount}</span><span className="font-normal">mastered</span></span>
        </>
      )}
    </div>
  );
}

/**
 * Standard pages: collapsible sidebar + sticky toolbar (breadcrumb left, status + actions right) + content.
 * `fluid` (workspace): no sidebar, a slim header, content fills the window.
 */
export default function AppShell({ children, fluid = false, left, crumb, actions, wide = false }: {
  children: ReactNode; fluid?: boolean; left?: ReactNode; crumb?: ReactNode; actions?: ReactNode; wide?: boolean;
}) {
  const { user, loading } = useAuth();
  const router = useRouter();
  const [hidden, setHidden] = usePref("sidebarCollapsed", "0");
  const [drawer, setDrawer] = useState(false);
  const collapsed = hidden === "1";

  useEffect(() => { if (!loading && !user) router.replace("/"); }, [loading, user, router]);
  useEffect(() => {
    if (fluid) return;
    const h = (e: KeyboardEvent) => { if ((e.metaKey || e.ctrlKey) && e.key === "\\") { e.preventDefault(); setHidden(collapsed ? "0" : "1"); } };
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [fluid, collapsed, setHidden]);

  if (loading || !user) return <div className="grid h-screen place-items-center"><div className="skeleton h-3 w-32" /></div>;

  if (fluid) {
    return (
      <div className="flex h-screen flex-col">
        <header className="glass z-40 shrink-0">
          <div className="flex h-12 items-center justify-between gap-3 px-3">
            <div className="flex min-w-0 items-center gap-3">
              <Link href="/dashboard" aria-label="Dashboard"><Logo height={26} /></Link>
              {left}
            </div>
            <div className="flex items-center gap-2"><StatusChips compact /><ThemeToggle compact /></div>
          </div>
        </header>
        <main className="min-h-0 flex-1">{children}</main>
      </div>
    );
  }

  return (
    <div className="flex min-h-screen">
      <aside className={`sticky top-0 hidden h-screen shrink-0 border-r border-sep transition-[width] duration-200 ease-out lg:block ${collapsed ? "w-[64px]" : "w-[248px]"}`}>
        <Sidebar collapsed={collapsed} onToggle={() => setHidden(collapsed ? "0" : "1")} />
      </aside>
      {drawer && (
        <div className="fixed inset-0 z-50 lg:hidden" role="dialog" aria-modal="true" aria-label="Menu">
          <div className="fade absolute inset-0 bg-black/50" onClick={() => setDrawer(false)} />
          <div className="rise absolute inset-y-0 left-0 w-[272px] border-r border-sep shadow-2xl">
            <Sidebar collapsed={false} onNavigate={() => setDrawer(false)} />
            <button className="btn btn-icon btn-ghost absolute right-2 top-3" aria-label="Close menu" onClick={() => setDrawer(false)}><X size={16} /></button>
          </div>
        </div>
      )}

      {/* overflow-x-clip: decorative glows bleed past the edge; clip (not hidden) keeps the sticky header working */}
      <div className="min-w-0 flex-1 overflow-x-clip">
        <header className="glass sticky top-0 z-30">
          <div className="flex h-14 items-center justify-between gap-3 px-4 sm:px-6">
            <div className="flex min-w-0 items-center gap-2">
              <button className="btn btn-icon btn-ghost lg:hidden" aria-label="Open menu" onClick={() => setDrawer(true)}><Menu size={18} /></button>
              <div className="min-w-0 truncate text-sm text-label2">{crumb}</div>
            </div>
            <div className="flex items-center gap-3"><StatusChips />{actions}<ThemeToggle compact /></div>
          </div>
        </header>
        <main className={`mx-auto w-full px-4 pb-16 pt-8 sm:px-8 ${wide ? "max-w-[1440px]" : "max-w-6xl"}`}>{children}</main>
      </div>
    </div>
  );
}
