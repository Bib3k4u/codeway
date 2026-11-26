"use client";
import Link from "next/link";
import { useParams, useRouter, useSearchParams } from "next/navigation";
import { useCallback, useEffect, useMemo, useRef, useState } from "react";
import { ArrowRight, Check, Eye, Pencil, RotateCcw, ChevronDown, ChevronLeft, FileCode2, Lightbulb, Loader2, Lock, Maximize2, Minimize2, PanelLeft, Play, Plus, Send, TestTube2, TextCursorInput, Trash2, XCircle, Trophy, Terminal } from "lucide-react";
import AppShell from "@/components/AppShell";
import Markdown from "@/components/Markdown";
import CoffeeLoader from "@/components/CoffeeLoader";
import Ring from "@/components/Ring";
import Splitter from "@/components/Splitter";
import Confetti from "@/components/Confetti";
import { useStats } from "@/lib/stats";
import { isLang, langInfo, langLabel, type LangId } from "@/lib/lang";
import { useAuth } from "@/lib/auth";
import { useConcepts } from "@/lib/concepts";
import LangMenu from "@/components/LangMenu";
import ResetMenu from "@/components/ResetMenu";
import { usePref } from "@/lib/prefs";
import { api, ApiError, saveDraftOnExit, startChallenge, type AttemptDetail, type Diagnostic, type Files, type Guide, type Hint, type HintQuota, type PatternReveal, type QuizState, type RunResp, type StartResp, type SubmitResp } from "@/lib/api";
import ExpectedOutput from "@/components/practice/ExpectedOutput";
import TestPanel, { STEP_MS } from "@/components/practice/TestPanel";
import ChallengeClock from "@/components/practice/ChallengeClock";
import FloatingTip, { type Tip } from "@/components/FloatingTip";
import GuidePanel, { stepStatus } from "@/components/practice/GuidePanel";
import FileSearch from "@/components/practice/FileSearch";
import { resetEditorModels, type Highlight, type Reveal } from "@/components/CodeEditor";
import { replaceAll, replaceOne, type Match } from "@/lib/search";
import EditorGroups, { closeIn, moveTab, openIn, removeEverywhere, renameIn, splitOut, type Drag, type Group } from "@/components/practice/EditorGroups";
import { duration } from "@/lib/time";

type Busy = null | "main" | "tests" | "submit" | "hint";
/** Hints left this hour; `resetAt` (ms) is when a slot opens again, once none are left. */
type QuotaState = { limit: number; left: number; resetAt: number | null };
const quotaState = (q?: HintQuota): QuotaState | null => (q ? { limit: q.limit, left: q.left, resetAt: q.left <= 0 ? Date.now() + q.resetSeconds * 1000 : null } : null);
const hintNames = ["Nudge", "Concept", "Partial code", "Solution"];

/** Picks the language: ?lang= in the URL, else the one this learner last chose, else Java. */
/**
 * An editor bug once saved the provided main file's text over the learner's other files. A saved file that is a copy of a provided file,
 * although its starter is something else, is that damage: it goes back to its starter text.
 */
function healDraft(draft: Files | undefined, starter: Files, locked: string[]): Files {
  if (!draft) return starter;
  const out: Files = { ...draft };
  for (const l of locked) {
    const provided = starter[l]?.trim();
    if (!provided) continue;
    for (const [n, text] of Object.entries(draft)) {
      if (locked.includes(n) || starter[n] === undefined) continue;
      if (text.trim() === provided && starter[n].trim() !== provided) out[n] = starter[n];
    }
  }
  return out;
}

export default function Practice() {
  const { concept } = useParams<{ concept: string }>();
  const search = useSearchParams();
  const { user, loading } = useAuth();
  const q = search.get("lang");
  const lang: LangId | null = isLang(q) ? q : loading ? null : isLang(user?.preferredLanguage) ? user.preferredLanguage : "java";
  if (!lang) return <AppShell><div className="skeleton mx-auto mt-10 h-8 w-64" /></AppShell>;
  // A fresh workspace per language: switching languages starts (or resumes) that language's challenge.
  const redo = Number(search.get("redo")) || null;
  // ?view=<attempt>: a past attempt shown in this same workspace, with the learner's code and results, read-only
  const view = Number(search.get("view")) || null;
  return <Workspace key={`${lang}:${redo ?? ""}:${view ?? ""}`} concept={concept} lang={lang} redo={redo} view={view} />;
}

/** @param redo a past challenge to redo as practice (graded, mastery unchanged) */
function Workspace({ concept, lang, redo, view }: { concept: string; lang: LangId; redo: number | null; view: number | null }) {
  const router = useRouter();
  const { setLanguage } = useAuth();
  const { refresh: refreshConcepts } = useConcepts();
  const [start, setStart] = useState<StartResp | null>(null);
  const [error, setError] = useState("");
  const [files, setFiles] = useState<Files>({});
  // The editor can be split into up to three columns, each with its own open files; `drag` is the file being dragged right now.
  const [groups, setGroups] = useState<Group[]>([{ id: 1, open: [], active: "" }]);
  const [focusId, setFocusId] = useState(1);
  const [drag, setDrag] = useState<Drag | null>(null);
  // project search: the text marked in the editor, and the match the editor was last asked to show
  const [find, setFind] = useState<Highlight | null>(null);
  const [reveal, setReveal] = useState<Reveal | null>(null);
  const revealN = useRef(0);
  // errors from a compile-only check of what is typed (see the effect below); a Run's own diagnostics replace them
  const [live, setLive] = useState<Diagnostic[] | null>(null);
  const initialFiles = useRef<Files | null>(null);
  // the server has no live check (an older backend is running): say so instead of quietly showing no errors
  const [checkMissing, setCheckMissing] = useState(false);
  const focusGroup = groups.find((g) => g.id === focusId) ?? groups[0];
  const active = focusGroup.active;
  const [busy, setBusy] = useState<Busy>(null);
  const [run, setRun] = useState<RunResp | null>(null);
  const [left, setLeft] = useState<"brief" | "steps" | "hints">("brief");
  const [bottom, setBottom] = useState<"console" | "tests">("console");
  // text the program reads when it is run (stdin): lets a learner try their own values
  const [stdin, setStdin] = useState("");
  const [showInput, setShowInput] = useState(false);
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
  // the run whose results have already played out one by one; coming back to the Tests tab shows them at once
  const playedSeq = useRef(0);
  const [result, setResult] = useState<SubmitResp | null>(null);
  const [newName, setNewName] = useState<string | null>(null);
  const [startKey, setStartKey] = useState(0);
  const [detail, setDetail] = useState<{ passed: number; total: number; delta: number; at: string | null } | null>(null);
  const [restored, setRestored] = useState(false);
  const { refresh: refreshStats } = useStats();
  const [party, setParty] = useState(false);
  // after a submit the results play out one by one in the Tests tab; the result sheet (and confetti) wait for that to finish
  const [settling, setSettling] = useState(false);
  const [lastMode, setLastMode] = useState<"main" | "tests">("main");
  // design-pattern practice: the diagnose question comes first and the editor stays read-only until it is answered
  const [quiz, setQuiz] = useState<QuizState | null>(null);
  // asking for a hint first shows what it costs; the hint is only fetched after the learner agrees
  const [hintAsk, setHintAsk] = useState(false);
  // file list: width the learner can drag, a tooltip with the full file name, and renaming of files they created
  const [filesW, setFilesW] = usePref("filesW", "176");
  const filesWidth = Math.min(300, Math.max(128, Number(filesW) || 176));
  const [fileTip, setFileTip] = useState<Tip | null>(null);
  const [renaming, setRenaming] = useState<string | null>(null);
  // Enter and the blur that follows it must not both commit: each edit commits once
  const addingRef = useRef(false);
  const renamingRef = useRef<string | null>(null);
  const startRename = (n: string) => { renamingRef.current = n; setRenameVal(n); setRenaming(n); };
  const [renameVal, setRenameVal] = useState("");

  // ---- telemetry ----
  const t0 = useRef(0);
  const sentFirst = useRef(false);
  const queue = useRef<{ attemptId: number; type: string; payload: object }[]>([]);
  const attemptId = start?.attemptId;
  // Nothing can change once a past attempt is shown or the result has been given: the code stays as it was submitted.
  const frozen = view !== null || result !== null;

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
  // In focus mode the brief starts open (each time focus mode is entered) and can be closed; outside it, the saved preference applies.
  const [focusBrief, setFocusBrief] = useState(true);
  // the diagnose question lives in the left pane, so a guided challenge keeps it open until it is answered
  const briefRequired = !!start?.guide && !(quiz ?? start.guide.quiz).solved;
  const showDesc = briefRequired || (focus ? focusBrief : descHidden !== "1");
  const toggleBrief = () => (focus ? setFocusBrief((v) => !v) : setDescHidden(descHidden === "1" ? "0" : "1"));

  const toggleFocus = useCallback((on: boolean) => {
    setFocus(on);
    if (on) setFocusBrief(true);
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
  const filesRef2 = useRef<HTMLDivElement>(null);
  const moveFiles = (x: number) => {
    const r = filesRef2.current?.getBoundingClientRect(); if (!r) return;
    setFilesW(String(Math.round(Math.min(300, Math.max(128, x - r.left)))));
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
    if (!attemptId || frozen || saved.current === files || Object.keys(files).length === 0) return;
    const t = setTimeout(() => {
      saved.current = files;
      api(`/attempts/${attemptId}/draft`, { files }, "PUT").catch(() => { saved.current = null; });
    }, 1200);
    return () => clearTimeout(t);
  }, [files, attemptId, frozen]);
  useEffect(() => {
    if (!attemptId || frozen) return;
    const flushNow = () => {
      if (document.visibilityState === "hidden" && saved.current !== filesRef.current && Object.keys(filesRef.current).length) {
        saved.current = filesRef.current; saveDraftOnExit(attemptId, filesRef.current);
      }
    };
    document.addEventListener("visibilitychange", flushNow);
    window.addEventListener("pagehide", flushNow);
    return () => { document.removeEventListener("visibilitychange", flushNow); window.removeEventListener("pagehide", flushNow); };
  }, [attemptId, frozen]);

  // ---- live errors: shortly after typing stops, the server compiles the project and the errors show in the editor ----
  const checkSeq = useRef(0);
  useEffect(() => {
    if (!attemptId || frozen || files === initialFiles.current || Object.keys(files).length === 0) return;
    const snapshot = files;
    const t = setTimeout(async () => {
      const seq = ++checkSeq.current;
      try {
        const r = await api<{ compiled: boolean; diagnostics: Diagnostic[]; skipped: boolean }>("/check", { attemptId, files: snapshot });
        // only the newest answer for what is on screen now counts
        setCheckMissing(false);
        if (!r.skipped && seq === checkSeq.current && filesRef.current === snapshot) setLive(r.diagnostics);
      } catch (e) {
        // best-effort: the Run button still reports everything; only a missing endpoint is worth telling the learner
        if (e instanceof ApiError && (e.status === 404 || e.status === 405)) setCheckMissing(true);
      }
    }, 1400);
    return () => clearTimeout(t);
  }, [files, attemptId, frozen]);

  // ---- load challenge (or resume the unfinished one) ----
  const started = useRef(-1);
  useEffect(() => {
    if (started.current === startKey) return;
    started.current = startKey;
    const req = view
      ? api<AttemptDetail>(`/attempts/${view}`).then((d): StartResp => {
        setDetail({ passed: d.passed, total: d.total, delta: d.delta, at: d.submittedAt });
        return {
        attemptId: d.attemptId, challenge: d.challenge, resumed: true, draft: d.files, hints: d.hints, lastRun: d.lastRun, practice: d.practice,
        guide: d.guide, startedAt: d.startedAt, serverNow: new Date().toISOString(), attemptNumber: d.attemptNumber, bestSeconds: d.bestSeconds,
        longestSeconds: d.longestSeconds, submittedAttempts: d.submittedAttempts,
        mastery: { score: 0, streak: 0, mastered: false, streakGoal: 3, attempts: 0 },
        };
      })
      : redo && startKey === 0 ? api<StartResp>(`/challenges/${redo}/redo`, {}) : startChallenge(lang, concept, undefined, () => started.current !== startKey);
    req
      .then((r) => {
        resetEditorModels();
        const initial = healDraft(r.draft, r.challenge.files, r.challenge.lockedFiles ?? []);
        saved.current = initial; initialFiles.current = initial;
        setStart(r); setQuiz(r.guide?.quiz ?? null); setLeft(r.guide ? "steps" : "brief"); setFiles(initial); setHintQuota(quotaState(r.hintQuota)); setHintNote("");
        const names = Object.keys(initial);
        const first = names.find((n) => /TODO/.test(initial[n])) ?? names[0];
        setGroups([{ id: 1, open: [first], active: first }]); setFocusId(1);
        setHints(r.hints ?? []);
        if (r.lastRun) { setRun(r.lastRun); if (r.lastRun.tests?.length) { setTestRun(r.lastRun); if (view) setBottom("tests"); } }
        else if (r.resumed) { setRestored(true); setTimeout(() => setRestored(false), 6000); }
        t0.current = performance.now(); sentFirst.current = r.resumed;
      })
      .catch((e) => setError(e.message));
  }, [concept, startKey, lang, redo, view]);

  function onEdit(path: string, value: string) {
    // a late change event for a file that was just renamed or deleted must not bring it back
    if (!attemptId || files[path] === undefined || value === files[path]) return;
    if (!sentFirst.current) {
      sentFirst.current = true;
      queue.current.push({ attemptId, type: "first_keystroke", payload: { ms: Math.round(performance.now() - t0.current) } });
    }
    if ((files[path]?.length ?? 0) - value.length > 60) queue.current.push({ attemptId, type: "churn", payload: { removed: files[path].length - value.length } });
    setFiles((f) => (path in f ? { ...f, [path]: value } : f));   // the list in this closure can be a render behind
  }

  async function exec(mode: "main" | "tests") {
    if (!attemptId || frozen) return;
    setBusy(mode); setLastMode(mode);
    if (mode === "tests") { setBottom("tests"); setConsoleOpen("1"); }
    try {
      const r = await api<RunResp>("/run", { attemptId, files, mode, stdin: mode === "main" ? stdin : undefined });
      setRun(r); setLive(null); setConsoleOpen("1"); refreshStats();
      if (mode === "tests") { setTestRun(r); setRunSeq((n) => n + 1); }
      setBottom(mode === "tests" && r.compiled ? "tests" : "console");
      // jump to the first error in a file the learner can edit; the provided Main fails to build until their classes exist, so it is no place to send them
      const locked = start?.challenge.lockedFiles ?? [];
      const failing = r.compiled ? undefined : r.diagnostics.find((d) => d.file && files[d.file] !== undefined && !locked.includes(d.file));
      if (failing) openFile(failing.file);
    } catch (e) { setRun({ compiled: false, diagnostics: [], stdout: "", stderr: (e as Error).message, tests: [], timedOut: false, exitCode: 1, millis: 0, runs: 0 }); setBottom("console"); }
    finally { setBusy(null); }
  }

  useEffect(() => { execRef.current = exec; });

  /** finish=true ends the attempt with the current result ("Move on"). */
  async function submit(finish = false) {
    if (!attemptId || frozen) return;
    setBusy("submit"); setBottom("tests"); setConsoleOpen("1");
    try {
      await flush();
      const r = await api<SubmitResp>(`/attempts/${attemptId}/submit`, { files, finish });
      setRun(r); setLive(null); refreshStats(); refreshConcepts();
      if (r.compiled) { setTestRun(r); setRunSeq((n) => n + 1); }
      if (r.compiled && r.tests.length) {   // let every test show its result before the sheet covers the screen
        setBusy(null); setSettling(true);
        await new Promise((done) => setTimeout(done, r.tests.length * STEP_MS + 800));
        setSettling(false);
      }
      if (!r.final) {                       // not every test passed: show why, let them fix and resubmit
        setRetry(r); setSheet("retry");
        if (!r.compiled) setBottom("console");
        return;
      }
      setRetry(null); setResult(r); setSheet("result");
      if (r.allPassed) setParty(true);
    } catch (e) { setSheet(null); setSettling(false); setRun({ compiled: false, diagnostics: [], stdout: "", stderr: (e as Error).message, tests: [], timedOut: false, exitCode: 1, millis: 0, runs: 0 }); setBottom("console"); }
    finally { setBusy(null); }
  }

  async function getHint() {
    if (!attemptId || frozen) return;
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
      setGroups((gs) => {
        const kept = gs.map((g) => { const o = g.open.filter((n) => n in starter); return { ...g, open: o, active: o.includes(g.active) ? g.active : o[0] ?? "" }; }).filter((g) => g.open.length > 0);
        if (kept.length) return kept;
        const first = Object.keys(starter)[0];
        return [{ id: 1, open: [first], active: first }];
      });
      setRun(null);
    }
    setConfirmReset(null);
  }

  function openFile(name: string) { setGroups((gs) => openIn(gs, gs.some((g) => g.id === focusId) ? focusId : gs[0].id, name)); }
  /** A file dropped into a new column: that column takes focus. */
  function splitFile(d: Drag, at: number) {
    const r = splitOut(groups, d, at);
    setGroups(r.groups); setFocusId(r.id);
  }
  function closeColumn(gid: number) {
    setGroups((gs) => (gs.length > 1 ? gs.filter((g) => g.id !== gid) : gs));
    if (gid === focusId) setFocusId(groups.find((g) => g.id !== gid)?.id ?? 1);
  }
  function addFile() {
    if (!addingRef.current) return;
    addingRef.current = false;
    let n = (newName ?? "").trim();
    if (!n) { setNewName(null); return; }
    const info = langInfo(lang);
    if (!/\.[a-z]+$/.test(n)) n += info.ext;
    if (!info.file.test(n) || files[n] !== undefined) { setNewName(null); return; }
    setFiles((f) => ({ ...f, [n]: info.template(n.replace(/\.[a-z]+$/, "")) })); openFile(n); setNewName(null);
  }

  /** Rename a file the learner created. Keeps its place in the list, its open tab, and (Java) the class name inside it. */
  function renameFile(oldName: string, raw: string) {
    if (renamingRef.current !== oldName) return;
    renamingRef.current = null;
    setRenaming(null);
    let n = raw.trim();
    if (!n || n === oldName || oldName in starter) return;
    const info = langInfo(lang);
    if (!/\.[a-z]+$/.test(n)) n += info.ext;
    if (!info.file.test(n) || files[n] !== undefined || n in starter) return;
    const oldBase = oldName.replace(/\.[a-z]+$/, ""), newBase = n.replace(/\.[a-z]+$/, "");
    setFiles((f) => Object.fromEntries(Object.entries(f).map(([k, v]) => k === oldName
      ? [n, lang === "java" || lang === "csharp" ? v.replace(new RegExp(`\\b(class|interface|enum|record|struct)\\s+${oldBase}\\b`), `$1 ${newBase}`) : v]
      : [k, v])));
    setGroups((gs) => renameIn(gs, oldName, n));
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
    setGroups((gs) => removeEverywhere(gs, name));
  }

  /** Saves this language's draft, then opens the same concept in another language. */
  function switchLang(l: LangId) {
    if (l === lang) return;
    if (attemptId && Object.keys(files).length) saveDraftOnExit(attemptId, files);
    setLanguage(l);
    router.replace(`/learn/${concept}/practice?lang=${l}`);
  }

  const endParty = useCallback(() => setParty(false), []);
  const diagnostics = live ?? run?.diagnostics ?? [];
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
          <div className="flex justify-center gap-2.5">
            <button className="btn btn-primary" onClick={nextChallenge}><RotateCcw size={15} />Try again</button>
            <Link href="/dashboard" className="btn btn-secondary">Back to dashboard</Link>
          </div>
        </div>
      </AppShell>
    );
  }

  if (!start) {
    return (
      <AppShell>
        <CoffeeLoader />
      </AppShell>
    );
  }

  const ch = start.challenge;
  const providedFiles = ch.lockedFiles ?? [];
  const guide: Guide | null = start.guide ? { ...start.guide, quiz: quiz ?? start.guide.quiz } : null;
  const locked = !!guide && !guide.quiz.solved;
  const stepsDone = !guide || stepStatus(guide, ch.testNames, testRun?.tests).current >= guide.steps.length;   // a guided exercise's output is the goal only at the end
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
    <AppShell fluid left={crumb} quiet={sheet !== null || party || settling}>
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
            <button className="btn btn-secondary btn-sm" onClick={() => setHintAsk(true)} disabled={busy !== null || settling || frozen || hintLevel >= 4 || hintsBlocked}
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
              <GuidePanel guide={guide} names={ch.testNames} tests={testRun?.tests} onAnswer={answerQuiz} onCheck={() => exec("tests")} checking={busy === "tests"} />
            ) : left === "brief" ? (
              <>
                {ch.requires && ch.requires.length > 0 && (
                  <p className="mb-4 flex flex-wrap items-center gap-1.5 text-[12.5px] text-label2">
                    <span className="font-semibold uppercase tracking-wider text-label3">Builds on</span>
                    {ch.requires.map((r) => (
                      <Link key={r.id} href={`/learn/${r.id}?lang=${lang}`} target="_blank" rel="noopener" className="chip hover:brightness-95" title="Opens the lesson in a new tab, so your work here is untouched">{r.title}</Link>
                    ))}
                  </p>
                )}
                <Markdown compact>{ch.descriptionMd}</Markdown>
                {ch.sampleOutput && (
                  <section className="mt-6">
                    {guide && <p className="mb-2 text-[12.5px] leading-snug text-label3">This is what the finished program prints, after the last step. You get there one step at a time.</p>}
                    <ExpectedOutput expected={ch.sampleOutput} />
                  </section>
                )}
                {!guide && ch.testNames.length > 0 && (
                  <section className="mt-6">
                    <h3 className="mb-2 text-[13px] font-semibold uppercase tracking-wider text-label3">Your code is checked for</h3>
                    <ul className="space-y-1.5">
                      {ch.testNames.map((n, i) => {
                        const t = testRun?.tests[i];
                        return (
                          <li key={i} className="flex items-start gap-2.5 text-[14px]">
                            {t ? (t.passed ? <Check size={16} className="mt-0.5 shrink-0 text-ok" /> : <XCircle size={16} className="mt-0.5 shrink-0 text-bad" />)
                              : <span className="mt-1 h-3.5 w-3.5 shrink-0 rounded-full border-2 border-sep" />}
                            <span>{t?.name ?? n}</span>
                          </li>
                        );
                      })}
                      {(ch.hiddenTests ?? 0) > 0 && (
                        <li className="flex items-start gap-2.5 text-[14px] text-label3">
                          <span className="mt-1 h-3.5 w-3.5 shrink-0 rounded-full border-2 border-dashed border-sep" />
                          <span>{ch.hiddenTests === 1 ? "1 more check" : `${ch.hiddenTests} more checks`} on Submit</span>
                        </li>
                      )}
                    </ul>
                  </section>
                )}
              </>
            ) : (
              hints.length === 0 && busy !== "hint" ? (
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
                  {busy === "hint" && (
                    <li role="status" className="flex items-center gap-2.5 rounded-xl border border-dashed border-sep p-4 text-[13.5px] text-label2">
                      <Loader2 size={15} className="animate-spin text-accent" />Reading your code and writing a hint. A few seconds…
                    </li>
                  )}
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
              {view ? <span className="chip">{langLabel(lang)}</span> : <LangMenu value={lang} onChange={switchLang} />}
              <span aria-hidden className="mx-1 h-5 w-px bg-sep" />
              <FileSearch files={files} canReplace={!frozen && !locked} onHighlight={setFind}
                onJump={(m: Match) => { openFile(m.file); setReveal({ path: m.file, start: m.start, end: m.end, n: ++revealN.current }); }}
                onReplace={(m, text) => { if (!providedFiles.includes(m.file)) setFiles((f) => replaceOne(f, m, text)); }}
                onReplaceAll={(query, cs, text) => { const mine = Object.fromEntries(Object.entries(files).filter(([n]) => !providedFiles.includes(n))); const r = replaceAll(mine, query, cs, text); setFiles((f) => ({ ...f, ...r.files })); return r.count; }} />
              <div className="flex-1" />
              <div className="flex shrink-0 items-center gap-2">
                {start.startedAt && !view && (
                  <ChallengeClock key={start.startedAt} startedAt={start.startedAt} serverNow={start.serverNow} stopped={result !== null}
                    attemptNumber={start.attemptNumber} bestSeconds={start.bestSeconds} longestSeconds={start.longestSeconds} onReset={resetClock} />
                )}
                {view ? (
                  <Link className="btn btn-primary btn-sm" href={`/learn/${concept}/practice?lang=${lang}&redo=${ch.id}`} title="Solve it again as practice (mastery unchanged)"><RotateCcw size={14} />Redo as practice</Link>
                ) : frozen ? (<>
                  <button className="btn btn-secondary btn-sm" onClick={() => setSheet("result")}><Trophy size={14} />View result</button>
                  <button className="btn btn-primary btn-sm" onClick={nextChallenge}>Next challenge<ArrowRight size={14} /></button>
                </>) : (<>
                <ResetMenu fileName={active} fileHasTemplate={fileHasTemplate} fileChanged={fileChanged} anyChanged={anyChanged}
                  onResetFile={() => setConfirmReset("file")} onResetAll={() => setConfirmReset("all")} disabled={busy !== null || settling} />
                <button className="btn btn-secondary btn-sm" onClick={() => exec("main")} disabled={busy !== null || settling} title="Run (⌘↵)">
                  {busy === "main" ? <Loader2 size={14} className="animate-spin" /> : <Play size={14} />}Run
                </button>
                <button className="btn btn-secondary btn-sm" onClick={() => exec("tests")} disabled={busy !== null || settling} title="Run tests (⌘⇧↵)">
                  {busy === "tests" ? <Loader2 size={14} className="animate-spin" /> : <TestTube2 size={14} />}Run tests
                </button>
                <button className="btn btn-primary btn-sm" onClick={() => submit()} disabled={busy !== null || settling}>{busy === "submit" ? <Loader2 size={14} className="animate-spin" /> : <Send size={14} />}Submit</button>
                </>)}
                <button className="btn btn-icon btn-sm btn-secondary" onClick={() => toggleFocus(!focus)} aria-label={focus ? "Exit focus mode" : "Focus mode"} aria-pressed={focus} title={focus ? "Exit focus mode (Esc)" : "Focus mode (⌘⇧F)"}>
                  {focus ? <Minimize2 size={14} /> : <Maximize2 size={14} />}
                </button>
              </div>
            </div>
            <div ref={filesRef2} className="flex min-h-0 flex-1">
              <nav style={{ width: filesWidth }} className="hidden shrink-0 flex-col bg-card2 p-2 sm:flex" aria-label="Project files">
                <div className="mb-1 flex items-center justify-between px-2 pt-1 text-[11px] font-semibold uppercase tracking-wider text-label3">
                  Files
                  {!frozen && <button aria-label="New file" title="New file" onClick={() => { addingRef.current = true; setNewName(""); }} className="rounded p-0.5 hover:bg-fill"><Plus size={14} /></button>}
                </div>
                <div className="min-h-0 flex-1 space-y-0.5 overflow-auto">
                  {fileNames.map((n) => {
                    const mine = !(n in starter) && !frozen;
                    if (renaming === n) {
                      return (
                        <input key={n} autoFocus value={renameVal} onChange={(e) => setRenameVal(e.target.value)} onFocus={(e) => e.currentTarget.select()}
                          onBlur={() => renameFile(n, renameVal)}
                          onKeyDown={(e) => { if (e.key === "Enter") renameFile(n, renameVal); if (e.key === "Escape") { renamingRef.current = null; setRenaming(null); } }}
                          aria-label={`Rename ${n}`} className="w-full rounded-lg bg-fill px-2 py-1.5 text-[13px] outline-none ring-2 ring-accent/40" />
                      );
                    }
                    const showTip = (el: HTMLElement) => { const r = el.getBoundingClientRect(); setFileTip({ text: mine ? `${n} (yours: double-click to rename)` : providedFiles.includes(n) ? `${n} (provided for you: read-only)` : n, x: r.right, y: r.top + r.height / 2, place: "right", leftX: r.left }); };
                    return (
                      <div key={n} className={`group flex w-full items-center rounded-lg text-[13px] ${n === active ? "bg-fill font-semibold text-label" : "text-label2 hover:bg-fill hover:text-label"}`}>
                        <button onClick={() => openFile(n)} onDoubleClick={() => { if (mine) startRename(n); }}
                          draggable onDragStart={(e) => { setFileTip(null); e.dataTransfer.setData("text/plain", n); e.dataTransfer.effectAllowed = "move"; setDrag({ name: n, from: null }); }} onDragEnd={() => setDrag(null)}
                          onMouseEnter={(e) => showTip(e.currentTarget)} onMouseLeave={() => setFileTip(null)} onFocus={(e) => showTip(e.currentTarget)} onBlur={() => setFileTip(null)}
                          className="flex min-w-0 flex-1 items-center gap-2 px-2 py-1.5 text-left">
                          <FileCode2 size={14} className="shrink-0" /><span className="truncate">{n}</span>{providedFiles.includes(n) && <Lock size={11} className="shrink-0 text-label3" aria-label="Provided: read-only" />}
                        </button>
                        {mine && (deleting === n ? (
                          <button onClick={() => { deleteFile(n); setDeleting(null); }} onBlur={() => setDeleting(null)} autoFocus
                            className="mr-1 shrink-0 rounded-md bg-badsoft px-1.5 py-0.5 text-[11px] font-semibold text-bad" aria-label={`Confirm delete ${n}`}>Delete</button>
                        ) : (<>
                          <button onClick={() => { setFileTip(null); startRename(n); }} aria-label={`Rename ${n}`}
                            className="shrink-0 rounded-md p-1 text-label3 opacity-0 transition-opacity hover:text-label focus-visible:opacity-100 group-hover:opacity-100"><Pencil size={12} /></button>
                          <button onClick={() => setDeleting(n)} aria-label={`Delete ${n}`}
                            className="mr-1 shrink-0 rounded-md p-1 text-label3 opacity-0 transition-opacity hover:text-bad focus-visible:opacity-100 group-hover:opacity-100"><Trash2 size={13} /></button>
                        </>))}
                      </div>
                    );
                  })}
                  {newName !== null && (
                    <input autoFocus value={newName} onChange={(e) => setNewName(e.target.value)} onBlur={addFile}
                      onKeyDown={(e) => { if (e.key === "Enter") addFile(); if (e.key === "Escape") { addingRef.current = false; setNewName(null); } }}
                      placeholder={langInfo(lang).placeholder} className="w-full rounded-lg bg-fill px-2 py-1.5 text-[13px] outline-none ring-2 ring-accent/40" />
                  )}
                </div>
              </nav>
              <div className="hidden sm:flex"><Splitter orientation="vertical" label="Resize file list" onMove={moveFiles} onNudge={(d) => setFilesW(String(filesWidth + d))} onReset={() => setFilesW("176")} /></div>
              <div className="flex min-w-0 flex-1 flex-col">
                {locked && (
                  <p role="status" className="flex items-center gap-2 border-b border-sep bg-card2 px-4 py-2 text-[13px] text-label2">
                    <Lock size={13} className="shrink-0" />Read the code first. It runs, but it has a design problem. Answer the question in the Steps tab to start editing.
                  </p>
                )}
                {checkMissing && !frozen && (
                  <p role="status" className="flex items-center gap-2 border-b border-sep bg-[var(--orange-soft)] px-4 py-2 text-[13px] text-warn">
                    Live error checking isn&apos;t available on this server yet (it needs the latest backend, so restart it). Errors still show when you press Run.
                  </p>
                )}
                {view && detail && (
                  <p role="status" className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-sep bg-card2 px-4 py-2 text-[13px] text-label2">
                    <Eye size={13} className="shrink-0" />
                    <span>Attempt {start.attemptNumber ? `#${start.attemptNumber}` : ""}{detail.at ? ` · ${new Date(detail.at).toLocaleString()}` : ""}</span>
                    <b className={detail.passed === detail.total ? "text-ok" : "text-bad"}>{detail.passed}/{detail.total} tests passed</b>
                    {start.practice ? <span className="chip">Practice</span> : <span className={`font-semibold tabular-nums ${detail.delta >= 0 ? "text-ok" : "text-bad"}`}>{detail.delta >= 0 ? "+" : ""}{Math.round(detail.delta * 100)} mastery</span>}
                    <Link href="/profile#history" className="ml-auto font-medium text-accent hover:underline">Back to history</Link>
                  </p>
                )}
                {!view && result && (
                  <p role="status" className="flex flex-wrap items-center gap-x-3 gap-y-1 border-b border-sep bg-card2 px-4 py-2 text-[13px] text-label2">
                    {result.allPassed ? <Check size={13} className="shrink-0 text-ok" /> : <XCircle size={13} className="shrink-0 text-warn" />}
                    <span>Submitted: {result.passed} of {result.total} tests passed. Your code is kept as it was; look around as long as you like.</span>
                  </p>
                )}
                <EditorGroups groups={groups} focusId={focusGroup.id} files={files} diagnostics={diagnostics} readOnly={locked || frozen} lockedPaths={providedFiles} highlight={find} reveal={reveal}
                  drag={drag} setDrag={setDrag} onFocus={setFocusId}
                  onSelect={(gid, name) => { setFocusId(gid); setGroups((gs) => openIn(gs, gid, name)); }}
                  onClose={(gid, name) => setGroups((gs) => closeIn(gs, gid, name))}
                  onMove={(d, to, index) => { setFocusId(to); setGroups((gs) => moveTab(gs, d, to, index)); }}
                  onSplit={splitFile}
                  onSplitActive={(gid) => { const g = groups.find((x) => x.id === gid); if (g?.active) splitFile({ name: g.active, from: null }, groups.indexOf(g) + 1); }}
                  onCloseGroup={closeColumn}
                  onEdit={onEdit} />
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
                {!frozen && (
                  <button className={`btn btn-sm ${showInput ? "btn-secondary" : "btn-ghost"}`} aria-pressed={showInput} onClick={() => { setShowInput((v) => !v); setBottom("console"); setConsoleOpen("1"); }}
                    title="Give your program your own input. Run feeds this text to whatever it reads (for example a Scanner or input())">
                    <TextCursorInput size={14} />Custom input{stdin.trim() && <span aria-label="has input" className="h-1.5 w-1.5 rounded-full bg-[var(--accent-fill)]" />}
                  </button>
                )}
                {run && busy !== "tests" && busy !== "submit" && <span className="text-xs text-label3">{run.compiled ? `Compiled · ${run.millis} ms` : "Compile failed"}</span>}
                <button className="btn btn-icon btn-sm btn-ghost" onClick={() => setConsoleOpen(consoleOpen === "1" ? "0" : "1")} aria-label={consoleOpen === "1" ? "Collapse console" : "Expand console"} aria-expanded={consoleOpen === "1"}>
                  <ChevronDown size={16} className={`transition-transform ${consoleOpen === "1" ? "" : "rotate-180"}`} />
                </button>
              </div>
            </div>
            {consoleOpen === "1" && <div className="min-h-0 flex-1 overflow-auto p-4 font-mono text-[12.5px] leading-relaxed">
              {bottom === "tests" ? (
                <TestPanel key={runSeq} run={testRun} names={ch.testNames} hidden={ch.hiddenTests ?? 0} animate={playedSeq.current !== runSeq} onPlayed={() => { playedSeq.current = runSeq; }}
                  running={busy === "tests" || busy === "submit"} submitting={busy === "submit"} />
              ) : (<>
                {showInput && !frozen && (
                  <div className="mb-3 font-sans">
                    <label htmlFor="stdin" className="mb-1 flex items-center justify-between text-[11.5px] font-semibold uppercase tracking-wider text-label3">
                      Custom input
                      <span className="font-normal normal-case tracking-normal">Run gives this text to your program, one line per line you type</span>
                    </label>
                    <textarea id="stdin" value={stdin} onChange={(e) => setStdin(e.target.value.slice(0, 20000))} rows={3} spellCheck={false}
                      placeholder={"Type what your program reads, for example:\n1000\n450"}
                      className="no-ring w-full resize-y rounded-xl border border-sep bg-card px-3 py-2 font-mono text-[12.5px] outline-none focus:border-[var(--label-3)]" />
                    <p className="mt-1 text-[12px] text-label3">Run tests ignores this: the hidden tests use their own values.</p>
                  </div>
                )}
                {!run ? <p className="font-sans text-sm text-label3">Press Run to see your program&apos;s output, or Run tests to check your work.</p>
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
                            <span className="max-w-[220px] truncate">{t.name}</span>
                          </button>
                        ))}
                      </div>
                    )}
                    {lastMode === "main" && run.compiled && ch.sampleOutput && run.exitCode === 0 && !stdin.trim() && (!guide || stepsDone) && <ExpectedOutput expected={ch.sampleOutput} actual={run.stdout} />}
                    {lastMode === "main" && run.compiled && run.exitCode === 0 && guide && !stepsDone && !stdin.trim() && (
                      <p className="mt-3 rounded-xl border border-sep bg-card2 px-3.5 py-2.5 text-[13px] leading-snug text-label2">
                        The finished program prints something different from this on purpose: you are still partway through the steps, so its output is not the goal yet. Press <b className="font-semibold text-label">Check step</b> in the Steps tab to see how far you are. The output is compared once every step passes.
                      </p>
                    )}
                  </div>
                )}
              </>)}
            </div>}
          </div>
        </section>
      </div>

      <FloatingTip tip={fileTip} />

      {/* ---------- Hint: say what it costs, then show it only if the learner agrees ---------- */}
      {hintAsk && (
        <Overlay onClose={() => setHintAsk(false)}>
          <div className="pop w-full max-w-[440px] rounded-[22px] border border-sep bg-card p-7 shadow-[var(--shadow-lg)]" role="alertdialog" aria-labelledby="hint-title">
            <span className="mb-4 grid h-11 w-11 place-items-center rounded-full bg-[var(--orange-soft)] text-warn"><Lightbulb size={20} /></span>
            <h2 id="hint-title" className="text-[22px] font-bold tracking-[-0.02em]">{hintLevel >= 3 ? "Show the full solution?" : `Use hint ${hintLevel + 1} of 4?`}</h2>
            <p className="mt-2 text-[14.5px] leading-relaxed text-label2">
              {start.practice
                ? "This is practice, so your mastery won't change. "
                : hintLevel === 0 ? "This attempt will earn a little less mastery (about 8% less), and your clean-pass count starts over. "
                : hintLevel === 1 ? "This attempt will earn noticeably less mastery (about 20% less), and your clean-pass count starts over. "
                : hintLevel === 2 ? "This attempt will earn much less mastery (about 40% less), and your clean-pass count starts over. "
                : "This shows the complete answer. The attempt can earn at most a fifth of its mastery, and your clean-pass count starts over. "}
              {hintLevel === 0 ? "You get a nudge, not the answer." : hintLevel === 1 ? "You get the key idea." : hintLevel === 2 ? "You get part of the code." : ""}
            </p>
            {hintQuota && <p className="mt-2 text-[13px] text-label3">It also uses one of your {hintQuota.limit} hints this hour ({hintQuota.left} left).</p>}
            <div className="mt-6 flex justify-end gap-2.5">
              <button className="btn btn-secondary" onClick={() => setHintAsk(false)} autoFocus>Not yet</button>
              <button className="btn btn-primary" onClick={() => { setHintAsk(false); getHint(); }}>{hintLevel >= 3 ? "Show solution" : "Show hint"}</button>
            </div>
          </div>
        </Overlay>
      )}

      {restored && (
        <div className="pop pointer-events-none fixed bottom-6 left-1/2 z-[60] -translate-x-1/2">
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
      {sheet === "retry" && retry && <FixSheet r={retry} busy={busy === "submit"} onFix={() => setSheet(null)} onMoveOn={() => submit(true)} />}

      {/* ---------- Result sheet ---------- */}
      {party && sheet === "result" && <Confetti running onDone={endParty} />}
      {sheet === "result" && result && <ResultSheet r={result} concept={concept} lang={lang} onNext={nextChallenge} onHome={() => router.push("/dashboard")} onClose={() => { setSheet(null); setParty(false); }} />}
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
    <div className="fade fixed inset-0 z-[60] grid place-items-center overflow-auto bg-black/40 p-4 backdrop-blur-sm" onMouseDown={(e) => e.target === e.currentTarget && onClose()} role="dialog" aria-modal="true">
      {children}
    </div>
  );
}

/** One failed test: what it checked (its name) and why it failed (the assertion message). */
function FailedTest({ name, message }: { name: string; message: string }) {
  return (
    <div className="rounded-xl border border-sep bg-card2 p-3.5">
      <p className="flex items-start gap-2 text-[14px] font-semibold"><XCircle size={16} className="mt-0.5 shrink-0 text-bad" />{name}</p>
      <p className="mt-2 rounded-lg bg-badsoft px-3 py-2 font-mono text-[12.5px] leading-relaxed text-bad">{message || "This test failed."}</p>
    </div>
  );
}

/** Submission that didn't pass every test: explain each failure, then fix and resubmit, or move on. */
function FixSheet({ r, busy, onFix, onMoveOn }: { r: SubmitResp; busy: boolean; onFix: () => void; onMoveOn: () => void }) {
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
            ? failing.map((t, i) => <FailedTest key={i} name={t.name} message={t.message} />)
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

function ResultSheet({ r, concept, lang, onNext, onHome, onClose }: { r: SubmitResp; concept: string; lang: LangId; onNext: () => void; onHome: () => void; onClose: () => void }) {
  const [shown, setShown] = useState(r.scoreBefore);
  useEffect(() => { const t = setTimeout(() => setShown(r.scoreAfter), 350); return () => clearTimeout(t); }, [r.scoreAfter]);
  const up = r.delta >= 0;
  return (
    <Overlay onClose={onClose}>
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
            <p className="text-[13px] text-label2">Mastery change</p>
            <p className={`text-[28px] font-bold tabular-nums tracking-tight ${up ? "text-ok" : "text-bad"}`}>{up ? "+" : ""}{Math.round(r.delta * 100)}</p>
            <p className="text-xs text-label3">{r.practice ? "Practice: mastery unchanged" : `Now ${Math.round(r.scoreAfter * 100)} of 100`}</p>
            {!r.practice && <p className="text-xs text-label3" title="Passes in a row with no hints and no resubmits. Three in a row masters the concept.">{`Clean passes ${r.streak}/${r.streakGoal}`}</p>}
            {r.calibration && r.calibration !== "unrated" && <span className="chip mt-1.5 capitalize" title="Read from how you worked: first test run, number of runs, pace and help used">{r.calibration}</span>}
          </div>
        </div>
        <ul className="mb-6 space-y-2 text-[14px]">
          {r.feedback.map((f, i) => <li key={i} className="flex gap-2.5"><span className="mt-[7px] h-1.5 w-1.5 shrink-0 rounded-full bg-accent" />{f}</li>)}
        </ul>
        {r.reveal && <RevealCard reveal={r.reveal} />}
        {r.tests.some((t) => !t.passed) && (
          <div className="mb-6 max-h-[30vh] space-y-2 overflow-auto">
            {r.tests.filter((t) => !t.passed).map((t, i) => <FailedTest key={i} name={t.name} message={t.message} />)}
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
