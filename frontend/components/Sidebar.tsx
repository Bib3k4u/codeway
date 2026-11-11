"use client";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useState } from "react";
import FloatingTip, { type Tip } from "./FloatingTip";
import { Check, LayoutGrid, LogOut, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import { api, type ConceptRow } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { conceptGlyph } from "./ConceptIcon";

/** Brand "C" emblem (no speed lines), swapped for light/dark themes. */
export function LogoMark({ size = 28 }: { size?: number }) {
  return (
    <span className="inline-grid shrink-0" style={{ width: size, height: size }}>
      <Image src="/brand/favicon-light-512.png" alt="Codeway" width={size} height={size} className="col-start-1 row-start-1 dark:hidden" priority />
      <Image src="/brand/favicon-dark-512.png" alt="Codeway" width={size} height={size} className="col-start-1 row-start-1 hidden dark:block" priority />
    </span>
  );
}

/** Full Codeway wordmark from the brand files, swapped for light/dark themes. */
export function Logo({ height = 30 }: { height?: number }) {
  return (
    <span className="inline-grid shrink-0" style={{ height }}>
      <Image src="/brand/logo-light.png" alt="Codeway" width={2010} height={404} style={{ height, width: "auto" }} className="col-start-1 row-start-1 dark:hidden" priority />
      <Image src="/brand/logo-dark.png" alt="Codeway" width={1247} height={245} style={{ height, width: "auto" }} className="col-start-1 row-start-1 hidden dark:block" priority />
    </span>
  );
}

export default function Sidebar({ collapsed, onToggle, onNavigate }: { collapsed: boolean; onToggle?: () => void; onNavigate?: () => void }) {
  const pathname = usePathname();
  const router = useRouter();
  const { user, signOut } = useAuth();
  const [concepts, setConcepts] = useState<ConceptRow[]>([]);
  const [tipState, setTip] = useState<Tip | null>(null);
  const showTip = (e: React.SyntheticEvent) => {
    const el = (e.target as HTMLElement).closest<HTMLElement>("[data-tip]");
    if (!collapsed || !el?.dataset.tip) { setTip(null); return; }
    const r = el.getBoundingClientRect();
    setTip({ place: "right", x: r.right, leftX: r.left, y: r.top + r.height / 2, text: el.dataset.tip });
  };
  useEffect(() => { api<ConceptRow[]>("/mastery?language=java").then(setConcepts).catch(() => {}); }, [pathname]);

  const tip = (t: string) => (collapsed ? { "data-tip": t } : {});
  const initial = (user?.displayName ?? "?").slice(0, 1).toUpperCase();

  return (
    <nav aria-label="Main" className={`flex h-full flex-col bg-side ${collapsed ? "rail" : ""}`}
      onMouseOver={showTip} onMouseLeave={() => setTip(null)} onFocus={showTip} onBlur={() => setTip(null)} onClick={() => setTip(null)}>
      <FloatingTip tip={collapsed ? tipState : null} />
      <div className={`flex h-14 shrink-0 items-center ${collapsed ? "justify-center" : "px-4"}`}>
        <Link href="/dashboard" onClick={onNavigate} aria-label="Dashboard">{collapsed ? <LogoMark size={30} /> : <Logo />}</Link>
      </div>

      <div className={`min-h-0 flex-1 overflow-y-auto pb-2 ${collapsed ? "px-0" : "px-2"}`}>
        <Link href="/dashboard" onClick={onNavigate} className="navitem" aria-current={pathname === "/dashboard" ? "page" : undefined} aria-label="Dashboard" {...tip("Dashboard")}>
          <LayoutGrid size={17} className="navicon" />{!collapsed && "Dashboard"}
        </Link>

        {collapsed ? <div className="mx-auto my-3 h-px w-8 bg-sep" /> : <p className="navgroup">Java</p>}
        <div className={collapsed ? "space-y-1" : "space-y-0.5"}>
          {concepts.map((c) => {
            const Icon = conceptGlyph(c.symbol);
            const active = pathname.startsWith(`/learn/${c.id}`);
            const status = c.mastered ? "mastered" : c.inProgress ? "in progress" : c.available ? "" : "needs AI setup";
            const body = (
              <>
                <Icon size={17} className="navicon" />
                {!collapsed && <span className="min-w-0 flex-1 truncate">{c.title}</span>}
                {c.mastered ? <Check size={14} className={`shrink-0 text-ok ${collapsed ? "absolute right-1 top-1 h-3 w-3" : ""}`} aria-hidden />
                  : c.inProgress ? <span aria-hidden className={`h-1.5 w-1.5 shrink-0 rounded-full bg-warn ${collapsed ? "absolute right-1.5 top-1.5" : ""}`} /> : null}
              </>
            );
            const label = status ? `${c.title} (${status})` : c.title;
            return c.available
              ? <Link key={c.id} href={`/learn/${c.id}`} onClick={onNavigate} className="navitem" aria-current={active ? "page" : undefined} aria-label={label} {...tip(c.title)}>{body}</Link>
              : <div key={c.id} className="navitem" aria-disabled="true" aria-label={label}>{body}</div>;
          })}
        </div>

      </div>

      <div className={`shrink-0 border-t border-sep ${collapsed ? "flex flex-col items-center gap-2 py-3" : "space-y-2 p-3"}`}>
        {onToggle && (
          <button className={`navitem ${collapsed ? "" : "!h-8"}`} onClick={onToggle} aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"} {...tip("Expand sidebar  ⌘\\")}>
            {collapsed ? <PanelLeftOpen size={17} className="navicon" /> : <><PanelLeftClose size={16} className="navicon" /><span className="flex-1">Collapse</span><span className="kbd">⌘\</span></>}
          </button>
        )}
        <div className={`flex items-center ${collapsed ? "flex-col gap-2" : "gap-2.5 pt-1"}`}>
          <span aria-hidden className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-[13px] font-bold" style={{ background: "var(--accent-fill)", color: "var(--accent-ink)" }}>{initial}</span>
          {!collapsed && (
            <div className="min-w-0 flex-1">
              <p className="truncate text-[13px] font-semibold leading-tight">{user?.displayName}</p>
              <p className="truncate text-xs text-label3">{user?.email}</p>
            </div>
          )}
          <button className="btn btn-sm btn-icon btn-ghost" aria-label="Sign out" {...tip("Sign out")} title={collapsed ? undefined : "Sign out"} onClick={() => { signOut(); router.replace("/"); }}><LogOut size={15} /></button>
        </div>
      </div>
    </nav>
  );
}
