import { supabase } from "./supabase";

export type Files = Record<string, string>;

export interface User { id: number; email: string; displayName: string; preferredLanguage?: string; avatarUrl?: string }
export interface Track { id: string; order: number; title: string; summary: string; practice: boolean }
export interface ConceptRow {
  id: string; title: string; summary: string; symbol: string; track: string; group?: string;
  available: boolean; inProgress: boolean; practice?: boolean; progress: number; score: number; streak: number; mastered: boolean; attempts: number;
}
export interface Lesson { title: string; contentMd: string; source: string; track?: string; practice?: boolean }
export interface MasteryView { score: number; streak: number; mastered: boolean; streakGoal: number; attempts: number }
export interface Challenge {
  id: number; title: string; descriptionMd: string; difficulty: number; language?: string;
  scaffolding: "HEAVY" | "PARTIAL" | "BLANK"; expectedMinutes: number; files: Files;
  testNames: string[]; sampleOutput: string; examples?: TestExample[];
}
export interface StartResp {
  attemptId: number; challenge: Challenge; mastery: MasteryView;
  resumed: boolean; draft?: Files; hints?: Hint[]; lastRun?: RunResp; startedAt?: string;
  /** redo of a past challenge: graded, mastery unchanged */
  practice?: boolean; revealed?: number[];
}
export interface HistoryRow { title: string; difficulty: number; passed: number; total: number; delta: number; hints: number; at: string }
export interface Diagnostic { file: string; line: number; severity: string; message: string }
export interface TestResult { name: string; passed: boolean; message: string; hidden?: boolean; revealed?: boolean; index?: number }
export interface TestExample { name: string; code: string }
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
  fluency?: number; practice?: boolean;
}
export interface RevealResp { index: number; name: string; passed?: boolean; message?: string; reveals: number }
export interface HistoryItem {
  attemptId: number; challengeId: number; title: string; concept: string; conceptTitle: string; language: string;
  difficulty: number; passed: number; total: number; delta: number; hints: number; practice: boolean; at: string;
}
export interface HistoryPage { items: HistoryItem[]; page: number; size: number; total: number; pages: number }
export interface AttemptDetail {
  attemptId: number; files: Files; passed: number; total: number; delta: number; practice: boolean; submittedAt: string | null;
  challenge: { id: number; title: string; descriptionMd: string; language: string; concept: string; difficulty: number };
}
export interface Hint { level: number; maxLevel: number; kind: "nudge" | "concept" | "code" | "solution"; text: string; cost: string; files?: Files }

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
