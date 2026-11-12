"use client";
import { Suspense, useEffect, useRef, useState, type ReactNode } from "react";
import Image from "next/image";
import { useRouter, useSearchParams } from "next/navigation";
import { ArrowRight, ArrowUp, Bot, Loader2, X } from "lucide-react";
import HeroDemo from "@/components/HeroDemo";
import { useAuth } from "@/lib/auth";
import { Logo } from "@/components/AppShell";
import ThemeToggle from "@/components/ThemeToggle";

const NAV = [
  { id: "product", label: "Product" },
  { id: "adapt", label: "How it adapts" },
  { id: "curriculum", label: "Curriculum" },
];

/** An illustrative run through one concept: what happened, and what the tutor did next. */
const RUN = [
  { n: 1, title: "Shape Parade", setup: "Guided · level 1", what: "5/5 tests on the first run, felt confident, no hints", next: "Next one gets harder and less scaffolded" },
  { n: 2, title: "Payment Router", setup: "Skeleton · level 2", what: "Passed after 6 runs and 3 compile errors, opened 2 hints", next: "Stays at level 2, streak resets" },
  { n: 3, title: "Notification Hub", setup: "Skeleton · level 2", what: "Passed on the second run, no hints", next: "Streak 1 of 3, same level" },
  { n: 4, title: "Plugin Loader", setup: "From scratch · level 3", what: "Passed, no hints, quicker than expected", next: "Streak 2 of 3, moves up" },
];

const TRACKS = [
  { n: "01", title: "Object-oriented programming", status: "Practice open", topics: "Classes and objects, encapsulation, inheritance, polymorphism, abstraction, interfaces" },
  { n: "02", title: "SOLID principles", status: "Practice open", topics: "Single responsibility, open/closed, Liskov substitution, interface segregation, dependency inversion" },
  { n: "03", title: "Design patterns", status: "Lessons open", topics: "Singleton, factory, builder, adapter, decorator, facade, strategy, observer, command, state" },
  { n: "04", title: "Low-level design", status: "Lessons open", topics: "Parking lot, elevator, library system, Splitwise, rate limiter: from requirements to classes to working code" },
];

export default function Page() {
  return <Suspense fallback={null}><HomePage /></Suspense>;
}

function HomePage() {
  const { user, loading } = useAuth();
  const router = useRouter();
  // ?auth=login|register (e.g. from the old /login URL) opens the dialog straight away
  const params = useSearchParams();
  const [auth, setAuth] = useState<null | "login" | "register">(() => {
    const a = params.get("auth");
    return a === "login" || a === "register" ? a : null;
  });
  useEffect(() => { if (!loading && user) router.replace("/dashboard"); }, [loading, user, router]);

  // navbar: highlight the section in view; clicking scrolls smoothly (sections carry scroll-margin for the sticky header)
  const [active, setActive] = useState("");
  useEffect(() => {
    const els = NAV.map((n) => document.getElementById(n.id)).filter((e): e is HTMLElement => !!e);
    const obs = new IntersectionObserver((entries) => {
      for (const e of entries) if (e.isIntersecting) setActive(e.target.id);
    }, { rootMargin: "-45% 0px -50% 0px" });
    els.forEach((e) => obs.observe(e));
    const onTop = () => { if (window.scrollY < 200) setActive(""); };
    window.addEventListener("scroll", onTop, { passive: true });
    return () => { obs.disconnect(); window.removeEventListener("scroll", onTop); };
  }, []);
  const goTo = (id: string) => (e: React.MouseEvent) => {
    e.preventDefault();
    if (id === "top") window.scrollTo({ top: 0, behavior: "smooth" });
    else document.getElementById(id)?.scrollIntoView({ behavior: "smooth", block: "start" });
    try { window.history.replaceState(null, "", id === "top" ? window.location.pathname : `#${id}`); } catch {}
  };
  const openAuth = (mode: "login" | "register") => () => setAuth(mode);

  return (
    <div className="min-h-screen">
      <header className="glass sticky top-0 z-40">
        <div className="mx-auto flex h-16 max-w-[1440px] items-center justify-between gap-4 px-5 sm:px-8 xl:px-12">
          <a href="#top" onClick={goTo("top")} aria-label="Codeway home"><Logo height={30} /></a>
          <nav className="hidden items-center gap-1 text-sm font-semibold md:flex" aria-label="Sections">
            {NAV.map((n) => (
              <a key={n.id} href={`#${n.id}`} onClick={goTo(n.id)} aria-current={active === n.id ? "true" : undefined}
                className={`navlink mx-3 py-2 transition-colors ${active === n.id ? "text-label" : "text-label2 hover:text-label"}`}>
                {n.label}
              </a>
            ))}
          </nav>
          <div className="flex items-center gap-2.5">
            <ThemeToggle compact />
            <button className="btn btn-secondary" onClick={openAuth("login")}>Sign in</button>
            <button className="btn btn-primary max-sm:hidden" onClick={openAuth("register")}>Start free</button>
          </div>
        </div>
      </header>

      <main id="top">
        {/* hero: exactly one screen tall, including the 64px navbar */}
        <section className="relative flex min-h-[calc(100svh-64px)] flex-col overflow-hidden">
          <div aria-hidden className="pointer-events-none absolute inset-0 -z-10">
            <div className="dot-grid absolute inset-0" />
            <div className="drift absolute -top-24 right-[8%] h-[460px] w-[460px] rounded-full blur-3xl" style={{ background: "var(--glow)" }} />
            <div className="drift-slow absolute bottom-[-120px] left-[-80px] h-[360px] w-[360px] rounded-full blur-3xl" style={{ background: "var(--accent-soft)" }} />
          </div>
          {/* text pinned left, scene pinned right: the gap between them grows with the window */}
          <div className="flex flex-1 items-center">
          <div className="mx-auto flex w-full max-w-[1440px] flex-col gap-12 px-5 py-12 sm:px-8 lg:flex-row lg:items-center lg:justify-between lg:gap-20 xl:px-12">
            <div className="rise lg:max-w-[540px]">
              <h1 className="display text-[52px] sm:text-[68px] lg:text-[78px]">
                A coding tutor that writes{" "}
                <span className="relative inline-block text-accent">
                  your next challenge.
                  <span aria-hidden className="sweep absolute -bottom-1 left-0 h-[5px] w-full rounded-full bg-[var(--accent-fill)] opacity-50" />
                </span>
              </h1>
              <p className="mt-7 max-w-[470px] text-[18px] leading-relaxed text-label2">
                AI-written challenges at your level, in Java, Python, JavaScript or C++, that adapt after every submission, with hints that coach instead of solve.
              </p>
              <div className="mt-9 flex flex-wrap items-center gap-3">
                <button className="btn btn-primary btn-lg" onClick={openAuth("register")}>Start free<ArrowRight size={18} /></button>
                <button className="btn btn-secondary btn-lg" onClick={openAuth("login")}>I have an account</button>
              </div>
            </div>
            <div className="rise w-full lg:max-w-[600px]" style={{ animationDelay: "150ms" }}>
              <HeroDemo />
            </div>
          </div>
          </div>
          <TopicTicker />
        </section>

        {/* product */}
        <Block id="product">
          <Heading align="center" title="The whole workspace, in your browser.">
            A real multi-file project with a compiler and hidden tests, generated for you and checked before you see it.
          </Heading>
          <div className="mt-14"><Screenshot name="workspace" alt="The Codeway workspace: a challenge brief, a code editor and test results with 3 of 5 tests passing" path="codeway.app/learn/polymorphism/practice" priority /></div>
          <div className="mt-12 grid gap-8 md:grid-cols-3">
            <Point title="A real project, not a text box">Several files, a real compiler or interpreter, and hidden tests that run in a sandbox. Errors show up on the line that caused them.</Point>
            <Point title="Generated, then checked">Each challenge ships with its own reference solution, which has to pass every test before you see it. If it fails, the challenge is rewritten.</Point>
            <Point title="Picks up where you left off">Your code, open hints and last test run are saved as you type. Close the tab and come back next week to the same place.</Point>
          </div>
        </Block>

        {/* how it adapts */}
        <Block id="adapt" muted>
          <div className="grid items-center gap-12 lg:grid-cols-[minmax(0,0.9fr)_minmax(0,1.1fr)]">
            <div>
              <Heading title="The next challenge depends on how you did on this one.">
                After every submission Codeway weighs your first-run results, runs and compile errors, time taken, hints opened,
                and how confident you said you were.
              </Heading>
              <p className="mt-4 max-w-[600px] text-[17px] leading-relaxed text-label2">
                That sets the difficulty and how much code you start with, from mostly written, to just the signatures, to a blank
                project. Three clean passes in a row at your level and the concept is done.
              </p>
            </div>
            <div className="card overflow-hidden">
              <div className="flex items-center justify-between border-b border-sep px-5 py-3.5">
                <p className="text-[13px] font-semibold">Example run · Polymorphism</p>
                <p className="text-[12px] text-label3">illustrative</p>
              </div>
              <ol>
                {RUN.map((r) => (
                  <li key={r.n} className="grid gap-1 border-b border-sep px-5 py-4 last:border-b-0 sm:grid-cols-[28px_minmax(0,1fr)]">
                    <span className="font-mono text-[13px] text-label3">{String(r.n).padStart(2, "0")}</span>
                    <div>
                      <p className="flex flex-wrap items-baseline gap-x-3 text-[15px]">
                        <span className="font-semibold">{r.title}</span>
                        <span className="font-mono text-[12px] text-label3">{r.setup}</span>
                      </p>
                      <p className="mt-1 text-[14px] text-label2">{r.what}</p>
                      <p className="mt-1.5 flex items-center gap-1.5 text-[13.5px] font-semibold text-accent"><ArrowRight size={13} />{r.next}</p>
                    </div>
                  </li>
                ))}
              </ol>
            </div>
          </div>
        </Block>

        {/* hints */}
        <Block>
          <div className="grid items-center gap-12 lg:grid-cols-[minmax(0,1.1fr)_minmax(0,0.9fr)]">
            <div className="space-y-3">
              <HintCard level={1} kind="Nudge" cost="Small cost">
                Look at <code className="tok-fn">describe()</code>. It calls <code className="tok-fn">area()</code> without knowing the subclass. What decides which version runs?
              </HintCard>
              <HintCard level={2} kind="Concept" cost="Medium cost">
                Each subclass overrides <code className="tok-fn">area()</code>. In <code className="tok-fn">totalArea</code> you can call it on a <code className="tok-type">Shape</code>; the right override runs at runtime, in any of the four languages.
              </HintCard>
              <HintCard level={3} kind="Partial code" cost="Large cost" dim>
                <span className="font-mono text-[13px]"><span className="tok-kw">for</span> (<span className="tok-type">Shape</span> <span className="tok-param">s</span> : <span className="tok-param">shapes</span>) <span className="tok-param">total</span> += <span className="tok-param">s</span>.<span className="tok-fn">area</span>();</span>
              </HintCard>
            </div>
            <div>
              <Heading title="Hints that keep you writing the code.">
                Help is opt-in and comes in steps: a question, then the idea behind it, then a small snippet. Each one is based on
                your current code and failing tests.
              </Heading>
              <p className="mt-4 max-w-[600px] text-[17px] leading-relaxed text-label2">
                The full solution is there as a last step, but it costs most of the credit for that challenge.
              </p>
            </div>
          </div>
        </Block>

        {/* progress */}
        <Block muted>
          <Heading align="center" title="See exactly where you stand.">
            Your streak, mastery for every concept, pass rate and time spent, plus every submission and how much it moved your score.
          </Heading>
          <div className="mt-14"><Screenshot name="dashboard" alt="The Codeway dashboard with streak, challenges solved, mastery and an activity calendar" path="codeway.app" /></div>
        </Block>

        {/* curriculum */}
        <Block id="curriculum">
          <Heading title="From OOP to designing whole systems.">
            Each track builds on the one before it, in the order you&apos;d need it on the job or in a design interview.
          </Heading>
          <ol className="mt-14 border-t border-sep">
            {TRACKS.map((t) => (
              <li key={t.n} className="grid gap-2 border-b border-sep py-6 md:grid-cols-[64px_minmax(0,0.9fr)_minmax(0,1.4fr)] md:gap-6">
                <span className="font-mono text-[14px] text-label3">{t.n}</span>
                <div>
                  <p className="text-[19px] font-bold tracking-tight">{t.title}</p>
                  <p className={`mt-1 text-[13px] font-semibold ${t.status === "Practice open" ? "text-accent" : "text-label2"}`}>{t.status}</p>
                </div>
                <p className="text-[15px] leading-relaxed text-label2">{t.topics}</p>
              </li>
            ))}
          </ol>
          <p className="mt-6 text-[14.5px] text-label2">Every lesson is in Java, Python, JavaScript and C++. Practice is open for OOP and SOLID; design patterns and LLD challenges come next.</p>
        </Block>

        {/* closing */}
        <Block muted>
          <div className="flex flex-col items-center text-center">
            <Heading align="center" title="Your first challenge is written when you sign in.">
              Start with Classes &amp; Objects. The difficulty adjusts from your very first submission.
            </Heading>
            <button className="btn btn-primary btn-lg mt-9" onClick={openAuth("register")}>Start free<ArrowRight size={18} /></button>
          </div>
        </Block>
      </main>

      <footer className="border-t border-sep bg-side">
        <div className="mx-auto grid max-w-[1200px] gap-10 px-5 py-14 sm:px-8 md:grid-cols-[1.6fr_1fr_1fr_1fr]">
          <div>
            <Logo height={30} />
            <p className="mt-4 max-w-xs text-[14.5px] leading-relaxed text-label2">Practise software design by writing it, with an AI tutor that adjusts every challenge to you.</p>
          </div>
          <FooterCol title="Product">
            {NAV.map((n) => <li key={n.id}><a href={`#${n.id}`} onClick={goTo(n.id)} className="navlink text-label2 hover:text-label">{n.label}</a></li>)}
          </FooterCol>
          <FooterCol title="Tracks">
            {TRACKS.map((t) => <li key={t.n} className="text-label2">{t.title}{t.status === "Practice open" && <span className="ml-2 text-[12px] font-semibold text-accent">Open</span>}</li>)}
          </FooterCol>
          <FooterCol title="Languages">
            {["Java", "Python", "JavaScript", "C++"].map((l) => <li key={l} className="text-label2">{l}</li>)}
          </FooterCol>
        </div>
        <div className="border-t border-sep">
          <div className="mx-auto flex max-w-[1200px] flex-wrap items-center justify-between gap-3 px-5 py-5 text-[13px] text-label3 sm:px-8">
            <p>© {new Date().getFullYear()} Codeway</p>
            <a href="#top" onClick={goTo("top")} className="inline-flex items-center gap-1.5 font-semibold text-label2 hover:text-label">Back to top<ArrowUp size={14} /></a>
          </div>
        </div>
      </footer>

      {auth && <AuthDialog mode={auth} onClose={() => setAuth(null)} />}
    </div>
  );
}

/* ------------------------------------------------------------------ pieces */

/** Every topic across the tracks; the live ones carry a yellow dot. */
const TOPICS: [string, boolean][] = [
  ["Classes & Objects", true], ["Encapsulation", true], ["Inheritance", true], ["Polymorphism", true], ["Abstraction", true], ["Interfaces", true],
  ["Single Responsibility", true], ["Open/Closed", true], ["Liskov Substitution", true], ["Interface Segregation", true], ["Dependency Inversion", true],
  ["Singleton", false], ["Factory", false], ["Builder", false], ["Strategy", false], ["Observer", false], ["Decorator", false], ["Command", false],
  ["Parking Lot", false], ["Elevator System", false], ["Splitwise", false], ["Rate Limiter", false],
];

/** Slow auto-scrolling band at the foot of the hero: everything you'll build, from OOP to LLD. */
function TopicTicker() {
  const row = (hidden: boolean) => (
    <ul className="flex shrink-0 items-center gap-10 pr-10" aria-hidden={hidden}>
      {TOPICS.map(([t, live]) => (
        <li key={t} className={`flex items-center gap-2.5 whitespace-nowrap text-[15px] font-semibold ${live ? "text-label" : "text-label3"}`}>
          <span className={`h-1.5 w-1.5 rounded-full ${live ? "bg-[var(--accent-fill)]" : "bg-sep"}`} />{t}
        </li>
      ))}
    </ul>
  );
  return (
    <div className="border-t border-sep">
      <div className="mx-auto flex max-w-[1440px] items-center gap-6 px-5 py-5 sm:px-8 xl:px-12">
        <p className="shrink-0 text-[13px] font-semibold text-label2"><span className="text-accent">●</span> Live now <span className="mx-2 text-label3">/</span><span className="text-label3">Coming next</span></p>
        <div className="marquee min-w-0 flex-1 overflow-hidden" aria-label="Topics: OOP and SOLID available now, then design patterns and low-level design">
          <div className="marquee-track">{row(false)}{row(true)}</div>
        </div>
      </div>
    </div>
  );
}

/** Every content section: same vertical rhythm, container width and divider. */
function Block({ id, muted = false, children }: { id?: string; muted?: boolean; children: ReactNode }) {
  return (
    <section id={id} className={`scroll-mt-16 border-t border-sep px-5 py-24 sm:px-8 lg:py-28 ${muted ? "bg-side" : ""}`}>
      <div className="mx-auto max-w-[1200px]">{children}</div>
    </section>
  );
}

/** Section title + supporting line, one style everywhere. */
function Heading({ title, children, align = "left" }: { title: string; children?: ReactNode; align?: "left" | "center" }) {
  const center = align === "center";
  return (
    <div className={center ? "mx-auto max-w-[760px] text-center" : "max-w-[640px]"}>
      <h2 className="text-balance text-[34px] font-bold leading-[1.08] tracking-[-0.035em] sm:text-[46px]">{title}</h2>
      {children && <p className={`mt-5 text-pretty text-[17px] leading-relaxed text-label2 ${center ? "mx-auto max-w-[600px]" : "max-w-[600px]"}`}>{children}</p>}
    </div>
  );
}

/** Real product screenshot in a light browser frame; swaps with the theme. */
function Screenshot({ name, alt, path, priority = false }: { name: string; alt: string; path: string; priority?: boolean }) {
  return (
    <figure className="overflow-hidden rounded-[18px] border border-sep bg-card shadow-[var(--shadow-lg)]">
      <div className="flex h-10 items-center gap-2 border-b border-sep bg-card2 px-4" aria-hidden>
        <span className="h-2.5 w-2.5 rounded-full bg-[#ff5f57]" /><span className="h-2.5 w-2.5 rounded-full bg-[#febc2e]" /><span className="h-2.5 w-2.5 rounded-full bg-[#28c840]" />
        <span className="mx-auto rounded-md bg-fill px-3 py-0.5 font-mono text-[11.5px] text-label3">{path}</span>
        <span className="w-[42px]" />
      </div>
      <Image src={`/home/${name}-light.webp`} alt={alt} width={1440} height={name === "dashboard" ? 700 : 900} className="block h-auto w-full dark:hidden" priority={priority} />
      <Image src={`/home/${name}-dark.webp`} alt={alt} width={1440} height={name === "dashboard" ? 700 : 900} className="hidden h-auto w-full dark:block" priority={priority} />
    </figure>
  );
}

function Point({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div className="border-t-2 border-[var(--accent-fill)] pt-4">
      <h3 className="text-[16px] font-bold">{title}</h3>
      <p className="mt-2 text-[14.5px] leading-relaxed text-label2">{children}</p>
    </div>
  );
}

/** Same look as a hint inside the workspace. */
function HintCard({ level, kind, cost, dim = false, children }: { level: number; kind: string; cost: string; dim?: boolean; children: ReactNode }) {
  return (
    <div className={`rounded-xl border border-sep bg-card p-4 ${dim ? "opacity-70" : ""}`}>
      <div className="mb-2 flex items-center justify-between">
        <span className="chip chip-accent"><Bot size={12} />Level {level} · {kind}</span>
        <span className="text-[11px] text-label3">{cost}</span>
      </div>
      <p className="text-[14.5px] leading-relaxed">{children}</p>
    </div>
  );
}

function FooterCol({ title, children }: { title: string; children: ReactNode }) {
  return (
    <div>
      <p className="mb-4 text-[13px] font-semibold">{title}</p>
      <ul className="space-y-2.5 text-[14.5px]">{children}</ul>
    </div>
  );
}

/* ------------------------------------------------------------------ auth card */

function AuthDialog({ mode, onClose }: { mode: "login" | "register"; onClose: () => void }) {
  const { signInWithGitHub } = useAuth();
  const btnRef = useRef<HTMLButtonElement>(null);
  const [error, setError] = useState("");
  const [busy, setBusy] = useState(false);
  const register = mode === "register";

  useEffect(() => {
    btnRef.current?.focus();
    const esc = (e: KeyboardEvent) => { if (e.key === "Escape") onClose(); };
    const prev = document.body.style.overflow;
    document.body.style.overflow = "hidden";
    window.addEventListener("keydown", esc);
    return () => { window.removeEventListener("keydown", esc); document.body.style.overflow = prev; };
  }, [onClose]);

  async function github() {
    setError(""); setBusy(true);
    // On success the browser leaves for GitHub, so busy stays on until the page changes.
    try { await signInWithGitHub("/dashboard"); }
    catch (err) { setError(err instanceof Error ? err.message : "Couldn't start GitHub sign-in"); setBusy(false); }
  }

  return (
    <div className="fade fixed inset-0 z-[60] grid place-items-center overflow-y-auto bg-black/50 p-4 backdrop-blur-sm"
      onMouseDown={(e) => e.target === e.currentTarget && onClose()} role="dialog" aria-modal="true" aria-labelledby="auth-title">
      <div className="pop relative w-full max-w-[420px] rounded-[24px] border border-sep bg-card p-7 shadow-[var(--shadow-lg)] sm:p-8">
        <button type="button" onClick={onClose} aria-label="Close" className="absolute right-4 top-4 grid h-9 w-9 place-items-center rounded-full text-label3 hover:bg-fill hover:text-label"><X size={18} /></button>
        <h2 id="auth-title" className="text-[26px] font-bold tracking-[-0.02em]">{register ? "Create your account" : "Sign in"}</h2>
        <p className="mb-7 mt-1 text-[14.5px] text-label2">{register ? "Your first challenge is ready a minute from now." : "Pick up where you left off."}</p>

        <button ref={btnRef} type="button" onClick={github} disabled={busy} className="btn btn-primary btn-lg w-full">
          {busy ? <Loader2 size={18} className="animate-spin" /> : <><GitHubMark />Continue with GitHub</>}
        </button>

        {error && <p role="alert" className="mt-4 rounded-xl bg-badsoft px-3.5 py-2.5 text-[13.5px] text-bad">{error}</p>}

        <p className="mt-5 text-center text-[13px] leading-relaxed text-label3">
          {register ? "New here? Your account is created the first time you continue." : "First time? Continuing creates your account."}
          {" "}Codeway reads your GitHub name, email and avatar, nothing else.
        </p>
      </div>
    </div>
  );
}

function GitHubMark() {
  return (
    <svg aria-hidden viewBox="0 0 16 16" width="18" height="18" fill="currentColor">
      <path d="M8 0C3.58 0 0 3.58 0 8c0 3.54 2.29 6.53 5.47 7.59.4.07.55-.17.55-.38 0-.19-.01-.82-.01-1.49-2.01.37-2.53-.49-2.69-.94-.09-.23-.48-.94-.82-1.13-.28-.15-.68-.52-.01-.53.63-.01 1.08.58 1.23.82.72 1.21 1.87.87 2.33.66.07-.52.28-.87.51-1.07-1.78-.2-3.64-.89-3.64-3.95 0-.87.31-1.59.82-2.15-.08-.2-.36-1.02.08-2.12 0 0 .67-.21 2.2.82.64-.18 1.32-.27 2-.27.68 0 1.36.09 2 .27 1.53-1.04 2.2-.82 2.2-.82.44 1.1.16 1.92.08 2.12.51.56.82 1.27.82 2.15 0 3.07-1.87 3.75-3.65 3.95.29.25.54.73.54 1.48 0 1.07-.01 1.93-.01 2.2 0 .21.15.46.55.38A8.013 8.013 0 0016 8c0-4.42-3.58-8-8-8z" />
    </svg>
  );
}
