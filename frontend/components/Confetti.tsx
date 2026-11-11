"use client";
import { useEffect, useRef } from "react";

const COLORS = ["#ff595e", "#ff924c", "#ffca3a", "#c5ca30", "#8ac926", "#36949d", "#1982c4", "#4267ac", "#6a4c93", "#f15bb5", "#00bbf9", "#fee440"];
const MAX = 420;

type Shape = "rect" | "circle" | "ribbon";
interface P {
  x: number; y: number; vx: number; vy: number;
  w: number; h: number; color: string; shape: Shape;
  angle: number; spin: number; tilt: number; tiltSpeed: number;
  wobble: number; wobbleSpeed: number; wobbleAmp: number;
  drag: number; gravity: number; terminal: number;
}

const rand = (a: number, b: number) => a + Math.random() * (b - a);

/**
 * Full-screen party popper. While `running`, it keeps raining multicolour confetti from across the top and fires
 * cannon bursts from the bottom corners. When `running` turns false it stops emitting, lets the rest fall, then calls onDone.
 */
export default function Confetti({ running, onDone }: { running: boolean; onDone?: () => void }) {
  const ref = useRef<HTMLCanvasElement>(null);
  const runningRef = useRef(running);
  const doneRef = useRef(onDone);
  useEffect(() => { runningRef.current = running; }, [running]);
  useEffect(() => { doneRef.current = onDone; }, [onDone]);

  useEffect(() => {
    const canvas = ref.current;
    if (!canvas) return;
    if (window.matchMedia("(prefers-reduced-motion: reduce)").matches) { doneRef.current?.(); return; }
    const ctx = canvas.getContext("2d")!;
    let W = innerWidth, H = innerHeight;
    const resize = () => {
      const dpr = Math.min(2, window.devicePixelRatio || 1);
      W = innerWidth; H = innerHeight;
      canvas.width = W * dpr; canvas.height = H * dpr; ctx.setTransform(dpr, 0, 0, dpr, 0, 0);
    };
    resize();
    window.addEventListener("resize", resize);

    const parts: P[] = [];
    const make = (x: number, y: number, vx: number, vy: number): P => {
      const r = Math.random();
      const shape: Shape = r < 0.55 ? "rect" : r < 0.8 ? "circle" : "ribbon";
      const size = rand(6, 12);
      return {
        x, y, vx, vy, shape,
        w: shape === "ribbon" ? size * 0.45 : size, h: shape === "ribbon" ? size * 2.4 : size * rand(0.5, 0.9),
        color: COLORS[(Math.random() * COLORS.length) | 0],
        angle: rand(0, Math.PI * 2), spin: rand(-0.12, 0.12), tilt: rand(0, Math.PI * 2), tiltSpeed: rand(0.05, 0.16),
        wobble: rand(0, Math.PI * 2), wobbleSpeed: rand(0.03, 0.08), wobbleAmp: rand(0.4, 1.6),
        drag: rand(0.975, 0.99), gravity: rand(0.06, 0.12), terminal: rand(2.2, 4.2),
      };
    };
    const rain = (n: number) => {
      for (let i = 0; i < n && parts.length < MAX; i++) parts.push(make(rand(-20, W + 20), rand(-60, -10), rand(-1.5, 1.5), rand(0.5, 2.5)));
    };
    const cannon = (fromLeft: boolean, n: number) => {
      const x = fromLeft ? -10 : W + 10, base = fromLeft ? -Math.PI / 3 : -Math.PI + Math.PI / 3;
      for (let i = 0; i < n && parts.length < MAX; i++) {
        const a = base + rand(-0.35, 0.35), v = rand(H * 0.018, H * 0.03);
        parts.push(make(x, H + 10, Math.cos(a) * v, Math.sin(a) * v));
      }
    };

    // opening: both cannons + a heavy first shower
    cannon(true, 90); cannon(false, 90); rain(60);
    let last = performance.now(), sinceCannon = 0, rainAcc = 0, side = true, raf = 0, finished = false;

    const tick = (now: number) => {
      const dt = Math.min(48, now - last) / 16.67; // frames elapsed at 60fps
      last = now;
      if (runningRef.current) {
        rainAcc += 1.6 * dt;                       // steady rain across the top
        const n = Math.floor(rainAcc); rainAcc -= n; rain(n);
        sinceCannon += dt;
        if (sinceCannon > 150) { cannon(side, 45); side = !side; sinceCannon = 0; } // alternate side bursts every ~2.5s
      }

      ctx.clearRect(0, 0, W, H);
      for (let i = parts.length - 1; i >= 0; i--) {
        const p = parts[i];
        p.vx *= Math.pow(p.drag, dt);
        p.vy = Math.min(p.vy * Math.pow(p.drag, dt) + p.gravity * dt, p.terminal);
        p.wobble += p.wobbleSpeed * dt;
        p.x += (p.vx + Math.sin(p.wobble) * p.wobbleAmp) * dt;
        p.y += p.vy * dt;
        p.angle += p.spin * dt;
        p.tilt += p.tiltSpeed * dt;
        if (p.y > H + 40 || p.x < -80 || p.x > W + 80) { parts.splice(i, 1); continue; }

        ctx.save();
        ctx.translate(p.x, p.y);
        ctx.rotate(p.angle);
        ctx.scale(1, Math.cos(p.tilt)); // 3D flip
        ctx.fillStyle = p.color;
        if (p.shape === "circle") { ctx.beginPath(); ctx.arc(0, 0, p.w / 2.2, 0, Math.PI * 2); ctx.fill(); }
        else ctx.fillRect(-p.w / 2, -p.h / 2, p.w, p.h);
        ctx.restore();
      }

      if (!runningRef.current && parts.length === 0) {
        if (!finished) { finished = true; doneRef.current?.(); }
        return;
      }
      raf = requestAnimationFrame(tick);
    };
    raf = requestAnimationFrame(tick);
    return () => { cancelAnimationFrame(raf); window.removeEventListener("resize", resize); };
  }, []);

  return <canvas ref={ref} aria-hidden className="pointer-events-none fixed inset-0 z-[90] h-screen w-screen" />;
}
