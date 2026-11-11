"use client";
import { useLayoutEffect, useRef } from "react";
import { createPortal } from "react-dom";

/**
 * x/y is the anchor point in viewport coordinates.
 * "top": centred above the anchor (flips below `belowY` if there is no room).
 * "right": to the right of the anchor, vertically centred (flips left if there is no room).
 */
export type Tip = { text: string; x: number; y: number; place: "top" | "right"; belowY?: number; leftX?: number };

/** Tooltip rendered in a portal on <body>, so scroll containers can't clip it, and kept inside the viewport. */
export default function FloatingTip({ tip }: { tip: Tip | null }) {
  const ref = useRef<HTMLDivElement>(null);

  useLayoutEffect(() => {
    const el = ref.current;
    if (!tip || !el) return;
    const w = el.offsetWidth, h = el.offsetHeight, m = 8, gap = 8;
    let left: number, top: number;
    if (tip.place === "right") {
      left = tip.x + gap;
      if (left + w > innerWidth - m) left = (tip.leftX ?? tip.x) - w - gap;
      top = tip.y - h / 2;
    } else {
      left = tip.x - w / 2;
      top = tip.y - h - gap;
      if (top < m) top = (tip.belowY ?? tip.y) + gap;
    }
    left = Math.max(m, Math.min(innerWidth - w - m, left));
    top = Math.max(m, Math.min(innerHeight - h - m, top));
    el.style.left = `${left}px`;
    el.style.top = `${top}px`;
    el.style.visibility = "visible";
  }, [tip]);

  if (!tip || typeof document === "undefined") return null;
  return createPortal(
    <div ref={ref} role="tooltip"
      className="pointer-events-none fixed z-[200] whitespace-nowrap rounded-lg px-2.5 py-1.5 text-xs font-semibold shadow-lg"
      style={{ left: 0, top: 0, visibility: "hidden", background: "var(--label)", color: "var(--bg)" }}>
      {tip.text}
    </div>,
    document.body,
  );
}
