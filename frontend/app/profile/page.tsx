"use client";
import Link from "next/link";
import { useEffect, useState } from "react";
import { ArrowLeft, ArrowRight, CheckCircle2, Code2, Eye, FileCode2, Flame, Loader2, RotateCcw, Target, Trophy, User, X, XCircle } from "lucide-react";
import AppShell from "@/components/AppShell";
import Markdown from "@/components/Markdown";
import { api, type AttemptDetail, type HistoryItem, type HistoryPage } from "@/lib/api";
import { useAuth } from "@/lib/auth";
import { highlight } from "@/lib/highlight";
import { langLabel } from "@/lib/lang";
import { useStats } from "@/lib/stats";

const PAGE = 20;

/** Profile: who you are, your totals, and your full submission history with view and redo. */
export default function Profile() {
  const { user } = useAuth();
  const { stats } = useStats();
  const [page, setPage] = useState(0);
  const [data, setData] = useState<{ page: number; res: HistoryPage } | null>(null);
  const [error, setError] = useState("");
  const [viewing, setViewing] = useState<number | null>(null);

  useEffect(() => {
    if (!user) return;
    api<HistoryPage>(`/history?page=${page}&size=${PAGE}`).then((res) => setData({ page, res })).catch((e) => setError(e.message));
  }, [user, page]);
  const res = data?.page === page ? data.res : null;
  const since = stats?.joinedAt ? new Date(stats.joinedAt).toLocaleDateString(undefined, { month: "long", year: "numeric" }) : null;

  return (
    <AppShell icon={User} crumb={<span className="font-medium text-label">Profile</span>}>
      <header className="glow rise mb-8 flex flex-wrap items-center gap-5">
        {user?.avatarUrl
          // eslint-disable-next-line @next/next/no-img-element -- GitHub avatar; no need for the image optimizer
          ? <img src={user.avatarUrl} alt="" width={72} height={72} referrerPolicy="no-referrer" className="h-[72px] w-[72px] rounded-full object-cover ring-2 ring-[var(--accent-fill)] ring-offset-2 ring-offset-[var(--bg)]" />
          : <span aria-hidden className="display grid h-[72px] w-[72px] place-items-center rounded-full text-[32px]" style={{ background: "var(--accent-fill)", color: "var(--accent-ink)" }}>{(user?.displayName ?? "?").slice(0, 1).toUpperCase()}</span>}
        <div className="min-w-0">
          <h1 className="display text-[44px]">{user?.displayName}</h1>
          <p className="text-[14px] text-label2">{user?.email}{since ? ` · Learning since ${since}` : ""}</p>
        </div>
      </header>

      <div className="mb-10 grid gap-3 sm:grid-cols-2 lg:grid-cols-4">
        <Stat icon={<CheckCircle2 size={16} />} label="Solved" value={stats?.solved} sub={stats ? `of ${stats.submissions} submissions` : ""} />
        <Stat icon={<Flame size={16} />} label="Current streak" value={stats?.currentStreak} sub={stats ? `best ${stats.longestStreak} days` : ""} />
        <Stat icon={<Trophy size={16} />} label="Mastered" value={stats?.mastered} sub={stats ? `of ${stats.conceptCount} concepts` : ""} />
        <Stat icon={<Target size={16} />} label="Time coding" value={stats ? Math.round(stats.minutesCoding) : undefined} sub="minutes" />
      </div>

      <section id="history" className="scroll-mt-20">
        <div className="mb-3 flex flex-wrap items-baseline justify-between gap-3">
          <h2 className="section-title">Submission history</h2>
          {res && <span className="text-[13px] text-label3">{res.total} {res.total === 1 ? "submission" : "submissions"}</span>}
        </div>
        {error && <p role="alert" className="mb-4 rounded-lg bg-badsoft px-4 py-3 text-sm text-bad">{error}</p>}
        <div className="card divide-y divide-[var(--sep)]">
          {!res ? Array.from({ length: 5 }).map((_, i) => <div key={i} className="skeleton m-4 h-10" />)
            : res.items.length === 0 ? <p className="p-6 text-sm text-label3">Nothing submitted yet. Your submissions will show up here.</p>
            : res.items.map((h) => <HistoryRow key={h.attemptId} h={h} onView={() => setViewing(h.attemptId)} />)}
        </div>
        {res && res.pages > 1 && (
          <nav className="mt-4 flex items-center justify-between" aria-label="History pages">
            <button className="btn btn-secondary btn-sm" disabled={page === 0} onClick={() => setPage((p) => p - 1)}><ArrowLeft size={14} />Newer</button>
            <span className="text-[13px] tabular-nums text-label3">Page {page + 1} of {res.pages}</span>
            <button className="btn btn-secondary btn-sm" disabled={page + 1 >= res.pages} onClick={() => setPage((p) => p + 1)}>Older<ArrowRight size={14} /></button>
          </nav>
        )}
      </section>

      {viewing !== null && <SubmissionViewer id={viewing} onClose={() => setViewing(null)} />}
    </AppShell>
  );
}

function Stat({ icon, label, value, sub }: { icon: React.ReactNode; label: string; value?: number; sub: string }) {
  return (
    <div className="card p-5">
      <p className="eyebrow mb-2 flex items-center gap-2"><span className="text-accent">{icon}</span>{label}</p>
      {value === undefined ? <div className="skeleton h-9 w-16" /> : <p className="display text-[36px] tabular-nums">{value}</p>}
      <p className="mt-1 text-[12.5px] text-label3">{sub}</p>
    </div>
  );
}

function HistoryRow({ h, onView }: { h: HistoryItem; onView: () => void }) {
  const ok = h.total > 0 && h.passed === h.total;
  const when = new Date(h.at).toLocaleString(undefined, { month: "short", day: "numeric", hour: "numeric", minute: "2-digit" });
  return (
    <div className="flex flex-wrap items-center gap-3 p-4 sm:flex-nowrap">
      {ok ? <CheckCircle2 size={18} className="shrink-0 text-ok" aria-label="Passed" /> : <XCircle size={18} className="shrink-0 text-bad" aria-label="Not passed" />}
      <div className="min-w-0 flex-1">
        <p className="truncate text-[14.5px] font-medium">{h.title}</p>
        <p className="text-[12.5px] text-label3">{h.conceptTitle} · {langLabel(h.language)} · Level {h.difficulty} · {h.passed}/{h.total} tests · {when}</p>
      </div>
      {h.practice ? <span className="chip">Practice</span>
        : <span className={`w-10 text-right text-xs font-semibold tabular-nums ${h.delta >= 0 ? "text-ok" : "text-bad"}`}>{h.delta >= 0 ? "+" : ""}{Math.round(h.delta * 100)}</span>}
      <div className="flex shrink-0 gap-1.5">
        <button className="btn btn-secondary btn-sm" onClick={onView}><Eye size={14} />View</button>
        <Link className="btn btn-secondary btn-sm" href={`/learn/${h.concept}/practice?lang=${h.language}&redo=${h.challengeId}`} title="Solve it again as practice (mastery unchanged)">
          <RotateCcw size={14} />Redo
        </Link>
      </div>
    </div>
  );
}

/** A past submission: the brief and the code that was submitted, read-only. */
function SubmissionViewer({ id, onClose }: { id: number; onClose: () => void }) {
  const [d, setD] = useState<AttemptDetail | null>(null);
  const [file, setFile] = useState("");
  const [error, setError] = useState("");
  useEffect(() => {
    api<AttemptDetail>(`/attempts/${id}`).then((r) => { setD(r); setFile(Object.keys(r.files).sort()[0] ?? ""); }).catch((e) => setError(e.message));
  }, [id]);
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);
  const names = d ? Object.keys(d.files).sort() : [];
  return (
    <div className="fade fixed inset-0 z-50 grid place-items-center bg-black/45 p-4 backdrop-blur-sm" role="dialog" aria-modal="true" aria-label="Submission"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()}>
      <div className="pop flex max-h-[88vh] w-full max-w-[920px] flex-col overflow-hidden rounded-[22px] border border-sep bg-card shadow-[var(--shadow-lg)]">
        <div className="flex items-start justify-between gap-4 border-b border-sep px-6 py-4">
          <div className="min-w-0">
            <p className="truncate text-[18px] font-semibold tracking-tight">{d?.challenge.title ?? "Loading…"}</p>
            {d && <p className="text-[12.5px] text-label3">{langLabel(d.challenge.language)} · {d.passed}/{d.total} tests{d.practice ? " · Practice" : ""}{d.submittedAt ? ` · ${new Date(d.submittedAt).toLocaleString()}` : ""}</p>}
          </div>
          <div className="flex shrink-0 gap-2">
            {d && <Link className="btn btn-primary btn-sm" href={`/learn/${d.challenge.concept}/practice?lang=${d.challenge.language}&redo=${d.challenge.id}`}><RotateCcw size={14} />Redo</Link>}
            <button className="btn btn-icon btn-sm btn-ghost" onClick={onClose} aria-label="Close"><X size={16} /></button>
          </div>
        </div>
        {error ? <p className="p-6 text-bad">{error}</p> : !d ? <div className="grid place-items-center p-16"><Loader2 className="animate-spin text-accent" /></div> : (
          <div className="grid min-h-0 flex-1 md:grid-cols-[minmax(0,1fr)_minmax(0,1.3fr)]">
            <div className="min-h-0 overflow-auto border-sep p-5 max-md:border-b md:border-r"><Markdown compact>{d.challenge.descriptionMd}</Markdown></div>
            <div className="flex min-h-0 flex-col">
              <div className="flex shrink-0 gap-1 overflow-x-auto border-b border-sep px-3 py-2" role="tablist" aria-label="Files">
                {names.map((n) => (
                  <button key={n} role="tab" aria-selected={n === file} onClick={() => setFile(n)}
                    className={`flex shrink-0 items-center gap-1.5 rounded-lg px-2.5 py-1 text-[12.5px] ${n === file ? "bg-fill font-semibold" : "text-label2 hover:bg-fill"}`}>
                    <FileCode2 size={13} />{n}
                  </button>
                ))}
              </div>
              <pre className="min-h-0 flex-1 overflow-auto p-4 font-mono text-[12.5px] leading-relaxed">
                {names.length === 0 ? <span className="flex items-center gap-2 font-sans text-label3"><Code2 size={14} />No code was saved for this attempt.</span>
                  : <code>{highlight(d.files[file] ?? "", d.challenge.language)}</code>}
              </pre>
            </div>
          </div>
        )}
      </div>
    </div>
  );
}
