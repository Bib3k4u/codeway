"use client";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, Check, RotateCcw, ChevronDown, ChevronLeft, FileCode2, Lightbulb, Loader2, Lock, Maximize2, Minimize2, PanelLeft, Play, Plus, Send, TestTube2, Trash2, X, XCircle, Trophy, Terminal } from "lucide-react";
import AppShell from "@/components/AppShell";
import CodeEditor from "@/components/CodeEditor";
import Markdown from "@/components/Markdown";
import Ring from "@/components/Ring";
import Splitter from "@/components/Splitter";
import Confetti from "@/components/Confetti";
import { useStats } from "@/lib/stats";
import { isLang, langInfo, type LangId } from "@/lib/lang";
import { useAuth } from "@/lib/auth";
import { useConcepts } from "@/lib/concepts";
import LangMenu from "@/components/LangMenu";
import ResetMenu from "@/components/ResetMenu";
import { usePref } from "@/lib/prefs";
import { api, ApiError, saveDraftOnExit, type Files, type Guide, type Hint, type HintQuota, type PatternReveal, type QuizState, type RevealResp, type RunResp, type StartResp, type SubmitResp } from "@/lib/api";
import ExpectedOutput from "@/components/practice/ExpectedOutput";
import TestPanel from "@/components/practice/TestPanel";
import ChallengeClock from "@/components/practice/ChallengeClock";
import GuidePanel from "@/components/practice/GuidePanel";
import { duration } from "@/lib/time";

type Busy = null | "main" | "tests" | "submit" | "hint";
/** Hints left this hour; `resetAt` (ms) is when a slot opens again, once none are left. */
type QuotaState = { limit: number; left: number; resetAt: number | null };
const quotaState = (q?: HintQuota): QuotaState | null => (q ? { limit: q.limit, left: q.left, resetAt: q.left <= 0 ? Date.now() + q.resetSeconds * 1000 : null } : null);
const hintNames = ["Nudge", "Concept", "Partial code", "Solution"];

/** Picks the language: ?lang= in the URL, else the one this learner last chose, else Java. */
export default function Practice() {
  const { concept } = useParams<{ concept: string }>();
  const search = useSearchParams();
  const { user, loading } = useAuth();
  const q = search.get("lang");
  const lang: LangId | null = isLang(q) ? q : loading ? null : isLang(user?.preferredLanguage) ? user.preferredLanguage : "java";
  if (!lang) return <AppShell><div className="skeleton mx-auto mt-10 h-8 w-64" /></AppShell>;
  // A fresh workspace per language: switching languages starts (or resumes) that language's challenge.
  const redo = Number(search.get("redo")) || null;
  return <Workspace key={`${lang}:${redo ?? ""}`} concept={concept} lang={lang} redo={redo} />;
}

/** @param redo a past challenge to redo as practice (graded, mastery unchanged) */
function Workspace({ concept, lang, redo }: { concept: string; lang: LangId; redo: number | null }) {
  const router = useRouter();
  const { setLanguage } = useAuth();
  const { refresh: refreshConcepts } = useConcepts();
  const [start, setStart] = useState<StartResp | null>(null);
  const [error, setError] = useState("");
  const [files, setFiles] = useState<Files>({});
  const [open, setOpen] = useState<string[]>([]);
  const [active, setActive] = useState("");
  const [busy, setBusy] = useState<Busy>(null);
  const [run, setRun] = useState<RunResp | null>(null);
  const [left, setLeft] = useState<"brief" | "steps" | "hints">("brief");
  const [bottom, setBottom] = useState<"console" | "tests">("console");
  const [hints, setHints] = useState<Hint[]>([]);
  const [hintQuota, setHintQuota] = useState<QuotaState | null>(null);
  const [hintNote, setHintNote] = useState("");
  const [now, setNow] = useState(() => Date.now());
  const [sheet, setSheet] = useState<null | "result" | "retry">(null);
  const [retry, setRetry] = useState<SubmitResp | null>(null);
  const [confirmReset, setConfirmReset] = useState<null | "file" | "all">(null);
  // The last test results stay in the Tests tab even after a plain Run; runSeq replays the result animation.
  const [testRun, setTestRun] = useState<RunResp | null>(null);
  const [runSeq, setRunSeq] = useState(0);
  const [result, setResult] = useState<SubmitResp | null>(null);
  const [newName, setNewName] = useState<string | null>(null);
  const [startKey, setStartKey] = useState(0);
  const [restored, setRestored] = useState(false);
  const { refresh: refreshStats } = useStats();
  const [party, setParty] = useState(false);
  const [lastMode, setLastMode] = useState<"main" | "tests">("main");
  // design-pattern practice: the diagnose question comes first and the editor stays read-only until it is answered
  const [quiz, setQuiz] = useState<QuizState | null>(null);

  // ---- telemetry ----
  const t0 = useRef(0);
  const sentFirst = useRef(false);
  const queue = useRef<{ attemptId: number; type: string; payload: object }[]>([]);
  const attemptId = start?.attemptId;

  const flush = useCallback(async () => {
    if (!queue.current.length) return;
    const events = queue.current.splice(0);
    try { await api("/telemetry", { events }); } catch { /* telemetry is best-effort */ }
  }, []);
  useEffect(() => {
    const id = setInterval(flush, 6000);
    return () => { clearInterval(id); flush(); };
  }, [flush]);

  // ---- workspace layout (sizes persist between visits) ----
  const wsRef = useRef<HTMLDivElement>(null);
  const rightRef = useRef<HTMLElement>(null);
  const [descW, setDescW] = usePref("descW", "380");
  const [descHidden, setDescHidden] = usePref("descHidden", "0");
  const [consoleH, setConsoleH] = usePref("consoleH", "230");
  const [consoleOpen, setConsoleOpen] = usePref("consoleOpen", "1");
  const [focus, setFocus] = useState(false);
  const descWidth = Math.min(680, Math.max(280, Number(descW) || 380));
  // In focus mode the brief starts hidden but can be shown; outside it, the saved preference applies.
  const [focusBrief, setFocusBrief] = useState(false);
  // the diagnose question lives in the left pane, so a guided challenge keeps it open until it is answered
  const briefRequired = !!start?.guide && !(quiz ?? start.guide.quiz).solved;
  const showDesc = briefRequired || (focus ? focusBrief : descHidden !== "1");
  const toggleBrief = () => (focus ? setFocusBrief((v) => !v) : setDescHidden(descHidden === "1" ? "0" : "1"));

  const toggleFocus = useCallback((on: boolean) => {
    setFocus(on);
    if (on) document.documentElement.requestFullscreen?.().catch(() => {});
    else if (document.fullscreenElement) document.exitFullscreen?.().catch(() => {});
  }, []);
  useEffect(() => {
    const onFs = () => { if (!document.fullscreenElement) setFocus(false); };
    document.addEventListener("fullscreenchange", onFs);
    return () => document.removeEventListener("fullscreenchange", onFs);
  }, []);
  // keyboard shortcuts call the latest exec (declared below) through a ref
  const execRef = useRef<(mode: "main" | "tests") => void>(() => {});
  useEffect(() => {
    const h = (e: KeyboardEvent) => {
      if (e.key === "Escape" && focus && !document.fullscreenElement) { toggleFocus(false); return; }
      if (!(e.metaKey || e.ctrlKey)) return;
      const k = e.key.toLowerCase();
      if (k === "b" && !e.shiftKey) { e.preventDefault(); toggleBrief(); }
      else if (k === "f" && e.shiftKey) { e.preventDefault(); toggleFocus(!focus); }
      else if (e.key === "Enter" && busy === null && attemptId) { e.preventDefault(); e.stopPropagation(); execRef.current(e.shiftKey ? "tests" : "main"); }
    };
    window.addEventListener("keydown", h, true);
    return () => window.removeEventListener("keydown", h, true);
  });
  const moveDesc = (x: number) => {
    const r = wsRef.current?.getBoundingClientRect(); if (!r) return;
    setDescW(String(Math.round(Math.min(Math.min(680, r.width * 0.6), Math.max(280, x - r.left - 12)))));
  };
  const moveConsole = (y: number) => {
    const r = rightRef.current?.getBoundingClientRect(); if (!r) return;
    setConsoleH(String(Math.round(Math.min(r.height * 0.7, Math.max(96, r.bottom - y - 17)))));
  };

  // ---- autosave: debounce edits, and flush when the tab is hidden or closed ----
  const saved = useRef<Files | null>(null);
  const filesRef = useRef<Files>({});
  useEffect(() => { filesRef.current = files; }, [files]);
  useEffect(() => {
    if (!attemptId || sheet === "result" || saved.current === files || Object.keys(files).length === 0) return;
    const t = setTimeout(() => {
      saved.current = files;
      api(`/attempts/${attemptId}/draft`, { files }, "PUT").catch(() => { saved.current = null; });
    }, 1200);
    return () => clearTimeout(t);
  }, [files, attemptId, sheet]);
  useEffect(() => {
    if (!attemptId) return;
    const flushNow = () => {
      if (document.visibilityState === "hidden" && saved.current !== filesRef.current && Object.keys(filesRef.current).length) {
        saved.current = filesRef.current; saveDraftOnExit(attemptId, filesRef.current);
      }
    };
    document.addEventListener("visibilitychange", flushNow);
    window.addEventListener("pagehide", flushNow);
    return () => { document.removeEventListener("visibilitychange", flushNow); window.removeEventListener("pagehide", flushNow); };
  }, [attemptId]);

  // ---- load challenge (or resume the unfinished one) ----
  const started = useRef(-1);
  useEffect(() => {
    if (started.current === startKey) return;
    started.current = startKey;
    const req = redo && startKey === 0 ? api<StartResp>(`/challenges/${redo}/redo`, {}) : api<StartResp>("/challenges/next", { language: lang, concept });
    req
      .then((r) => {
        const initial = r.draft ?? r.challenge.files;
        saved.current = initial;
        setStart(r); setQuiz(r.guide?.quiz ?? null); setLeft(r.guide ? "steps" : "brief"); setFiles(initial); setHintQuota(quotaState(r.hintQuota)); setHintNote("");
        const names = Object.keys(initial);
        const first = names.find((n) => /TODO/.test(initial[n])) ?? names[0];
        setOpen([first]); setActive(first);
        setHints(r.hints ?? []);
        if (r.lastRun) { setRun(r.lastRun); if (r.lastRun.tests?.length) setTestRun(r.lastRun); }
        if (r.resumed) { setRestored(true); setTimeout(() => setRestored(false), 6000); }
        t0.current = performance.now(); sentFirst.current = r.resumed;
      })
      .catch((e) => setError(e.message));
  }, [concept, startKey, lang, redo]);

  function onEdit(path: string, value: string) {
    if (!attemptId || value === files[path]) return;
    if (!sentFirst.current) {
      sentFirst.current = true;
      queue.current.push({ attemptId, type: "first_keystroke", payload: { ms: Math.round(performance.now() - t0.current) } });
    }
    if ((files[path]?.length ?? 0) - value.length > 60) queue.current.push({ attemptId, type: "churn", payload: { removed: files[path].length - value.length } });
    setFiles((f) => ({ ...f, [path]: value }));
  }

  async function exec(mode: "main" | "tests") {
    if (!attemptId) return;
    setBusy(mode); setLastMode(mode);
    if (mode === "tests") { setBottom("tests"); setConsoleOpen("1"); }
    try {
      const r = await api<RunResp>("/run", { attemptId, files, mode });
      setRun(r); setConsoleOpen("1"); refreshStats();
      if (mode === "tests") { setTestRun(r); setRunSeq((n) => n + 1); }
      setBottom(mode === "tests" && r.compiled ? "tests" : "console");
      if (!r.compiled && r.diagnostics[0]?.file && files[r.diagnostics[0].file] !== undefined) openFile(r.diagnostics[0].file);
    } catch (e) { setRun({ compiled: false, diagnostics: [], stdout: "", stderr: (e as Error).message, tests: [], timedOut: false, exitCode: 1, millis: 0, runs: 0 }); setBottom("console"); }
    finally { setBusy(null); }
  }

  useEffect(() => { execRef.current = exec; });

  /** finish=true ends the attempt with the current result ("Move on"). */
  async function submit(finish = false) {
    if (!attemptId) return;
    setBusy("submit"); setBottom("tests"); setConsoleOpen("1");
    try {
      await flush();
      const r = await api<SubmitResp>(`/attempts/${attemptId}/submit`, { files, finish });
      setRun(r); refreshStats(); refreshConcepts();
      if (r.compiled) { setTestRun(r); setRunSeq((n) => n + 1); }
      if (!r.final) {                       // not every test passed: show why, let them fix and resubmit
        setRetry(r); setSheet("retry");
        if (!r.compiled) setBottom("console");
        return;
      }
      setRetry(null); setResult(r); setSheet("result");
      if (r.allPassed) setParty(true);
    } catch (e) { setSheet(null); setRun({ compiled: false, diagnostics: [], stdout: "", stderr: (e as Error).message, tests: [], timedOut: false, exitCode: 1, millis: 0, runs: 0 }); setBottom("console"); }
    finally { setBusy(null); }
  }

  async function getHint() {
    if (!attemptId) return;
    setBusy("hint"); setLeft("hints");
    try {
      const failures = run?.tests.filter((t) => !t.passed).map((t) => `${t.name}: ${t.message}`).join("\n");
      const h = await api<Hint>(`/attempts/${attemptId}/hint`, { files, failures });
      setHints((x) => [...x, h]);
      setHintNote("");
      if (h.quota) setHintQuota(quotaState(h.quota));
    } catch (e) {
      const msg = (e as Error).message;
      setHintNote(msg);                                  // shown in the Hints tab (the page-level error isn't visible here)
      if (e instanceof ApiError && e.status === 429) {   // hourly allowance used up: the message says how long until the next one
        const mins = /(\d+) min/.exec(msg);
        setHintQuota((q) => ({ limit: q?.limit ?? 5, left: 0, resetAt: Date.now() + (mins ? Number(mins[1]) : 60) * 60_000 }));
        setNow(Date.now());
      }
    }
    finally { setBusy(null); }
  }

  // ---- reset to the starting template ----
  const starter = start?.challenge.files ?? {};
  const fileHasTemplate = active in starter;
  const fileChanged = fileHasTemplate && files[active] !== starter[active];
  const anyChanged = Object.keys(files).length !== Object.keys(starter).length || Object.keys(starter).some((k) => files[k] !== starter[k]);
  function doReset(kind: "file" | "all") {
    if (kind === "file") {
      if (fileHasTemplate) setFiles((f) => ({ ...f, [active]: starter[active] }));
    } else {
      setFiles({ ...starter });
      const keep = open.filter((n) => n in starter);
      const nextOpen = keep.length ? keep : [Object.keys(starter)[0]];
      setOpen(nextOpen);
      if (!(active in starter)) setActive(nextOpen[0]);
      setRun(null);
    }
    setConfirmReset(null);
  }

  function openFile(name: string) { setOpen((o) => (o.includes(name) ? o : [...o, name])); setActive(name); }
  function closeTab(name: string) {
    setOpen((o) => { const n = o.filter((x) => x !== name); if (active === name) setActive(n[n.length - 1] ?? ""); return n; });
  }
  function addFile() {
    let n = (newName ?? "").trim();
    if (!n) { setNewName(null); return; }
    const info = langInfo(lang);
    if (!/\.[a-z]+$/.test(n)) n += info.ext;
    if (!info.file.test(n) || files[n] !== undefined) { setNewName(null); return; }
    setFiles((f) => ({ ...f, [n]: info.template(n.replace(/\.[a-z]+$/, "")) })); openFile(n); setNewName(null);
  }

  /** Reveal one hidden test (costs a little mastery): fills in its name and failure message in the results. */
  async function reveal(index: number) {
    if (!attemptId) return;
    const r = await api<RevealResp>(`/attempts/${attemptId}/reveal`, { index });
    const patch = (x: RunResp | null) => x && { ...x, tests: x.tests.map((t, i) => (i === index ? { ...t, name: r.name, message: r.message ?? t.message, revealed: true } : t)) };
    setTestRun(patch); setRun(patch); setRetry((x) => (x ? (patch(x) as SubmitResp) : x));
  }

  /** Restart the clock on this attempt (a fresh timed try at the same question). */
  async function answerQuiz(option: string): Promise<QuizState> {
    const r = await api<QuizState>(`/attempts/${attemptId}/quiz`, { option });
    setQuiz(r);
    return r;
  }

  async function resetClock() {
    if (!attemptId) return;
    const r = await api<{ startedAt: string; serverNow: string }>(`/attempts/${attemptId}/timer/reset`, {});
    setStart((s) => (s ? { ...s, startedAt: r.startedAt, serverNow: r.serverNow } : s));
  }

  /** Learners can delete files they created; starter files can only be reset. */
  function deleteFile(name: string) {
    if (name in (start?.challenge.files ?? {})) return;
    setFiles((f) => { const n = { ...f }; delete n[name]; return n; });
    closeTab(name);
  }

  /** Saves this language's draft, then opens the same concept in another language. */
  function switchLang(l: LangId) {
    if (l === lang) return;
    if (attemptId && Object.keys(files).length) saveDraftOnExit(attemptId, files);
    setLanguage(l);
    router.replace(`/learn/${concept}/practice?lang=${l}`);
  }

  const endParty = useCallback(() => setParty(false), []);
  const diagnostics = run?.diagnostics ?? [];
  const nextChallenge = () => { setParty(false); setSheet(null); setResult(null); setRun(null); setTestRun(null); setHints([]); setHintNote(""); setStart(null); setQuiz(null); setError(""); saved.current = null; setRestored(false); setStartKey((k) => k + 1); };
  const fileNames = useMemo(() => Object.keys(files).sort(), [files]);
  const passing = testRun?.tests.filter((t) => t.passed).length ?? 0;
  const [deleting, setDeleting] = useState<string | null>(null);
  const hintLevel = hints.length;
  // with no hints left, tick so the button comes back by itself when a slot opens
  useEffect(() => {
    if (!hintQuota || hintQuota.left > 0) return;
    const id = setInterval(() => setNow(Date.now()), 15_000);
    return () => clearInterval(id);
  }, [hintQuota]);
  const hintsBlocked = !!hintQuota && hintQuota.left <= 0 && (hintQuota.resetAt ?? 0) > now;
  const hintMins = hintQuota?.resetAt ? Math.max(1, Math.ceil((hintQuota.resetAt - now) / 60_000)) : 0;

  if (error && !start) {
    return (
      <AppShell>
        <div className="card mx-auto max-w-md p-8 text-center rise">
          <XCircle className="mx-auto mb-3 text-bad" size={36} />
          <h1 className="mb-2 text-xl font-semibold">Couldn&apos;t start a challenge</h1>
          <p className="mb-6 text-sm text-label2">{error}</p>
          <Link href="/dashboard" className="btn btn-primary">Back to dashboard</Link>
        </div>
      </AppShell>
    );
  }

  if (!start) {
    return (
      <AppShell>
        <div className="mx-auto mt-24 max-w-sm text-center rise">
          <Loader2 className="mx-auto mb-5 animate-spin text-accent" size={30} />
          <h1 className="mb-1 text-xl font-semibold tracking-tight">Preparing your challenge</h1>
          <p className="text-sm text-label2">Choosing one that matches your level. Generated challenges are tested before you see them, so this can take a moment.</p>
        </div>
      </AppShell>
    );
  }

  const ch = start.challenge;
  const guide: Guide | null = start.guide ? { ...start.guide, quiz: quiz ?? start.guide.quiz } : null;
  const locked = !!guide && !guide.quiz.solved;
  const crumb = (
    <div className="hidden items-center gap-2 text-sm text-label2 md:flex">
      <ChevronLeft size={14} />
      <Link href={`/learn/${concept}?lang=${lang}`} className="navlink hover:text-label">Lesson</Link>
      <span className="text-label3">/</span>
      <span className="font-medium text-label">{ch.title}</span>
      <span className="chip">Level {ch.difficulty}</span>
      <span className="chip chip-accent">{guide ? "Refactor lab" : ch.scaffolding === "HEAVY" ? "Guided" : ch.scaffolding === "PARTIAL" ? "Skeleton" : "From scratch"}</span>
      {start.practice && <span className="chip" title="Redo from your history: graded, but mastery doesn't change">Practice</span>}
    </div>
  );

  return (
    <AppShell fluid left={crumb} quiet={sheet !== null || party}>
      <div ref={wsRef} className={focus ? "fixed inset-0 z-[55] flex bg-card max-lg:flex-col" : "flex h-full min-h-0 bg-card max-lg:h-auto max-lg:flex-col max-lg:overflow-auto"}>
        {/* ---------- Left: brief & hints ---------- */}
        {showDesc && (<>
        <section style={{ width: descWidth }} className="pane shrink-0 max-lg:!w-full max-lg:max-h-[50vh] max-lg:border-b max-lg:border-sep" aria-label="Challenge brief">
          <div className="pane-head justify-between">
            <div className="flex self-stretch" role="tablist" aria-label="Brief and hints">
              <button role="tab" className="ptab" aria-selected={left === "brief"} onClick={() => setLeft("brief")}>Brief</button>
              {guide && <button role="tab" className="ptab" aria-selected={left === "steps"} onClick={() => setLeft("steps")}>Steps{!guide.quiz.solved && <span className="h-1.5 w-1.5 rounded-full bg-accentfill" aria-label="Question waiting" />}</button>}
              <button role="tab" className="ptab" aria-selected={left === "hints"} onClick={() => setLeft("hints")}>Hints{hintLevel > 0 && <span className="chip !h-[18px] !px-1.5 !text-[10px]">{hintLevel}</span>}</button>
            </div>
            <button className="btn btn-secondary btn-sm" onClick={getHint} disabled={busy !== null || hintLevel >= 4 || hintsBlocked}
              title={hintsBlocked ? `You've used your ${hintQuota?.limit ?? 5} hints for this hour` : hintLevel >= 3 ? "Shows the full solution and lowers mastery" : "Opt-in help. Costs a little mastery"}>
              {busy === "hint" ? <Loader2 size={14} className="animate-spin" /> : <Lightbulb size={14} />}
              {hintLevel >= 4 ? "No more hints" : hintsBlocked ? `Hints reset in ${hintMins} min` : hintLevel === 3 ? "Show solution" : "Need a hint?"}
              {hintQuota && !hintsBlocked && hintLevel < 4 && <span className="chip !h-[18px] !px-1.5 !text-[10px] tabular-nums" title="Hints left this hour">{hintQuota.left}/{hintQuota.limit}</span>}
            </button>
          </div>
          {left === "hints" && hintNote && (
            <p role="status" className="mx-6 mt-4 rounded-xl px-3.5 py-2.5 text-[13px] leading-snug text-warn" style={{ background: "var(--orange-soft)" }}>{hintNote}</p>
          )}
          <div className="min-h-0 flex-1 overflow-auto px-6 py-5">
            {left === "steps" && guide ? (
              <GuidePanel guide={guide} names={ch.testNames} tests={testRun?.tests} onAnswer={answerQuiz} />
            ) : left === "brief" ? (
              <>
                <Markdown compact>{ch.descriptionMd}</Markdown>
                {ch.sampleOutput && (
                  <section className="mt-6">
                    <ExpectedOutput expected={ch.sampleOutput} />
                  </section>
                )}
                {!guide && ch.testNames.length > 0 && (
                  <section className="mt-6">
                    <h3 className="mb-2 text-[13px] font-semibold uppercase tracking-wider text-label3">Your code is checked for</h3>
                    <ul className="space-y-1.5">
                      {ch.testNames.map((n, i) => {
                        const t = testRun?.tests[i];
                        const hidden = n.startsWith("Hidden test") && !t?.revealed;
                        return (
                          <li key={i} className={`flex items-start gap-2.5 text-[14px] ${hidden ? "text-label3" : ""}`}>
                            {t ? (t.passed ? <Check size={16} className="mt-0.5 shrink-0 text-ok" /> : <XCircle size={16} className="mt-0.5 shrink-0 text-bad" />)
                              : hidden ? <Lock size={14} className="mt-1 shrink-0" /> : <span className="mt-1 h-3.5 w-3.5 shrink-0 rounded-full border-2 border-sep" />}
                            <span>{t?.revealed ? t.name : n}</span>
                          </li>
                        );
                      })}
                    </ul>
                  </section>
                )}
              </>
            ) : (
              hints.length === 0 ? (
                <div className="mt-10 text-center text-sm text-label2">
                  <Lightbulb className="mx-auto mb-3 text-warn" size={28} />
                  <p className="mb-1 font-medium text-label">Try first, hint second.</p>
                  <p>Hints escalate: nudge, concept, partial code, solution. Each one lowers what this attempt earns, and you get {hintQuota?.limit ?? 5} per hour.</p>
                </div>
              ) : (
                <ol className="space-y-4">
                  {hints.map((h) => (
                    <li key={h.level} className="pop rounded-xl border border-sep bg-card2 p-4">
                      <div className="mb-2 flex items-center justify-between">
                        <span className={`chip ${h.kind === "solution" ? "chip-warn" : "chip-accent"}`}>Level {h.level} · {hintNames[h.level - 1]}</span>
                        <span className="text-[11px] text-label3">{h.cost}</span>
                      </div>
                      <Markdown compact>{h.text}</Markdown>
                      {h.files && Object.entries(h.files).map(([n, c]) => (
                        <details key={n} className="mt-2"><summary className="cursor-pointer text-[13px] font-medium text-accent">{n}</summary>
                          <pre className="mt-2 overflow-auto rounded-xl bg-fill p-3 font-mono text-[12px] leading-relaxed">{c}</pre></details>
                      ))}
                    </li>
                  ))}
                </ol>
              )
            )}
          </div>
        </section>
        <div className="flex max-lg:hidden"><Splitter orientation="vertical" label="Resize brief" onMove={moveDesc} onNudge={(d) => setDescW(String(descWidth + d))} onReset={() => setDescW("380")} /></div>
        </>)}

        {/* ---------- Right: editor ---------- */}
        <section ref={rightRef} className="flex min-h-0 min-w-0 flex-1 flex-col max-lg:min-h-[640px]" aria-label="Code editor">
          <div className="pane flex-1">
            <div className="pane-head !pr-2.5">
              <button className="btn btn-icon btn-sm btn-ghost max-lg:hidden" onClick={toggleBrief}
                aria-label={showDesc ? "Hide brief" : "Show brief"} aria-pressed={showDesc} title={`${showDesc ? "Hide" : "Show"} brief (⌘B)`}>
                <PanelLeft size={16} />
              </button>
              <LangMenu value={lang} onChange={switchLang} />
              <span aria-hidden className="mx-1 h-5 w-px bg-sep" />
              <div className="flex min-w-0 flex-1 self-stretch overflow-x-auto" role="tablist" aria-label="Open files">
                {open.map((n) => (
                  <div key={n} role="tab" aria-selected={n === active} onClick={() => setActive(n)}
                    className="ptab group shrink-0">
                    <FileCode2 size={14} />{n}
                    {diagnostics.some((d) => d.file === n && d.severity === "error") && <span className="h-1.5 w-1.5 rounded-full bg-bad" />}
                    {open.length > 1 && <button aria-label={`Close ${n}`} onClick={(e) => { e.stopPropagation(); closeTab(n); }} className="opacity-0 transition-opacity group-hover:opacity-60 hover:!opacity-100"><X size={13} /></button>}
                  </div>
                ))}
              </div>
              <div className="flex shrink-0 items-center gap-2">
                {start.startedAt && (
                  <ChallengeClock key={start.startedAt} startedAt={start.startedAt} serverNow={start.serverNow} stopped={sheet === "result"}
                    attemptNumber={start.attemptNumber} bestSeconds={start.bestSeconds} longestSeconds={start.longestSeconds} onReset={resetClock} />
                )}
                <ResetMenu fileName={active} fileHasTemplate={fileHasTemplate} fileChanged={fileChanged} anyChanged={anyChanged}
                  onResetFile={() => setConfirmReset("file")} onResetAll={() => setConfirmReset("all")} disabled={busy !== null} />
                <button className="btn btn-secondary btn-sm" onClick={() => exec("main")} disabled={busy !== null} title="Run (⌘↵)">
                  {busy === "main" ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}Run
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => exec("tests")} disabled={busy !== null} title="Run tests (⌘⇧↵)">
                  {busy === "tests" ? <Loader2 size={14} className="animate-spin" /> : <TestTube2 size={14} />}Run tests
                </button>
                <button className="btn btn-primary btn-sm" onClick={() => submit()} disabled={busy !== null}>{busy === "submit" ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}Submit</button>
                <button className="btn btn-icon btn-sm btn-secondary" onClick={() => toggleFocus(!focus)} aria-label={focus ? "Exit focus mode" : "Focus mode"} aria-pressed={focus} title={focus ? "Exit focus mode (Esc)" : "Focus mode (⌘⇧F)"}>
                  {focus ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
                </button>
              </div>
            </div>
            <div className="flex min-h-0 flex-1">
              <nav className="hidden w-[176px] shrink-0 flex-col border-r border-sep bg-card2 p-2 sm:flex" aria-label="Project files">
                <div className="mb-1 flex items-center justify-between px-2 pt-1 text-[11px] font-semibold uppercase tracking-wider text-label3">
                  Files
                  <button aria-label="New file" title="New file" onClick={() => setNewName("")} className="rounded p-0.5 hover:bg-fill"><Plus size={14} /></button>
                </div>
                <div className="min-h-0 flex-1 space-y-0.5 overflow-auto">
                  {fileNames.map((n) => {
                    const mine = !(n in starter);
                    return (
                      <div key={n} className={`group flex w-full items-center rounded-lg text-[13px] ${n === active ? "bg-fill font-semibold text-label" : "text-label2 hover:bg-fill hover:text-label"}`}>
                        <button onClick={() => openFile(n)} className="flex min-w-0 flex-1 items-center gap-2 px-2 py-1.5 text-left" title={n}>
                          <FileCode2 size={14} className="shrink-0" /><span className="truncate">{n}</span>
                        </button>
                        {mine && (deleting === n ? (
                          <button onClick={() => { deleteFile(n); setDeleting(null); }} onBlur={() => setDeleting(null)} autoFocus
                            className="mr-1 shrink-0 rounded-md bg-badsoft px-1.5 py-0.5 text-[11px] font-semibold text-bad" aria-label={`Confirm delete ${n}`}>Delete</button>
                        ) : (
                          <button onClick={() => setDeleting(n)} aria-label={`Delete ${n}`} title="Delete file (only files you added)"
                            className="mr-1 shrink-0 rounded-md p-1 text-label3 opacity-0 transition-opacity hover:text-bad focus-visible:opacity-100 group-hover:opacity-100"><Trash2 size={13} /></button>
                        ))}
                      </div>
                    );
                  })}
                  {newName !== null && (
                    <input autoFocus value={newName} onChange={(e) => setNewName(e.target.value)} onBlur={addFile}
                      onKeyDown={(e) => { if (e.key === "Enter") addFile(); if (e.key === "Escape") setNewName(null); }}
                      placeholder={langInfo(lang).placeholder} className="w-full rounded-lg bg-fill px-2 py-1.5 text-[13px] outline-none ring-2 ring-accent/40" />
                  )}
                </div>
              </nav>
              <div className="flex min-w-0 flex-1 flex-col">
                {locked && (
                  <p role="status" className="flex items-center gap-2 border-b border-sep bg-card2 px-4 py-2 text-[13px] text-label2">
                    <Lock size={13} className="shrink-0" />Read the code first. It runs, but it has a design problem. Answer the question in the Steps tab to start editing.
                  </p>
                )}
                <div className="min-h-0 flex-1">
                  {active && files[active] !== undefined && <CodeEditor path={active} value={files[active]} onChange={(v) => onEdit(active, v)} diagnostics={diagnostics} files={files} readOnly={locked} />}
                </div>
              </div>
            </div>
          </div>

          {/* ---------- Console / tests ---------- */}
          {consoleOpen === "1" && <Splitter orientation="horizontal" label="Resize console" onMove={moveConsole} onNudge={(d) => setConsoleH(String((Number(consoleH) || 230) - d))} onReset={() => setConsoleH("230")} />}
          <div className={`pane shrink-0 ${consoleOpen === "1" ? "" : "border-t border-sep"}`} style={consoleOpen === "1" ? { height: Number(consoleH) || 230 } : undefined}>
            <div className="pane-head justify-between">
              <div className="flex self-stretch" role="tablist" aria-label="Output">
                <button role="tab" className="ptab" aria-selected={bottom === "console"} onClick={() => { setBottom("console"); setConsoleOpen("1"); }}><Terminal size={13} />Console</button>
                <button role="tab" className="ptab" aria-selected={bottom === "tests"} onClick={() => { setBottom("tests"); setConsoleOpen("1"); }}>{busy === "tests" || busy === "submit" ? <Loader2 size={13} className="animate-spin" /> : <TestTube2 size={13} />}Tests{busy !== "tests" && busy !== "submit" && testRun && testRun.tests.length > 0 && <span className={`text-[11px] ${passing === testRun.tests.length ? "text-ok" : "text-warn"}`}>{passing}/{testRun.tests.length}</span>}</button>
              </div>
              <div className="flex items-center gap-2">
                {run && busy !== "tests" && busy !== "submit" && <span className="text-xs text-label3">{run.compiled ? `Compiled · ${run.millis} ms` : "Compile failed"}</span>}
                <button className="btn btn-icon btn-sm btn-ghost" onClick={() => setConsoleOpen(consoleOpen === "1" ? "0" : "1")} aria-label={consoleOpen === "1" ? "Collapse console" : "Expand console"} aria-expanded={consoleOpen === "1"}>
                  <ChevronDown size={16} className={`transition-transform ${consoleOpen === "1" ? "" : "rotate-180"}`} />
                </button>
              </div>
            </div>
            {consoleOpen === "1" && <div className="min-h-0 flex-1 overflow-auto p-4 font-mono text-[12.5px] leading-relaxed">
              {bottom === "tests" ? (
                <TestPanel key={runSeq} run={testRun} names={ch.testNames} examples={ch.examples ?? []} language={lang}
                  running={busy === "tests" || busy === "submit"} submitting={busy === "submit"} onReveal={reveal} />
              ) : !run ? <p className="font-sans text-sm text-label3">Press Run to see your program&apos;s output, or Run tests to check your work.</p>
                : (
                  <div className="space-y-3">
                    {run.diagnostics.map((d, i) => (
                      <button key={i} onClick={() => files[d.file] !== undefined && openFile(d.file)} className="block w-full rounded-lg bg-badsoft px-3 py-2 text-left text-bad hover:brightness-95">
                        <span className="font-semibold">{d.file}:{d.line}</span> {d.message}
                      </button>
                    ))}
                    {run.stdout && (
                      <div>
                        <p className="mb-1 font-sans text-[11.5px] font-semibold uppercase tracking-wider text-label3">Your output</p>
                        <pre className="whitespace-pre-wrap">{run.stdout}</pre>
                      </div>
                    )}
                    {run.stderr && <pre className="whitespace-pre-wrap text-bad">{run.stderr}</pre>}
                    {run.compiled && !run.stdout && !run.stderr && <p className="font-sans text-label3">Program finished with no output.</p>}
                    {testRun && testRun.compiled && testRun.tests.length > 0 && (
                      <div className="flex flex-wrap items-center gap-1.5 font-sans">
                        <span className="mr-1 text-[11.5px] font-semibold uppercase tracking-wider text-label3">Tests</span>
                        {testRun.tests.map((t, i) => (
                          <button key={i} onClick={() => setBottom("tests")} title={t.passed ? "Passed" : "Failed: open the Tests tab"}
                            className={`inline-flex items-center gap-1.5 rounded-full px-2.5 py-1 text-[12px] font-medium ${t.passed ? "bg-oksoft text-ok" : "bg-badsoft text-bad"}`}>
                            {t.passed ? <span className="grid h-3.5 w-3.5 place-items-center rounded-[4px] bg-[var(--green)] text-white"><Check size={10} strokeWidth={3} /></span> : <XCircle size={13} />}
                            <span className="max-w-[220px] truncate">{t.hidden && !t.revealed ? `Hidden ${i + 1 - testRun.tests.filter((x) => !x.hidden).length}` : t.name}</span>
                          </button>
                        ))}
                      </div>
                    )}
                    {lastMode === "main" && run.compiled && ch.sampleOutput && run.exitCode === 0 && <ExpectedOutput expected={ch.sampleOutput} actual={run.stdout} />}
                  </div>
                )}
            </div>}
          </div>
        </section>
      </div>

      {restored && (
        <div className="pop pointer-events-none fixed bottom-6 left-1/2 z-50 -translate-x-1/2">
          <div className="sheet flex items-center gap-2.5 px-5 py-3 text-sm font-medium"><Check size={16} className="text-ok" />Welcome back. Your work was restored right where you left it.</div>
        </div>
      )}

      {/* ---------- Reset confirmation ---------- */}
      {confirmReset && (
        <Overlay onClose={() => setConfirmReset(null)}>
          <div className="pop w-full max-w-[420px] rounded-[22px] border border-sep bg-card p-7 shadow-[var(--shadow-lg)]" role="alertdialog" aria-labelledby="reset-title">
            <span className="mb-4 grid h-11 w-11 place-items-center rounded-full bg-badsoft text-bad"><RotateCcw size={20} /></span>
            <h2 id="reset-title" className="text-[22px] font-bold tracking-[-0.02em]">{confirmReset === "file" ? `Reset ${active}?` : "Reset all files?"}</h2>
            <p className="mt-2 text-[14.5px] leading-relaxed text-label2">
              {confirmReset === "file"
                ? "Your changes to this file will be replaced with the starting template."
                : "Every file goes back to the starting template, and any files you added are removed. Your last test results are cleared."}
              {" "}This can&apos;t be undone.
            </p>
            <div className="mt-6 flex justify-end gap-2.5">
              <button className="btn btn-secondary" onClick={() => setConfirmReset(null)} autoFocus>Cancel</button>
              <button className="btn !bg-[var(--red)] !text-white hover:brightness-110" onClick={() => doReset(confirmReset)}>{confirmReset === "file" ? "Reset file" : "Reset all"}</button>
            </div>
          </div>
        </Overlay>
      )}

      {/* ---------- Not every test passed: fix or move on ---------- */}
      {sheet === "retry" && retry && <FixSheet r={retry} busy={busy === "submit"} onFix={() => setSheet(null)} onMoveOn={() => submit(true)} onReveal={reveal} />}

      {/* ---------- Result sheet ---------- */}
      {party && sheet === "result" && <Confetti running onDone={endParty} />}
      {sheet === "result" && result && <ResultSheet r={result} concept={concept} lang={lang} onNext={nextChallenge} onHome={() => router.push("/dashboard")} />}
    </AppShell>
  );
}

function Overlay({ children, onClose }: { children: React.ReactNode; onClose: () => void }) {
  useEffect(() => {
    const h = (e: KeyboardEvent) => e.key === "Escape" && onClose();
    window.addEventListener("keydown", h);
    return () => window.removeEventListener("keydown", h);
  }, [onClose]);
  return (
    <div className="fade fixed inset-0 z-50 grid place-items-center overflow-auto bg-black/40 p-4 backdrop-blur-sm" onMouseDown={(e) => e.target === e.currentTarget && onClose()} role="dialog" aria-modal="true">
      {children}
    </div>
  );
}

/** One failed hidden test: what it checked (its name) and why it failed (the assertion message). */
function FailedTest({ name, message }: { name: string; message: string }) {
  return (
    <div className="rounded-xl border border-sep bg-card2 p-3.5">
      <p className="flex items-start gap-2 text-[14px] font-semibold"><XCircle size={16} className="mt-0.5 shrink-0 text-bad" />{name}</p>
      <p className="mt-2 rounded-lg bg-badsoft px-3 py-2 font-mono text-[12.5px] leading-relaxed text-bad">{message || "This test failed."}</p>
    </div>
  );
}

/** A failed hidden test: only that it failed, with an opt-in reveal that costs a little mastery. */
function HiddenFailure({ name, onReveal }: { name: string; onReveal: () => Promise<void> }) {
  const [busy, setBusy] = useState(false);
  return (
    <div className="flex items-center gap-3 rounded-xl border border-sep bg-card2 p-3.5">
      <Lock size={15} className="shrink-0 text-label3" />
      <p className="min-w-0 flex-1 text-[14px] font-semibold">{name} failed<span className="block text-[12px] font-normal text-label3">An edge case. Revealing it costs a little mastery.</span></p>
      <button className="btn btn-secondary btn-sm" disabled={busy} onClick={async () => { setBusy(true); try { await onReveal(); } finally { setBusy(false); } }}>
        {busy ? <Loader2 size={14} className="animate-spin" /> : "Reveal"}
      </button>
    </div>
  );
}

/** Submission that didn't pass every test: explain each failure, then fix and resubmit, or move on. */
function FixSheet({ r, busy, onFix, onMoveOn, onReveal }: { r: SubmitResp; busy: boolean; onFix: () => void; onMoveOn: () => void; onReveal: (i: number) => Promise<void> }) {
  const failing = r.tests.filter((t) => !t.passed);
  const pct = r.total ? (r.passed / r.total) * 100 : 0;
  return (
    <Overlay onClose={() => !busy && onFix()}>
      <div className="pop w-full max-w-[580px] overflow-hidden rounded-[24px] border border-sep bg-card shadow-[var(--shadow-lg)]">
        <div className="px-7 pb-5 pt-7">
          <div className="flex items-start gap-3.5">
            <span className="grid h-11 w-11 shrink-0 place-items-center rounded-full bg-badsoft text-bad"><XCircle size={22} /></span>
            <div>
              <h2 className="text-[26px] font-bold tracking-[-0.02em]">Not quite yet</h2>
              <p className="mt-0.5 text-[14px] text-label2">
                {r.compiled ? <><b className="text-label">{r.passed} of {r.total}</b> tests passed</> : "Your code didn't compile, so no tests could run"}
                <span className="text-label3"> · submission {r.failedSubmissions}</span>
              </p>
            </div>
          </div>
          {r.compiled && (
            <div className="mt-5 h-1.5 overflow-hidden rounded-full bg-fill" aria-hidden>
              <div className="h-full rounded-full bg-[var(--accent-fill)]" style={{ width: `${pct}%` }} />
            </div>
          )}
        </div>

        <div className="max-h-[42vh] space-y-2.5 overflow-auto border-t border-sep px-7 py-5">
          <p className="text-[13px] font-semibold text-label2">{r.compiled ? "What failed and why" : "Compile errors"}</p>
          {r.compiled
            ? failing.map((t, i) => t.hidden && !t.revealed
              ? <HiddenFailure key={i} name={t.name} onReveal={() => onReveal(t.index ?? -1)} />
              : <FailedTest key={i} name={t.name} message={t.message} />)
            : (r.diagnostics.length ? r.diagnostics.slice(0, 5) : [{ file: "", line: 0, severity: "error", message: r.stderr || "Compilation failed." }]).map((d, i) => (
              <p key={i} className="rounded-lg bg-badsoft px-3 py-2 font-mono text-[12.5px] leading-relaxed text-bad">{d.file && <b>{d.file}:{d.line} </b>}{d.message}</p>
            ))}
        </div>

        <div className="flex flex-wrap items-center justify-between gap-3 border-t border-sep bg-card2 px-7 py-4">
          <p className="max-w-[250px] text-[12.5px] leading-snug text-label3">Each extra submission lowers the mastery this challenge can earn. Finishing it still counts.</p>
          <div className="flex gap-2.5">
            <button className="btn btn-secondary" onClick={onMoveOn} disabled={busy}>{busy ? <Loader2 size={16} className="animate-spin" /> : "Move on"}</button>
            <button className="btn btn-primary" onClick={onFix} disabled={busy}>Fix my code<ArrowRight size={16} /></button>
          </div>
        </div>
      </div>
    </Overlay>
  );
}

/** After a guided challenge: the pattern the learner just built gets its name, with its roles mapped to their own classes. */
function RevealCard({ reveal }: { reveal: PatternReveal }) {
  return (
    <div className="pop mb-6 rounded-xl border border-[var(--accent-fill)] bg-card2 p-4">
      <p className="text-[11.5px] font-semibold uppercase tracking-wider text-label3">What you just built</p>
      <p className="mt-0.5 text-[22px] font-bold tracking-tight">{reveal.pattern} <span className="text-[13px] font-medium text-label3">· the fix for {reveal.principle}</span></p>
      {reveal.roles && reveal.roles.length > 0 && (
        <dl className="mt-3 grid grid-cols-[auto_1fr] gap-x-4 gap-y-1.5 text-[13.5px]">
          {reveal.roles.map((x) => (
            <div key={x.name} className="contents"><dt className="text-label3">{x.name}</dt><dd className="font-mono font-semibold">{x.is}</dd></div>
          ))}
        </dl>
      )}
      <p className="mt-3 text-[14px] leading-relaxed text-label2">{reveal.summary}</p>
    </div>
  );
}

function ResultSheet({ r, concept, lang, onNext, onHome }: { r: SubmitResp; concept: string; lang: LangId; onNext: () => void; onHome: () => void }) {
  const [shown, setShown] = useState(r.scoreBefore);
  useEffect(() => { const t = setTimeout(() => setShown(r.scoreAfter), 350); return () => clearTimeout(t); }, [r.scoreAfter]);
  const up = r.delta >= 0;
  return (
    <Overlay onClose={() => {}}>
      <div className="sheet pop max-h-[92vh] w-full max-w-lg overflow-auto p-7">
        <div className="mb-6 flex flex-col items-center text-center">
          {r.mastered ? <Trophy className="mb-3 text-ok" size={34} /> : r.allPassed ? <Check className="mb-3 rounded-full bg-oksoft p-1.5 text-ok" size={34} /> : <XCircle className="mb-3 text-warn" size={34} />}
          <h2 className="display text-[44px]">{r.mastered ? "Concept mastered" : r.allPassed ? "Nicely done" : "Not quite yet"}</h2>
          <p className="mt-1 text-sm text-label2">{r.passed} of {r.total} tests passed{r.attemptNumber ? ` · attempt #${r.attemptNumber}` : ""}</p>
        </div>
        <div className="mb-5 grid grid-cols-3 divide-x divide-[var(--sep)] rounded-xl border border-sep bg-card2 text-center">
          <div className="p-3"><p className="text-[11.5px] uppercase tracking-wider text-label3">This time</p><p className="mt-0.5 text-[17px] font-semibold tabular-nums">{duration(r.seconds ?? r.minutes * 60)}</p></div>
          <div className="p-3"><p className="text-[11.5px] uppercase tracking-wider text-label3">Best solved</p>
            <p className={`mt-0.5 text-[17px] font-semibold tabular-nums ${r.allPassed && r.bestSeconds != null && r.seconds === r.bestSeconds ? "text-ok" : ""}`}>{duration(r.bestSeconds)}</p></div>
          <div className="p-3"><p className="text-[11.5px] uppercase tracking-wider text-label3">Longest</p><p className="mt-0.5 text-[17px] font-semibold tabular-nums">{duration(r.longestSeconds)}</p></div>
        </div>
        {r.allPassed && r.bestSeconds != null && r.seconds === r.bestSeconds && (r.submittedAttempts ?? 1) > 1 && (
          <p className="pop -mt-2 mb-4 text-center text-[13px] font-semibold text-ok">New personal best on this question</p>
        )}
        <div className="mb-5 flex items-center justify-center gap-6 rounded-xl bg-card2 p-5 border border-sep">
          <Ring value={shown} size={92} stroke={9} mastered={r.mastered} />
          <div>
            <p className="text-[13px] text-label2">Mastery</p>
            <p className={`text-[28px] font-bold tabular-nums tracking-tight ${up ? "text-ok" : "text-bad"}`}>{up ? "+" : ""}{Math.round(r.delta * 100)}</p>
            <p className="text-xs text-label3">{r.practice ? "Practice: mastery unchanged" : `Streak ${r.streak}/${r.streakGoal}`}</p>
            {r.calibration && r.calibration !== "unrated" && <span className="chip mt-1.5 capitalize" title="Read from how you worked: first test run, number of runs, pace and help used">{r.calibration}</span>}
          </div>
        </div>
        <ul className="mb-6 space-y-2 text-[14px]">
          {r.feedback.map((f, i) => <li key={i} className="flex gap-2.5"><span className="mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full bg-accent" />{f}</li>)}
        </ul>
        {r.reveal && <RevealCard reveal={r.reveal} />}
        {r.tests.some((t) => !t.passed) && (
          <div className="mb-6 max-h-[30vh] space-y-2 overflow-auto">
            {r.tests.filter((t) => !t.passed).map((t, i) => <FailedTest key={i} name={t.name} message={t.hidden && !t.revealed ? "A hidden edge case failed." : t.message} />)}
          </div>
        )}
        <div className="flex gap-3">
          <button className="btn btn-secondary btn-lg flex-1" onClick={onHome}>Dashboard</button>
          {r.reveal && !r.mastered && <Link href={`/learn/${concept}?lang=${lang}`} className="btn btn-secondary btn-lg flex-1">Read the lesson</Link>}
          {r.mastered
            ? <Link href={`/learn/${concept}?lang=${lang}`} className="btn btn-primary btn-lg flex-1">Review lesson</Link>
            : <button className="btn btn-primary btn-lg flex-1" onClick={onNext}>Next challenge<ArrowRight size={18} /></button>}
        </div>
      </div>
    </Overlay>
  );
}
