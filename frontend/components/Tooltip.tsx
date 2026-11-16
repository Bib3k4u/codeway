"use client";
import { useState, type ReactNode } from "react";
import FloatingTip, { type Tip } from "@/components/FloatingTip";

/** Wraps a button (or anything) so that hovering or focusing it shows a short note on what it does, right away, below it. */
export default function Tooltip({ text, children, className = "" }: { text: string; children: ReactNode; className?: string }) {
  const [tip, setTip] = useState<Tip | null>(null);
  const show = (el: HTMLElement) => {
    const r = el.getBoundingClientRect();
    // y = 0 makes the tip prefer the room below the control, where the toolbar leaves it free
    setTip({ text, x: r.left + r.width / 2, y: 0, belowY: r.bottom, place: "top" });
  };
  return (
    <span className={`inline-flex ${className}`} onMouseEnter={(e) => show(e.currentTarget)} onMouseLeave={() => setTip(null)}
      onFocus={(e) => show(e.currentTarget)} onBlur={() => setTip(null)} onMouseDown={() => setTip(null)}>
      {children}
      <FloatingTip tip={tip} />
    </span>
  );
}
