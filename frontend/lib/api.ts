export type Files = Record<string, string>;

export interface User { id: number; email: string; displayName: string; preferredLanguage?: string }
export interface ConceptRow {
  id: string; title: string; summary: string; symbol: string;
  available: boolean; inProgress: boolean; practice?: boolean; progress: number; score: number; streak: number; mastered: boolean; attempts: number;
}
export interface Lesson { title: string; contentMd: string; source: string }
export interface MasteryView { score: number; streak: number; mastered: boolean; streakGoal: number; attempts: number }
export interface Challenge {
  id: number; title: string; descriptionMd: string; difficulty: number;
  scaffolding: "HEAVY" | "PARTIAL" | "BLANK"; expectedMinutes: number; files: Files;
  testNames: string[]; sampleOutput: string;
}
export interface StartResp {
  attemptId: number; challenge: Challenge; mastery: MasteryView;
  resumed: boolean; draft?: Files; hints?: Hint[]; lastRun?: RunResp; startedAt?: string;
}
export interface HistoryRow { title: string; difficulty: number; passed: number; total: number; delta: number; hints: number; at: string }
export interface Diagnostic { file: string; line: number; severity: string; message: string }
export interface TestResult { name: string; passed: boolean; message: string }
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
}
export interface Hint { level: number; maxLevel: number; kind: "nudge" | "concept" | "code" | "solution"; text: string; cost: string; files?: Files }

const KEY = "codeway_token";
export const tokenStore = {
  get(): string | null { try { return localStorage.getItem(KEY); } catch { return null; } },
  set(t: string) { try { localStorage.setItem(KEY, t); } catch {} },
  clear() { try { localStorage.removeItem(KEY); } catch {} },
};

export class ApiError extends Error {
  constructor(message: string, public status: number) { super(message); }
}

export async function api<T>(path: string, body?: unknown, method?: string): Promise<T> {
  const token = tokenStore.get();
  let res: Response;
  try {
    res = await fetch(`/api${path}`, {
      method: method ?? (body === undefined ? "GET" : "POST"),
      headers: { "Content-Type": "application/json", ...(token ? { Authorization: `Bearer ${token}` } : {}) },
      body: body === undefined ? undefined : JSON.stringify(body),
    });
  } catch {
    throw new ApiError("Can't reach the server. Is the backend running on port 8080?", 0);
  }
  if (res.status === 401 && token) tokenStore.clear();
  const data = await res.json().catch(() => ({}));
  if (!res.ok) throw new ApiError(data.error ?? `Request failed (${res.status})`, res.status);
  return data as T;
}

/** Best-effort save that survives the tab closing. */
export function saveDraftOnExit(attemptId: number, files: Files) {
  const token = tokenStore.get();
  if (!token) return;
  try {
    fetch(`/api/attempts/${attemptId}/draft`, {
      method: "PUT", keepalive: true,
      headers: { "Content-Type": "application/json", Authorization: `Bearer ${token}` },
      body: JSON.stringify({ files }),
    });
  } catch {}
}
