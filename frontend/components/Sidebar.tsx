"use client";
import Image from "next/image";
import Link from "next/link";
import { usePathname, useRouter } from "next/navigation";
import { useEffect, useLayoutEffect, useRef, useState } from "react";
import { createPortal } from "react-dom";
import FloatingTip, { type Tip } from "./FloatingTip";
import { BookOpen, Check, ChevronRight, LayoutGrid, UserRound, LogOut, PanelLeftClose, PanelLeftOpen } from "lucide-react";
import type { ConceptRow, Track } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { useConcepts } from "@/lib/concepts";
import { usePref } from "@/lib/prefs";
import { byTrack, useTracks } from "@/lib/tracks";
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

const SHORT: Record<string, string> = { oop: "OOP", solid: "SOLID", patterns: "Design patterns", lld: "Low-level design" };
const TRACK_SYMBOL: Record<string, string> = { oop: "cube", solid: "target", patterns: "blocks", lld: "compass" };

// The sidebar is re-created on every page (each page renders its own shell): keep its scroll position here.
let savedScroll = 0;

export default function Sidebar({ collapsed, onToggle, onNavigate }: { collapsed: boolean; onToggle?: () => void; onNavigate?: () => void }) {
  const pathname = usePathname();
  const router = useRouter();
  const { user, signOut } = useAuth();
  const { rows } = useConcepts();
  const tracks = useTracks();
  const groups = byTrack(tracks, rows ?? []).filter((g) => g.rows.length);
  const activeConcept = /^\/learn\/([^/?#]+)/.exec(pathname)?.[1];
  const activeTrack = rows?.find((r) => r.id === activeConcept)?.track;

  // Which tracks are expanded (persisted). Nothing saved yet: the track you're in, else OOP.
  const [openPref, setOpenPref] = usePref("navOpen", "");
  const open = new Set(openPref ? openPref.split(",").filter(Boolean) : [activeTrack ?? "oop"]);
  const toggle = (id: string) => {
    const next = new Set(open);
    if (next.has(id)) next.delete(id); else next.add(id);
    setOpenPref([...next].join(",") || ",");
  };
  // Arriving at a concept inside a closed track opens that track once; after that the learner can close it freely.
  const prefRef = useRef(openPref);
  useEffect(() => { prefRef.current = openPref; }, [openPref]);
  const arrived = useRef<string | undefined>(undefined);
  useEffect(() => {
    if (!activeTrack || !activeConcept || arrived.current === activeConcept) return;
    arrived.current = activeConcept;
    const cur = prefRef.current;
    if (cur && !cur.split(",").includes(activeTrack)) setOpenPref(`${cur},${activeTrack}`);
  }, [activeConcept, activeTrack, setOpenPref]);

  // rail: tooltips and the track flyout
  const [tipState, setTip] = useState<Tip | null>(null);
  const [fly, setFly] = useState<{ track: Track; rows: ConceptRow[]; top: number; left: number } | null>(null);
  // Tooltips: rail items (collapsed, no flyout open), and any name cut off with an ellipsis (data-trunc), anywhere.
  const showTip = (e: React.SyntheticEvent) => {
    const el = (e.target as HTMLElement).closest<HTMLElement>("[data-tip]");
    if (!el?.dataset.tip) { setTip(null); return; }
    if (el.hasAttribute("data-trunc")) {
      const t = el.querySelector<HTMLElement>(".truncate");
      if (!t || t.scrollWidth <= t.clientWidth) { setTip(null); return; }
    } else if (!collapsed || fly) { setTip(null); return; }
    const r = el.getBoundingClientRect();
    setTip({ place: "right", x: r.right, leftX: r.left, y: r.top + r.height / 2, text: el.dataset.tip });
  };
  const tip = (t: string) => (collapsed ? { "data-tip": t } : {});
  const initial = (user?.displayName ?? "?").slice(0, 1).toUpperCase();

  const scroller = useRef<HTMLDivElement>(null);
  useLayoutEffect(() => { if (scroller.current) scroller.current.scrollTop = savedScroll; }, [rows]);

  return (
    <nav aria-label="Main" className={`flex h-full flex-col bg-side ${collapsed ? "rail" : ""}`}
      onMouseOver={showTip} onMouseLeave={() => setTip(null)} onFocus={showTip} onBlur={() => setTip(null)} onClick={() => setTip(null)}>
      <FloatingTip tip={tipState} />
      <div className={`flex h-14 shrink-0 items-center ${collapsed ? "justify-center" : "px-4"}`}>
        <Link href="/dashboard" onClick={onNavigate} aria-label="Dashboard">{collapsed ? <LogoMark size={30} /> : <Logo />}</Link>
      </div>

      <div ref={scroller} onScroll={(e) => { savedScroll = e.currentTarget.scrollTop; }} className={`min-h-0 flex-1 overflow-y-auto pb-2 ${collapsed ? "px-0" : "px-2"}`}>
        <Link href="/dashboard" onClick={onNavigate} className="navitem" aria-current={pathname === "/dashboard" ? "page" : undefined} aria-label="Dashboard" {...tip("Dashboard")}>
          <LayoutGrid size={17} className="navicon" />{!collapsed && "Dashboard"}
        </Link>
        <Link href="/profile" onClick={onNavigate} className={`navitem ${collapsed ? "mt-1" : ""}`} aria-current={pathname === "/profile" ? "page" : undefined} aria-label="Profile and history" {...tip("Profile & history")}>
          <UserRound size={17} className="navicon" />{!collapsed && "Profile & history"}
        </Link>

        {collapsed ? (
          <>
            <div className="mx-auto my-3 h-px w-8 bg-sep" />
            <div className="space-y-1.5">
              {groups.map(({ track, rows: list }) => {
                const Icon = conceptGlyph(TRACK_SYMBOL[track.id] ?? "cube");
                const here = track.id === activeTrack;
                const busy = list.some((c) => c.inProgress && !c.mastered);
                return (
                  <button key={track.id} className="navitem" aria-current={here ? "page" : undefined} aria-haspopup="menu" aria-expanded={fly?.track.id === track.id}
                    aria-label={`${track.title}: ${list.length} concepts`} {...tip(track.title)}
                    onClick={(e) => {
                      if (fly?.track.id === track.id) { setFly(null); return; }
                      const r = e.currentTarget.getBoundingClientRect();
                      setTip(null); setFly({ track, rows: list, top: r.top, left: r.right + 10 });
                    }}>
                    <Icon size={18} className="navicon" />
                    <span aria-hidden className="absolute bottom-[5px] right-[5px] grid h-[15px] min-w-[15px] place-items-center rounded-full bg-card px-1 text-[9.5px] font-bold leading-none text-label2 ring-1 ring-[var(--sep)]">{track.order}</span>
                    {busy && <span aria-hidden className="absolute right-1.5 top-1.5 h-1.5 w-1.5 rounded-full bg-warn" />}
                  </button>
                );
              })}
            </div>
          </>
        ) : (
          <>
            <p className="navgroup">Learning path</p>
            <div className="space-y-0.5">
              {groups.map(({ track, rows: list }) => {
                const isOpen = open.has(track.id);
                const Icon = conceptGlyph(TRACK_SYMBOL[track.id] ?? "cube");
                const mastered = list.filter((c) => c.mastered).length;
                return (
                  <div key={track.id}>
                    <button className="navitem !h-[38px]" aria-expanded={isOpen} aria-controls={`nav-${track.id}`} onClick={() => toggle(track.id)}
                      aria-current={!isOpen && track.id === activeTrack ? "true" : undefined} data-tip={track.title} data-trunc="">
                      <Icon size={17} className={`navicon ${track.id === activeTrack ? "!text-accent" : ""}`} />
                      <span className="min-w-0 flex-1 truncate font-semibold text-label">{SHORT[track.id] ?? track.title}</span>
                      {track.practice
                        ? <span className="text-[11.5px] tabular-nums text-label3" aria-label={`${mastered} of ${list.length} mastered`}>{mastered}/{list.length}</span>
                        : <BookOpen size={13} className="shrink-0 text-label3" aria-label="Lessons only for now" />}
                      <ChevronRight size={14} className={`shrink-0 text-label3 transition-transform duration-200 ${isOpen ? "rotate-90" : ""}`} />
                    </button>
                    <div id={`nav-${track.id}`} className="grid transition-[grid-template-rows] duration-200 ease-out" style={{ gridTemplateRows: isOpen ? "1fr" : "0fr" }}>
                      <div className="min-h-0 overflow-hidden">
                        <ConceptList rows={list} pathname={pathname} onNavigate={onNavigate} hidden={!isOpen} />
                      </div>
                    </div>
                  </div>
                );
              })}
            </div>
          </>
        )}
      </div>

      {fly && collapsed && <Flyout {...fly} pathname={pathname} onClose={() => setFly(null)} onTip={showTip} onTipEnd={() => setTip(null)} />}

      <div className={`shrink-0 border-t border-sep ${collapsed ? "flex flex-col items-center gap-2 py-3" : "space-y-2 p-3"}`}>
        {onToggle && (
          <button className={`navitem ${collapsed ? "" : "!h-8"}`} onClick={onToggle} aria-label={collapsed ? "Expand sidebar" : "Collapse sidebar"} {...tip("Expand sidebar  ⌘\\")}>
            {collapsed ? <PanelLeftOpen size={17} className="navicon" /> : <><PanelLeftClose size={16} className="navicon" /><span className="flex-1">Collapse</span><span className="kbd">⌘\</span></>}
          </button>
        )}
        <div className={`flex items-center ${collapsed ? "flex-col gap-2" : "gap-2.5 pt-1"}`}>
          {user?.avatarUrl
            // eslint-disable-next-line @next/next/no-img-element -- tiny remote avatar; no need for the image optimizer
            ? <img src={user.avatarUrl} alt="" aria-hidden width={32} height={32} referrerPolicy="no-referrer" className="h-8 w-8 shrink-0 rounded-full object-cover" />
            : <span aria-hidden className="grid h-8 w-8 shrink-0 place-items-center rounded-full text-[13px] font-bold" style={{ background: "var(--accent-fill)", color: "var(--accent-ink)" }}>{initial}</span>}
          {!collapsed && (
            <Link href="/profile" onClick={onNavigate} className="min-w-0 flex-1 rounded-md hover:text-label" title="Profile and history">
              <p className="truncate text-[13px] font-semibold leading-tight">{user?.displayName}</p>
              <p className="truncate text-xs text-label3">{user?.email}</p>
            </Link>
          )}
          <button className="btn btn-sm btn-icon btn-ghost" aria-label="Sign out" {...tip("Sign out")} title={collapsed ? undefined : "Sign out"} onClick={() => { signOut(); router.replace("/"); }}><LogOut size={15} /></button>
        </div>
      </div>
    </nav>
  );
}

/** Concepts inside an open track: an indented list on a guide line, with sub-headings (Creational, ...) when present. */
function ConceptList({ rows, pathname, onNavigate, hidden = false }: { rows: ConceptRow[]; pathname: string; onNavigate?: () => void; hidden?: boolean }) {
  const groups = [...new Set(rows.map((r) => r.group ?? ""))];
  return (
    <div className="mb-1.5 ml-[18px] mt-0.5 border-l border-sep pl-2" inert={hidden}>
      {groups.map((g) => (
        <div key={g || "all"}>
          {g && <p className="px-2.5 pb-1 pt-2 text-[10.5px] font-semibold uppercase tracking-[0.1em] text-label3">{g}</p>}
          {rows.filter((r) => (r.group ?? "") === g).map((c) => {
            const active = pathname.startsWith(`/learn/${c.id}`);
            const status = c.mastered ? "mastered" : c.inProgress ? "in progress" : "";
            return (
              <Link key={c.id} href={`/learn/${c.id}`} onClick={onNavigate} className="navitem !h-[31px] !text-[13.5px]" aria-current={active ? "page" : undefined}
                aria-label={status ? `${c.title} (${status})` : c.title} data-tip={c.title} data-trunc="">
                <span className="min-w-0 flex-1 truncate">{c.title}</span>
                {c.mastered ? <Check size={13} className="shrink-0 text-ok" aria-hidden />
                  : c.inProgress ? <span aria-hidden className="h-1.5 w-1.5 shrink-0 rounded-full bg-warn" /> : null}
              </Link>
            );
          })}
        </div>
      ))}
    </div>
  );
}

/** Collapsed sidebar: a track's concepts in a panel beside the rail. Closes on outside click, Escape or navigation. */
function Flyout({ track, rows, top, left, pathname, onClose, onTip, onTipEnd }: {
  track: Track; rows: ConceptRow[]; top: number; left: number; pathname: string; onClose: () => void;
  onTip: (e: React.SyntheticEvent) => void; onTipEnd: () => void;
}) {
  const ref = useRef<HTMLDivElement>(null);
  const [y, setY] = useState(top - 8);
  useLayoutEffect(() => {
    const h = ref.current?.offsetHeight ?? 0;
    setY(Math.max(8, Math.min(top - 8, window.innerHeight - h - 8)));
    ref.current?.querySelector<HTMLElement>("a")?.focus();
  }, [top]);
  const first = useRef(pathname);
  useEffect(() => { if (pathname !== first.current) onClose(); }, [pathname, onClose]);
  useEffect(() => {
    const away = (e: Event) => {
      if (e instanceof KeyboardEvent) { if (e.key === "Escape") onClose(); return; }
      const t = e.target as HTMLElement;
      if (!ref.current?.contains(t) && !t.closest?.('[aria-haspopup="menu"]')) onClose();
    };
    document.addEventListener("mousedown", away);
    document.addEventListener("keydown", away);
    return () => { document.removeEventListener("mousedown", away); document.removeEventListener("keydown", away); };
  }, [onClose]);
  const mastered = rows.filter((c) => c.mastered).length;
  return createPortal(
    <div ref={ref} role="menu" aria-label={track.title} onMouseOver={onTip} onMouseLeave={onTipEnd} onClick={onTipEnd} className="pop fixed z-[200] w-[264px] rounded-2xl border border-sep bg-card p-2 shadow-[var(--shadow-lg)]" style={{ left, top: y }}>
      <div className="flex items-baseline justify-between gap-3 px-2.5 pb-1.5 pt-1.5">
        <p className="text-[13px] font-semibold"><span className="mr-1.5 tabular-nums text-label3">{track.order}</span>{track.title}</p>
        <span className="shrink-0 text-[11.5px] tabular-nums text-label3">{track.practice ? `${mastered}/${rows.length}` : "Lessons"}</span>
      </div>
      <div className="max-h-[60vh] overflow-y-auto">
        <ConceptList rows={rows} pathname={pathname} />
      </div>
    </div>,
    document.body,
  );
}
