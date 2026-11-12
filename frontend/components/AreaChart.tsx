"use client";
import { useEffect, useMemo, useRef, useState } from "react";

export interface Point { date: string; value: number }

const H = 200, PAD = { top: 12, right: 12, bottom: 26, left: 40 };

/** A tidy max for the y axis: 1, 2, 2.5 or 5 times a power of ten, so gridlines land on round numbers. */
function niceMax(v: number) {
  if (v <= 0) return 1;
  const p = Math.pow(10, Math.floor(Math.log10(v)));
  for (const m of [1, 2, 2.5, 5, 10]) if (m * p >= v) return m * p;
  return 10 * p;
}

/**
 * Single-series area chart over days (one hue: the accent, 2px line over a soft fill). Hover or arrow keys move a
 * crosshair with a tooltip; the title outside names the series, so there is no legend.
 */
export default function AreaChart({ points, format, unit }: { points: Point[]; format: (v: number) => string; unit: string }) {
  const box = useRef<HTMLDivElement>(null);
  const [w, setW] = useState(640);
  const [hover, setHover] = useState<number | null>(null);
  useEffect(() => {
    if (!box.current) return;
    const ro = new ResizeObserver(([e]) => setW(Math.max(280, Math.round(e.contentRect.width))));
    ro.observe(box.current);
    return () => ro.disconnect();
  }, []);

  const { path, area, max, xs, ys, ticks, labels } = useMemo(() => {
    const n = points.length;
    const max = niceMax(Math.max(0, ...points.map((p) => p.value)));
    const iw = w - PAD.left - PAD.right, ih = H - PAD.top - PAD.bottom;
    const xs = points.map((_, i) => PAD.left + (n <= 1 ? iw / 2 : (i / (n - 1)) * iw));
    const ys = points.map((p) => PAD.top + ih - (p.value / max) * ih);
    const line = xs.map((x, i) => `${i ? "L" : "M"}${x.toFixed(1)},${ys[i].toFixed(1)}`).join("");
    const area = n ? `${line}L${xs[n - 1].toFixed(1)},${PAD.top + ih}L${xs[0].toFixed(1)},${PAD.top + ih}Z` : "";
    const ticks = [0, 0.25, 0.5, 0.75, 1].map((f) => ({ y: PAD.top + ih - f * ih, v: f * max }));
    // ~6 date labels, always including the last day
    const step = Math.max(1, Math.ceil(n / 6));
    const labels = points.map((p, i) => ({ i, p })).filter(({ i }) => (n - 1 - i) % step === 0);
    return { path: line, area, max, xs, ys, ticks, labels };
  }, [points, w]);

  const fmtDate = (iso: string, long = false) =>
    new Date(iso + "T00:00:00").toLocaleDateString(undefined, long ? { weekday: "short", month: "short", day: "numeric" } : { month: "short", day: "numeric" });

  const pick = (clientX: number) => {
    const r = box.current?.getBoundingClientRect();
    if (!r || !xs.length) return;
    const x = clientX - r.left;
    let best = 0;
    xs.forEach((v, i) => { if (Math.abs(v - x) < Math.abs(xs[best] - x)) best = i; });
    setHover(best);
  };

  const h = hover !== null ? points[hover] : null;
  return (
    <div ref={box} className="relative select-none" tabIndex={0} role="img"
      aria-label={`${unit} per day, ${points.length} days, highest ${format(Math.max(0, ...points.map((p) => p.value)))}`}
      onMouseMove={(e) => pick(e.clientX)} onMouseLeave={() => setHover(null)}
      onKeyDown={(e) => {
        if (e.key === "ArrowRight") setHover((v) => Math.min(points.length - 1, (v ?? points.length - 1) + 1));
        else if (e.key === "ArrowLeft") setHover((v) => Math.max(0, (v ?? points.length - 1) - 1));
      }}
      onBlur={() => setHover(null)}>
      <svg width={w} height={H} className="block overflow-visible">
        <defs>
          <linearGradient id="area-fill" x1="0" x2="0" y1="0" y2="1">
            <stop offset="0%" stopColor="var(--accent-fill)" stopOpacity="0.38" />
            <stop offset="100%" stopColor="var(--accent-fill)" stopOpacity="0.02" />
          </linearGradient>
        </defs>
        {ticks.map((t, i) => (
          <g key={i}>
            <line x1={PAD.left} x2={w - PAD.right} y1={t.y} y2={t.y} stroke="var(--sep)" strokeDasharray={i === 0 ? undefined : "2 4"} />
            <text x={PAD.left - 8} y={t.y + 4} textAnchor="end" fontSize="11" fill="var(--label-3)">{max <= 4 && t.v % 1 ? "" : format(t.v)}</text>
          </g>
        ))}
        {labels.map(({ i, p }) => (
          <text key={i} x={xs[i]} y={H - 6} textAnchor={i === points.length - 1 ? "end" : i === 0 ? "start" : "middle"} fontSize="11" fill="var(--label-3)">{fmtDate(p.date)}</text>
        ))}
        <path d={area} fill="url(#area-fill)" />
        <path d={path} fill="none" stroke="var(--accent-fill)" strokeWidth="2" strokeLinejoin="round" strokeLinecap="round" />
        {hover !== null && (
          <g>
            <line x1={xs[hover]} x2={xs[hover]} y1={PAD.top} y2={H - PAD.bottom} stroke="var(--label-3)" strokeDasharray="3 3" />
            <circle cx={xs[hover]} cy={ys[hover]} r="5" fill="var(--accent-fill)" stroke="var(--card)" strokeWidth="2" />
          </g>
        )}
      </svg>
      {h && hover !== null && (
        <div className="pointer-events-none absolute z-10 -translate-x-1/2 whitespace-nowrap rounded-lg border border-sep bg-card px-2.5 py-1.5 text-[12px] shadow-[var(--shadow-lg)]"
          style={{ left: Math.min(w - 70, Math.max(70, xs[hover])), top: Math.max(0, ys[hover] - 52) }}>
          <p className="font-semibold tabular-nums text-label">{format(h.value)} <span className="font-normal text-label2">{unit}</span></p>
          <p className="text-label3">{fmtDate(h.date, true)}</p>
        </div>
      )}
    </div>
  );
}
