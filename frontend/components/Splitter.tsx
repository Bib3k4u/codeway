"use client";
import { useRef, useState } from "react";

/**
 * Thin draggable divider. `onMove` receives the pointer coordinate along the axis (clientX for vertical
 * dividers, clientY for horizontal); the parent turns it into a size. Double-click resets, arrow keys nudge.
 */
export default function Splitter({ orientation, onMove, onNudge, onReset, label }: {
  orientation: "vertical" | "horizontal"; onMove: (pos: number) => void; onNudge: (delta: number) => void; onReset: () => void; label: string;
}) {
  const [drag, setDrag] = useState(false);
  const el = useRef<HTMLDivElement>(null);
  const vertical = orientation === "vertical";
  return (
    <div
      ref={el} role="separator" tabIndex={0} aria-label={label} aria-orientation={orientation}
      data-drag={drag} className={`splitter ${vertical ? "splitter-v" : "splitter-h"}`}
      onPointerDown={(e) => { el.current?.setPointerCapture(e.pointerId); setDrag(true); document.body.style.userSelect = "none"; }}
      onPointerMove={(e) => { if (drag) onMove(vertical ? e.clientX : e.clientY); }}
      onPointerUp={(e) => { el.current?.releasePointerCapture(e.pointerId); setDrag(false); document.body.style.userSelect = ""; }}
      onDoubleClick={onReset}
      onKeyDown={(e) => {
        const [dec, inc] = vertical ? ["ArrowLeft", "ArrowRight"] : ["ArrowUp", "ArrowDown"];
        if (e.key === dec) { e.preventDefault(); onNudge(-24); }
        if (e.key === inc) { e.preventDefault(); onNudge(24); }
        if (e.key === "Home" || e.key === "Enter") { e.preventDefault(); onReset(); }
      }}
    />
  );
}
