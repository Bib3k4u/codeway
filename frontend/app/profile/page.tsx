"use client";
import Link from "next/link";
import { useEffect, useMemo, useState } from "react";
import {
  ArrowLeft, ArrowRight, Check, CheckCircle2, ChevronDown, Clock, Eye, Flame, Layers, ListTree, Loader2, Pencil,
  RotateCcw, Timer, Trophy, User, X, XCircle,
} from "lucide-react";
import AppShell from "@/components/AppShell";
import AreaChart from "@/components/AreaChart";
import { api, type ChallengeGroup, type HistoryItem, type HistoryPage, type HistorySummary } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { LANGS, langLabel } from "@/lib/lang";
import { useStats } from "@/lib/stats";
import { duration } from "@/lib/time";
import { useTracks } from "@/lib/tracks";

const PAGE = 20;
type Filters = { days: string; result: string; language: string; concept: string };
const NO_FILTERS: Filters = { days: "", result: "", language: "", concept: "" };
const qs = (f: Filters) => Object.entries(f).filter(([, v]) => v).map(([k, v]) => `&${k}=${encodeURIComponent(v)}`).join("");

/** Profile: name, totals, an activity chart, breakdowns by topic and language, and the full filterable history. */
export default function Profile() {
  const { user } = useAuth();
  const { stats } = useStats();
  const [summary, setSummary] = useState<{ days: number; data: HistorySummary } | null>(null);
  const [range, setRange] = useState(30);
  const [metric, setMetric] = useState<"minutes" | "solved" | "submitted">("minutes");
  const [filters, setFilters] = useState<Filters>(NO_FILTERS);
  const [view, setView] = useState<"timeline" | "challenge">("timeline");

  useEffect(() => {
    if (!user) return;
    api<HistorySummary>(`/history/summary?days=${range}&tz=${new Date().getTimezoneOffset()}`).then((data) => setSummary({ days: range, data })).catch(() => {});
  }, [user, range]);
  const sum = summary?.days === range ? summary.data : null;
  const since = stats?.joinedAt ? new Date(stats.joinedAt).toLocaleDateString(undefined, { month: "long", year: "numeric" }) : null;
  const points = useMemo(() => (sum ? sum.days.map((d) => ({ date: d.date, value: d[metric] })) : []), [sum, metric]);
  const periodTotal = points.reduce((a, p) => a + p.value, 0);

  return (
    <AppShell icon={User} crumb={<span className="font-medium text-label">Profile</span>}>
      <header className="glow rise mb-8 flex flex-wrap items-center gap-5">
        {user?.avatarUrl
          // eslint-disable-next-line @next/next/no-img-element -- GitHub avatar; no need for the image optimizer
          ? <img src={user.avatarUrl} alt="" width={76} height={76} referrerPolicy="no-referrer" className="h-[76px] w-[76px] rounded-full object-cover ring-2 ring-[var(--accent-fill)] ring-offset-2 ring-offset-[var(--bg)]" />
          : <span aria-hidden className="display grid h-[76px] w-[76px] place-items-center rounded-full text-[34px]" style={{ background: "var(--accent-fill)", color: "var(--accent-ink)" }}>{(user?.displayName ?? "?").slice(0, 1).toUpperCase()}</span>}
        <div className="min-w-0">
          <NameEditor />
          <p className="mt-0.5 text-[14px] text-label2">{user?.email}{since ? ` · Learning since ${since}` : ""}</p>
        </div>
      </header>

      {/* totals */}
      <div className="mb-8 grid grid-cols-2 gap-3 lg:grid-cols-3 xl:grid-cols-6">
        <Stat icon={<CheckCircle2 size={15} />} label="Solved" value={sum?.totals.solved} sub={sum ? `of ${sum.totals.attempts} attempts` : ""} />
        <Stat icon={<Layers size={15} />} label="Challenges" value={sum?.totals.challenges} sub="different questions" />
        <Stat icon={<Clock size={15} />} label="Time coding" value={sum ? duration(sum.totals.minutes * 60) : undefined} sub="on submitted attempts" />
        <Stat icon={<Timer size={15} />} label="Avg time" value={sum ? duration(sum.totals.avgMinutes * 60) : undefined} sub="per attempt" />
        <Stat icon={<Flame size={15} />} label="Streak" value={stats?.currentStreak} sub={stats ? `best ${stats.longestStreak} ${stats.longestStreak === 1 ? "day" : "days"}` : ""} />
        <Stat icon={<Trophy size={15} />} label="Mastered" value={stats?.mastered} sub={stats ? `of ${stats.conceptCount} concepts` : ""} />
      </div>

      {/* activity chart */}
      <section className="card mb-8 p-5">
        <div className="mb-4 flex flex-wrap items-center justify-between gap-3">
          <div>
            <h2 className="text-[16px] font-semibold tracking-tight">{metric === "minutes" ? "Time coding per day" : metric === "solved" ? "Challenges solved per day" : "Submissions per day"}</h2>
            <p className="text-[13px] text-label3">{metric === "minutes" ? duration(periodTotal * 60) : periodTotal} in the last {range} days</p>
          </div>
          <div className="flex flex-wrap gap-2">
            <Seg value={metric} onChange={(v) => setMetric(v as typeof metric)} options={[["minutes", "Time"], ["solved", "Solved"], ["submitted", "Submissions"]]} label="Chart metric" />
            <Seg value={String(range)} onChange={(v) => setRange(Number(v))} options={[["30", "30 days"], ["90", "90 days"]]} label="Chart range" />
          </div>
        </div>
        {sum ? <AreaChart points={points} unit={metric === "minutes" ? "coding" : metric === "solved" ? "solved" : "submissions"}
          format={(v) => (metric === "minutes" ? (v >= 60 ? `${Math.round(v / 60 * 10) / 10}h` : v >= 1 || v === 0 ? `${Math.round(v)}m` : `${Math.round(v * 60)}s`) : String(Math.round(v * 10) / 10))} />
          : <div className="skeleton h-[200px]" />}
      </section>

      {/* breakdowns */}
      <div className="mb-10 grid gap-6 lg:grid-cols-[minmax(0,1.6fr)_minmax(0,1fr)]">
        <TopicBreakdown sum={sum} onPick={(concept) => { setFilters({ ...NO_FILTERS, concept }); setView("timeline"); document.getElementById("history")?.scrollIntoView({ behavior: "smooth" }); }} />
        <section>
          <h2 className="section-title mb-3">By language</h2>
          <div className="card divide-y divide-[var(--sep)]">
            {!sum ? <div className="skeleton m-4 h-20" /> : sum.byLanguage.length === 0 ? <p className="p-5 text-sm text-label3">No submissions yet.</p>
              : sum.byLanguage.map((l) => (
                <button key={l.language} onClick={() => { setFilters({ ...NO_FILTERS, language: l.language }); document.getElementById("history")?.scrollIntoView({ behavior: "smooth" }); }}
                  className="flex w-full items-center gap-3 p-4 text-left transition-colors hover:bg-fill">
                  <span className="w-24 shrink-0 text-[14px] font-medium">{langLabel(l.language)}</span>
                  <Bar part={l.solved} whole={l.attempts} />
                  <span className="w-16 shrink-0 text-right text-[12.5px] tabular-nums text-label2">{l.solved}/{l.attempts}</span>
                </button>
              ))}
          </div>
        </section>
      </div>

      {/* history */}
      <section id="history" className="scroll-mt-20">
        <div className="mb-3 flex flex-wrap items-center justify-between gap-3">
          <h2 className="section-title">Submission history</h2>
          <Seg value={view} onChange={(v) => setView(v as typeof view)} options={[["timeline", "Timeline"], ["challenge", "By question"]]} label="History view" />
        </div>
        <FilterBar filters={filters} onChange={setFilters} topics={sum?.byConcept ?? []} />
        {view === "timeline"
          ? <Timeline key={qs(filters)} filters={filters} />
          : <ByChallenge key={qs(filters)} filters={filters} />}
      </section>

    </AppShell>
  );
}

// ---------------------------------------------------------------- header + small pieces

function NameEditor() {
  const { user, setDisplayName } = useAuth();
  const [editing, setEditing] = useState(false);
  const [name, setName] = useState("");
  const [busy, setBusy] = useState(false);
  const [error, setError] = useState("");
  const save = async () => {
    setBusy(true); setError("");
    try { await setDisplayName(name); setEditing(false); }
    catch (e) { setError(e instanceof Error ? e.message : "Couldn't save"); }
    finally { setBusy(false); }
  };
  if (!editing) {
    return (
      <div className="flex items-center gap-2">
        <h1 className="display text-[44px]">{user?.displayName}</h1>
        <button className="btn btn-icon btn-sm btn-ghost" aria-label="Edit your name" title="Edit what you're called" onClick={() => { setName(user?.displayName ?? ""); setEditing(true); }}><Pencil size={15} /></button>
      </div>
    );
  }
  return (
    <form className="flex flex-wrap items-center gap-2" onSubmit={(e) => { e.preventDefault(); save(); }}>
      <label htmlFor="display-name" className="sr-only">Your name</label>
      <input id="display-name" autoFocus maxLength={40} value={name} onChange={(e) => setName(e.target.value)} onKeyDown={(e) => e.key === "Escape" && setEditing(false)}
        className="input !h-12 !w-[min(360px,70vw)] !text-[20px] font-semibold" placeholder="What should we call you?" />
      <button className="btn btn-primary" disabled={busy || !name.trim()}>{busy ? <Loader2 size={16} className="animate-spin" /> : <><Check size={16} />Save</>}</button>
      <button type="button" className="btn btn-secondary" onClick={() => setEditing(false)}>Cancel</button>
      {error && <p role="alert" className="w-full text-[13px] text-bad">{error}</p>}
    </form>
  );
}

function Stat({ icon, label, value, sub }: { icon: React.ReactNode; label: string; value?: number | string; sub: string }) {
  return (
    <div className="card p-4">
      <p className="eyebrow mb-2 flex items-center gap-2"><span className="text-accent">{icon}</span>{label}</p>
      {value === undefined ? <div className="skeleton h-8 w-14" /> : <p className="display text-[30px] tabular-nums">{value}</p>}
      <p className="mt-0.5 truncate text-[12px] text-label3">{sub}</p>
    </div>
  );
}

function Seg({ value, onChange, options, label }: { value: string; onChange: (v: string) => void; options: [string, string][]; label: string }) {
  return (
    <div className="seg" role="tablist" aria-label={label}>
      {options.map(([v, l]) => <button key={v} role="tab" aria-selected={v === value} onClick={() => v !== value && onChange(v)}>{l}</button>)}
    </div>
  );
}

function Bar({ part, whole }: { part: number; whole: number }) {
  return (
    <div className="h-2 min-w-0 flex-1 overflow-hidden rounded-full bg-fill" role="presentation">
      <div className="h-full rounded-full" style={{ width: `${whole ? (part / whole) * 100 : 0}%`, background: "var(--accent-fill)" }} />
    </div>
  );
}

function TopicBreakdown({ sum, onPick }: { sum: HistorySummary | null; onPick: (concept: string) => void }) {
  const tracks = useTracks();
  const groups = tracks.map((t) => ({ t, rows: (sum?.byConcept ?? []).filter((c) => c.track === t.id) })).filter((g) => g.rows.length);
  return (
    <section>
      <h2 className="section-title mb-3">By topic</h2>
      <div className="card p-2">
        {!sum ? <div className="skeleton m-2 h-24" /> : sum.byConcept.length === 0 ? <p className="p-4 text-sm text-label3">Solve a challenge to see your topics here.</p>
          : groups.map(({ t, rows }) => (
            <div key={t.id} className="mb-1 last:mb-0">
              <p className="px-3 pb-1 pt-2.5 text-[11px] font-semibold uppercase tracking-[0.1em] text-label3">{t.title}</p>
              {rows.map((c) => (
                <button key={c.concept} onClick={() => onPick(c.concept)} className="flex w-full items-center gap-3 rounded-xl px-3 py-2.5 text-left transition-colors hover:bg-fill" title="Show these in the history">
                  <span className="w-40 shrink-0 truncate text-[14px] font-medium">{c.conceptTitle}</span>
                  <Bar part={c.solved} whole={c.attempts} />
                  <span className="w-28 shrink-0 text-right text-[12.5px] tabular-nums text-label2">{c.solved}/{c.attempts} · {duration(c.seconds)}</span>
                </button>
              ))}
            </div>
          ))}
      </div>
    </section>
  );
}

// ---------------------------------------------------------------- history

/** A dropdown pill with its own arrow (the native one sits flush against the edge) and no focus ring: the border just darkens. */
function FilterSelect({ label, value, onChange, children }: { label: string; value: string; onChange: (e: React.ChangeEvent<HTMLSelectElement>) => void; children: React.ReactNode }) {
  return (
    <span className="relative inline-flex">
      <select aria-label={label} value={value} onChange={onChange}
        className="no-ring h-9 cursor-pointer appearance-none rounded-full border border-sep bg-card pl-3.5 pr-9 text-[13px] text-label outline-none transition-colors hover:border-[var(--label-3)] focus:border-[var(--label-3)]">
        {children}
      </select>
      <ChevronDown size={14} aria-hidden className="pointer-events-none absolute right-3.5 top-1/2 -translate-y-1/2 text-label3" />
    </span>
  );
}

function FilterBar({ filters, onChange, topics }: { filters: Filters; onChange: (f: Filters) => void; topics: HistorySummary["byConcept"] }) {
  const set = (k: keyof Filters) => (e: React.ChangeEvent<HTMLSelectElement>) => { onChange({ ...filters, [k]: e.target.value }); e.target.blur(); };
  const active = Object.values(filters).some(Boolean);
  return (
    <div className="mb-3 flex flex-wrap items-center gap-2">
      <ListTree size={15} className="text-label3" aria-hidden />
      <FilterSelect label="Date range" value={filters.days} onChange={set("days")}>
        <option value="">Any time</option><option value="1">Last 24 hours</option><option value="7">Last 7 days</option><option value="30">Last 30 days</option><option value="90">Last 90 days</option>
      </FilterSelect>
      <FilterSelect label="Result" value={filters.result} onChange={set("result")}>
        <option value="">All results</option><option value="passed">Passed</option><option value="failed">Not passed</option><option value="practice">Practice redos</option>
      </FilterSelect>
      <FilterSelect label="Language" value={filters.language} onChange={set("language")}>
        <option value="">All languages</option>{LANGS.map((l) => <option key={l.id} value={l.id}>{l.label}</option>)}
      </FilterSelect>
      <FilterSelect label="Topic" value={filters.concept} onChange={set("concept")}>
        <option value="">All topics</option>{topics.map((t) => <option key={t.concept} value={t.concept}>{t.conceptTitle}</option>)}
      </FilterSelect>
      {active && <button className="btn btn-ghost btn-sm" onClick={() => onChange(NO_FILTERS)}><X size={14} />Clear</button>}
    </div>
  );
}

const dayLabel = (iso: string) => {
  const d = new Date(iso); const t = new Date();
  const same = (a: Date, b: Date) => a.toDateString() === b.toDateString();
  const y = new Date(); y.setDate(t.getDate() - 1);
  return same(d, t) ? "Today" : same(d, y) ? "Yesterday" : d.toLocaleDateString(undefined, { weekday: "long", month: "long", day: "numeric", year: d.getFullYear() === t.getFullYear() ? undefined : "numeric" });
};

/** Timeline: submissions grouped by day, newest first, 20 per page. */
function Timeline({ filters }: { filters: Filters }) {
  const [page, setPage] = useState(0);
  const [data, setData] = useState<{ page: number; res: HistoryPage } | null>(null);
  const [error, setError] = useState("");
  useEffect(() => {
    api<HistoryPage>(`/history?page=${page}&size=${PAGE}${qs(filters)}`).then((res) => setData({ page, res })).catch((e) => setError(e.message));
  }, [page, filters]);
  const res = data?.page === page ? data.res : null;
  const days = useMemo(() => {
    const m = new Map<string, HistoryItem[]>();
    for (const h of res?.items ?? []) { const k = new Date(h.at).toDateString(); m.set(k, [...(m.get(k) ?? []), h]); }
    return [...m.values()];
  }, [res]);
  return (
    <>
      {error && <p role="alert" className="mb-4 rounded-lg bg-badsoft px-4 py-3 text-sm text-bad">{error}</p>}
      {!res ? <div className="card">{Array.from({ length: 4 }).map((_, i) => <div key={i} className="skeleton m-4 h-10" />)}</div>
        : res.items.length === 0 ? <div className="card p-6 text-sm text-label3">No submissions match these filters.</div>
        : (
          <div className="space-y-5">
            <p className="text-[13px] text-label3">{res.total} {res.total === 1 ? "submission" : "submissions"}</p>
            {days.map((list) => (
              <div key={list[0].at}>
                <p className="eyebrow mb-2">{dayLabel(list[0].at)}</p>
                <div className="card divide-y divide-[var(--sep)]">{list.map((h) => <HistoryRow key={h.attemptId} h={h} />)}</div>
              </div>
            ))}
          </div>
        )}
      {res && res.pages > 1 && (
        <nav className="mt-4 flex items-center justify-between" aria-label="History pages">
          <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}><ArrowLeft size={14} />Newer</button>
          <span className="text-[13px] tabular-nums text-label3">Page {page + 1} of {res.pages}</span>
          <button className="btn btn-secondary btn-sm" disabled={page + 1 >= res.pages} onClick={() => setPage((p) => p + 1)}>Older<ArrowRight size={14} /></button>
        </nav>
      )}
    </>
  );
}

/** By question: one card per challenge with every attempt, the best time and how often it was solved. */
function ByChallenge({ filters }: { filters: Filters }) {
  const [groups, setGroups] = useState<ChallengeGroup[] | null>(null);
  const [open, setOpen] = useState<number | null>(null);
  useEffect(() => { api<ChallengeGroup[]>(`/history/challenges?x=1${qs(filters)}`).then(setGroups).catch(() => setGroups([])); }, [filters]);
  if (!groups) return <div className="card">{Array.from({ length: 3 }).map((_, i) => <div key={i} className="skeleton m-4 h-12" />)}</div>;
  if (!groups.length) return <div className="card p-6 text-sm text-label3">No submissions match these filters.</div>;
  return (
    <div className="space-y-2.5">
      <p className="text-[13px] text-label3">{groups.length} {groups.length === 1 ? "question" : "questions"}</p>
      {groups.map((g) => {
        const isOpen = open === g.challengeId;
        return (
          <div key={g.challengeId} className="card overflow-hidden">
            <button className="flex w-full flex-wrap items-center gap-3 p-4 text-left transition-colors hover:bg-fill sm:flex-nowrap" onClick={() => setOpen(isOpen ? null : g.challengeId)} aria-expanded={isOpen}>
              {g.solved > 0 ? <CheckCircle2 size={18} className="shrink-0 text-ok" aria-label="Solved" /> : <XCircle size={18} className="shrink-0 text-bad" aria-label="Not solved yet" />}
              <div className="min-w-0 flex-1">
                <p className="truncate text-[14.5px] font-medium">{g.title}</p>
                <p className="text-[12.5px] text-label3">{g.conceptTitle} · {langLabel(g.language)} · Level {g.difficulty}</p>
              </div>
              <span className="chip">{g.attempts.length} {g.attempts.length === 1 ? "attempt" : "attempts"}</span>
              <span className="chip chip-accent" title="Fastest solved time"><Trophy size={12} />{duration(g.bestSeconds)}</span>
              <ChevronDown size={16} className={`shrink-0 text-label3 transition-transform ${isOpen ? "rotate-180" : ""}`} />
            </button>
            {isOpen && (
              <div className="divide-y divide-[var(--sep)] border-t border-sep bg-card2">
                {g.attempts.map((h) => <HistoryRow key={h.attemptId} h={h} compact />)}
              </div>
            )}
          </div>
        );
      })}
    </div>
  );
}

function HistoryRow({ h, compact = false }: { h: HistoryItem; compact?: boolean }) {
  const ok = h.total > 0 && h.passed === h.total;
  const when = new Date(h.at).toLocaleString(undefined, compact ? { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" } : { hour: "numeric", minute: "2-digit" });
  return (
    <div className="flex flex-wrap items-center gap-3 px-4 py-3.5 sm:flex-nowrap">
      {ok ? <CheckCircle2 size={17} className="shrink-0 text-ok" aria-label="Passed" /> : <XCircle size={17} className="shrink-0 text-bad" aria-label="Not passed" />}
      <div className="min-w-0 flex-1">
        <p className="truncate text-[14px] font-medium">{compact ? `Attempt #${h.attemptNumber}` : h.title}</p>
        <p className="text-[12.5px] text-label3">
          {compact ? "" : `${h.conceptTitle} · ${langLabel(h.language)} · `}{h.passed}/{h.total} tests · <Timer size={11} className="-mt-0.5 inline" /> {duration(h.seconds)}
          {!compact && h.attemptNumber > 1 ? ` · attempt #${h.attemptNumber}` : ""} · {when}
        </p>
      </div>
      {h.practice ? <span className="chip">Practice</span>
        : <span className={`w-10 text-right text-xs font-semibold tabular-nums ${h.delta >= 0 ? "text-ok" : "text-bad"}`}>{h.delta >= 0 ? "+" : ""}{Math.round(h.delta * 100)}</span>}
      <div className="flex shrink-0 gap-1.5">
        <Link className="btn btn-secondary btn-sm" href={`/learn/${h.concept}/practice?lang=${h.language}&view=${h.attemptId}`} title="Open it in the challenge page with your code and results"><Eye size={14} />View</Link>
        <Link className="btn btn-secondary btn-sm" href={`/learn/${h.concept}/practice?lang=${h.language}&redo=${h.challengeId}`} title="Solve it again as practice (mastery unchanged)">
          <RotateCcw size={14} />Redo
        </Link>
      </div>
    </div>
  );
}
