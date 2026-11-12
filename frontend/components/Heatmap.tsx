"use client";
import { useEffect, useMemo, useRef, useState } from "react";
import FloatingTip, { type Tip } from "./FloatingTip";

const CELL = 12, GAP = 3, STEP = CELL + GAP, LEFT = 30, TOP = 18;
const DOW = ["", "Mon", "", "Wed", "", "Fri", ""];
const MONTHS = ["Jan", "Feb", "Mar", "Apr", "May", "Jun", "Jul", "Aug", "Sep", "Oct", "Nov", "Dec"];

function level(n: number) { return n <= 0 ? 0 : n <= 2 ? 1 : n <= 5 ? 2 : n <= 9 ? 3 : 4; }
function iso(d: Date) { return d.toISOString().slice(0, 10); }
function parse(s: string) { const [y, m, d] = s.split("-").map(Number); return new Date(Date.UTC(y, m - 1, d)); }
function fmt(s: string) { return parse(s).toLocaleDateString(undefined, { weekday: "short", month: "short", day: "numeric", year: "numeric", timeZone: "UTC" }); }

/** GitHub-style calendar of correct submissions per day: 53 weeks, one sequential hue (yellow), hover tooltip per day. */
export default function Heatmap({ days, today }: { days: Record<string, number>; today: string }) {
  const scroller = useRef<HTMLDivElement>(null);
  const [tip, setTip] = useState<Tip | null>(null);
  const [table, setTable] = useState(false);

  const { cells, months, cols } = useMemo(() => {
    const end = parse(today);
    const start = new Date(end); start.setUTCDate(end.getUTCDate() - (52 * 7 + end.getUTCDay()));
    const cells: { date: string; count: number; col: number; row: number }[] = [];
    const months: { col: number; label: string }[] = [];
    let lastMonth = -1;
    for (let d = new Date(start), i = 0; d <= end; d.setUTCDate(d.getUTCDate() + 1), i++) {
      const col = Math.floor(i / 7), row = d.getUTCDay(), date = iso(d);
      cells.push({ date, count: days[date] ?? 0, col, row });
      if (row === 0 && d.getUTCMonth() !== lastMonth) { lastMonth = d.getUTCMonth(); months.push({ col, label: MONTHS[lastMonth] }); }
    }
    return { cells, months: months.filter((m, i) => i > 0 || m.col < 50), cols: Math.floor((cells.length - 1) / 7) + 1 };
  }, [days, today]);

  useEffect(() => { const el = scroller.current; if (el) el.scrollLeft = el.scrollWidth; }, [cells]);
  const width = LEFT + cols * STEP, height = TOP + 7 * STEP;
  const active = Object.entries(days).filter(([, n]) => n > 0).sort((a, b) => b[0].localeCompare(a[0]));

  return (
    <div>
      <div ref={scroller} className="relative overflow-x-auto pb-1" onScroll={() => setTip(null)}>
        <svg viewBox={`0 0 ${width} ${height}`} width="100%" style={{ minWidth: 760 }} role="img" aria-label={`Activity calendar: ${active.length} active days in the last year`} className="block">
          {months.map((m) => <text key={m.col + m.label} x={LEFT + m.col * STEP} y={11} fontSize={11} fill="var(--label-3)">{m.label}</text>)}
          {DOW.map((d, r) => d && <text key={r} x={0} y={TOP + r * STEP + CELL - 2} fontSize={10} fill="var(--label-3)">{d}</text>)}
          {cells.map((c) => (
            <rect key={c.date} x={LEFT + c.col * STEP} y={TOP + c.row * STEP} width={CELL} height={CELL} rx={2.5}
              fill={`var(--heat-${level(c.count)})`} stroke="var(--sep)" strokeWidth={1}
              onMouseEnter={(e) => {
                const r = e.currentTarget.getBoundingClientRect();
                setTip({ place: "top", x: r.left + r.width / 2, y: r.top, belowY: r.bottom, text: `${c.count === 0 ? "No" : c.count} correct ${c.count === 1 ? "submission" : "submissions"} · ${fmt(c.date)}` });
              }}
              onMouseLeave={() => setTip(null)} />
          ))}
        </svg>
        <FloatingTip tip={tip} />
      </div>
      <div className="mt-3 flex flex-wrap items-center justify-between gap-3 text-xs text-label3">
        <button className="underline-offset-2 hover:text-label hover:underline" onClick={() => setTable((t) => !t)} aria-expanded={table}>{table ? "Hide" : "View"} as list</button>
        <div className="flex items-center gap-1.5" aria-hidden>
          Less{[0, 1, 2, 3, 4].map((l) => <span key={l} className="inline-block h-[11px] w-[11px] rounded-[2.5px] border border-sep" style={{ background: `var(--heat-${l})` }} />)}More
        </div>
      </div>
      {table && (
        <div className="mt-3 max-h-48 overflow-auto rounded-lg border border-sep">
          <table className="w-full text-sm">
            <thead className="sticky top-0 bg-card2 text-left text-xs text-label3"><tr><th className="px-3 py-2 font-medium">Day</th><th className="px-3 py-2 text-right font-medium">Activities</th></tr></thead>
            <tbody>{active.length === 0 ? <tr><td className="px-3 py-3 text-label3" colSpan={2}>No correct submissions yet.</td></tr> : active.map(([d, n]) => (
              <tr key={d} className="border-t border-sep"><td className="px-3 py-1.5">{fmt(d)}</td><td className="px-3 py-1.5 text-right tabular-nums">{n}</td></tr>
            ))}</tbody>
          </table>
        </div>
      )}
    </div>
  );
}
