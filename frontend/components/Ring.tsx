"use client";
export default function Ring({ value, size = 56, stroke = 6, mastered = false, label = true }: { value: number; size?: number; stroke?: number; mastered?: boolean; label?: boolean }) {
  const r = (size - stroke) / 2;
  const c = 2 * Math.PI * r;
  const v = Math.max(0, Math.min(1, value));
  return (
    <div className="relative shrink-0" style={{ width: size, height: size }} role="img" aria-label={`Mastery ${Math.round(v * 100)} percent`}>
      <svg width={size} height={size} className="-rotate-90">
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" stroke="var(--fill)" strokeWidth={stroke} />
        <circle cx={size / 2} cy={size / 2} r={r} fill="none" strokeWidth={stroke} strokeLinecap="round"
          stroke={mastered ? "var(--green)" : "var(--accent)"} strokeDasharray={c} strokeDashoffset={c * (1 - v)}
          style={{ transition: "stroke-dashoffset 1.1s cubic-bezier(0.22, 1, 0.36, 1), stroke 0.4s" }} />
      </svg>
      {label && <span className="absolute inset-0 grid place-items-center font-semibold tabular-nums" style={{ fontSize: size * 0.27 }}>{Math.round(v * 100)}</span>}
    </div>
  );
}
