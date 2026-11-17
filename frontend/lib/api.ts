import { supabase } from "./supabase";

export type Files = Record<string, string>;

export interface User { id: number; email: string; displayName: string; preferredLanguage?: string; avatarUrl?: string; languageChosen?: boolean }
export interface Track { id: string; order: number; title: string; summary: string; practice: boolean }
export interface ConceptRow {
  id: string; title: string; summary: string; symbol: string; track: string; group?: string;
  available: boolean; inProgress: boolean; practice?: boolean; progress: number; score: number; streak: number; mastered: boolean; attempts: number;
  /** lessons to know first */
  requires?: Prereq[];
  /** the two ways to master it: this many clean passes in a row, or this mastery score (0..1) with solid passes */
  streakGoal?: number; scoreGoal?: number;
}
/** A lesson to know first. */
export interface Prereq { id: string; title: string }
export interface Lesson { title: string; contentMd: string; source: string; track?: string; practice?: boolean; requires?: Prereq[] }
export interface MasteryView { score: number; streak: number; mastered: boolean; streakGoal: number; scoreGoal?: number; attempts: number }
export interface Challenge {
  id: number; title: string; descriptionMd: string; difficulty: number; language?: string;
  scaffolding: "HEAVY" | "PARTIAL" | "BLANK"; expectedMinutes: number; files: Files;
  /** the tests you can see and run, by name: what is checked (the code of a test is never shown) */
  testNames: string[]; sampleOutput: string;
  /** more tests that run only on Submit (edge cases): how many, never which */
  hiddenTests?: number;
  /** files that are provided and read-only (the main file from SOLID on) */
  lockedFiles?: string[];
  /** lessons to know before this challenge */
  requires?: Prereq[];
}
export interface StartResp extends TimeRecord {
  attemptId: number; challenge: Challenge; mastery: MasteryView;
  resumed: boolean; draft?: Files; hints?: Hint[]; lastRun?: RunResp; startedAt?: string;
  /** redo of a past challenge: graded, mastery unchanged */
  practice?: boolean; hintQuota?: HintQuota;
  /** clock: the attempt's start (moves forward over breaks) and the server's time, to correct for a skewed device clock */
  serverNow?: string;
  /** design-pattern challenges: diagnose question first, then small refactoring steps */
  guide?: Guide;
}
export interface QuizState {
  question: string; options: { id: string; text: string }[]; tries: number; solved: boolean;
  /** only once solved */
  answers?: string[]; explanation?: string; correct?: boolean;
}
export interface Guide { quiz: QuizState; steps: { title: string; md: string }[] }
/** Shown after a guided challenge is finished: now the pattern gets its name. */
export interface PatternReveal { pattern: string; principle: string; roles?: { name: string; is: string }[]; summary: string; concept: string }
/** The learner's record on a challenge. */
export interface TimeRecord {
  attemptNumber?: number; submittedAttempts?: number; bestSeconds?: number | null; longestSeconds?: number | null;
}
export interface HistoryRow { title: string; difficulty: number; passed: number; total: number; delta: number; hints: number; at: string }
export interface Diagnostic { file: string; line: number; severity: string; message: string }
export interface TestResult { name: string; passed: boolean; message: string; index?: number }
export interface RunResp {
  compiled: boolean; diagnostics: Diagnostic[]; stdout: string; stderr: string;
  tests: TestResult[]; timedOut: boolean; exitCode: number; millis: number; runs: number;
}
export interface SubmitResp extends RunResp {
  /** false: not every test passed and the attempt is still open (fix and resubmit, or finish to move on) */
  final: boolean; failedSubmissions: number;
  passed: number; total: number; allPassed: boolean; performance: number;
  scoreBefore: number; scoreAfter: number; delta: number; streak: number; streakGoal: number;
  mastered: boolean; cleanPass: boolean; calibration: string; feedback: string[]; minutes: number;
  /** 0..1, read from how the learner worked; calibration holds its label (fluent / steady / effortful) */
  fluency?: number; practice?: boolean; seconds?: number;
  bestSeconds?: number | null; longestSeconds?: number | null; attemptNumber?: number; submittedAttempts?: number;
  reveal?: PatternReveal;
}
export interface HistoryItem {
  attemptId: number; challengeId: number; title: string; concept: string; conceptTitle: string; language: string;
  difficulty: number; passed: number; total: number; delta: number; hints: number; practice: boolean; at: string;
  track: string; seconds: number | null; attemptNumber: number;
}
export interface ChallengeGroup {
  challengeId: number; title: string; concept: string; conceptTitle: string; track: string; language: string; difficulty: number;
  attempts: HistoryItem[]; solved: number; bestSeconds: number | null; lastAt: string;
}
export interface HistorySummary {
  days: { date: string; minutes: number; solved: number; submitted: number }[];
  byConcept: { concept: string; conceptTitle: string; track: string; attempts: number; solved: number; seconds: number }[];
  byLanguage: { language: string; attempts: number; solved: number }[];
  totals: { attempts: number; solved: number; challenges: number; minutes: number; avgMinutes: number };
}
export interface HistoryPage { items: HistoryItem[]; page: number; size: number; total: number; pages: number }
/** A past attempt: what the challenge page needs to show it again with the learner's code and results. */
export interface AttemptDetail extends TimeRecord {
  attemptId: number; files: Files; passed: number; total: number; delta: number; practice: boolean; submittedAt: string | null;
  challenge: Challenge & { concept: string; language: string };
  hints?: Hint[]; lastRun?: RunResp; guide?: Guide; startedAt?: string; seconds?: number;
}
/** Hints a learner may still ask for in the current rolling hour. */
export interface HintQuota { limit: number; used: number; left: number; resetSeconds: number }
export interface Hint { level: number; maxLevel: number; kind: "nudge" | "concept" | "code" | "solution"; text: string; cost: string; files?: Files; quota?: HintQuota }

/**
 * Supabase access token for API calls. getSession() returns the stored session and refreshes it first when it is
 * about to expire. The last token is also kept in memory for the synchronous save on tab close.
 */
let lastToken: string | null = null;
export async function accessToken(): Promise<string | null> {
  if (typeof window === "undefined") return null;
  try {
    const { data } = await supabase().auth.getSession();
    lastToken = data.session?.access_token ?? null;
  } catch { lastToken = null; }
  return lastToken;
}
export function rememberToken(t: string | null) { lastToken = t; }

export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message); }
}

export async function api<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const token = await accessToken();
  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method: method ?? (body === undefined ? "GET" : "POST"),
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError("Can't reach the server. Check your connection and try again.", 0);
  }
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error ?? `Request failed (${res.status})`, res.status);
  return data as T;
}

/**
 * Starts (or resumes) a challenge. A new one can take a minute to write and test, so the server answers "preparing" after a short
 * wait and this asks again until it is ready: no single request runs long enough for a proxy or the browser to cut it off.
 */
export async function startChallenge(language: string, concept: string, onWait?: (seconds: number) => void, cancelled?: () => boolean): Promise<StartResp> {
  const t0 = Date.now();
  let hiccups = 0;
  for (;;) {
    try {
      const r = await api<StartResp & { preparing?: boolean }>("/challenges/next", { language, concept });
      if (!r.preparing) return r;
      hiccups = 0;
    } catch (e) {
      // a dropped connection or a proxy timeout is not the end of the job: it carries on in the background
      const status = e instanceof ApiError ? e.status : 0;
      if (![0, 502, 504].includes(status) || ++hiccups > 4) throw e;
    }
    if (cancelled?.()) throw new ApiError("Cancelled", 0);
    const waited = Math.round((Date.now() - t0) / 1000);
    if (waited > 240) throw new ApiError("This is taking longer than usual. Please try again.", 504);
    onWait?.(waited);
    await new Promise((done) => setTimeout(done, 1500));
  }
}

/** Best-effort save that survives the tab closing. */
export function saveDraftOnExit(attemptId: number, files: Files) {
  const token = lastToken;
  if (!token) return;
  try {
    fetch(`/api/attempts/${attemptId}/draft`, {
      method: "PUT", keepalive: true,
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ files }),
    });
  } catch {}
}
