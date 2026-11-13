# Guided design-pattern scenarios

Each folder here is one hand-written **anchor scenario**. The server validates it at startup (like AI output), serves it to
learners, translates it into the other languages on demand, and uses it as the model when the AI writes fresh variants.

## How a learner experiences one

1. They read a short brief and **working code with a design problem** (it breaks a SOLID principle). The brief never names the pattern.
2. **Diagnose:** "which principle does this break?" (five options; wrong twice shows the answer, so nobody is stuck).
3. **Refactor in 3-4 small steps.** Each step has its own tests. The old behaviour must keep working throughout ("Guard:" tests).
4. The last step adds a **brand-new variant defined inside the test file**, proving the design is open for extension.
5. Only after all tests pass: the **reveal** names the pattern, maps its roles to the learner's classes, and links the lesson.

## Folder layout

```
patterns/<id>/
  scenario.json
  description.md
  java/starter/*.java      working code with the problem + empty stubs for the NEW types (so everything compiles)
  java/reference/*.java    the finished design: EVERY file, complete
  java/tests/TestMain.java hidden tests
```

Only `java/` is hand-written; other languages are translated on demand. `<id>` is lower-kebab, e.g. `strategy-shipping`.

## scenario.json

```json
{
  "id": "strategy-shipping",
  "concept": "strategy",                      // must be one of ConceptCatalog.GUIDED
  "title": "Shipping costs that keep growing",  // scenario-based; never contains the pattern's name
  "difficulty": 2,                            // 1-5
  "scaffolding": "HEAVY",                     // HEAVY: stubs have signatures; PARTIAL: empty types; BLANK: names only in the brief
  "expectedMinutes": 25,
  "hints": ["a guiding question", "the key idea WITHOUT naming the pattern", "a small partial snippet"],
  "guide": {
    "quiz": { "question": "Which principle does this code break most clearly?", "explanation": "3-4 sentences, pointing at the exact class/lines." },
    "steps": [ { "title": "Short step title", "md": "Markdown: exactly what to do, with the exact names to use." } ],
    "reveal": { "pattern": "Strategy", "principle": "Open/Closed",
                "roles": [ { "name": "Context", "is": "ShippingCalculator" } ],
                "summary": "2-3 sentences: what it is, what it bought you, and when NOT to use it." }
  }
}
```

The quiz options (the five SOLID principles) and the answer key are added by the server; do not write them.

## description.md

Story, what must keep working, what the learner will do at a high level, a `## Example` block with the **exact** output of `Main`,
and a short `Format:` note. At least ~120 words. **Do not name the pattern, and do not use the word "pattern".**

## Tests (`java/tests/TestMain.java`)

Plain Java, default package: `public class TestMain { public static void main(String[] args) { ... } }`, using the provided helper:
`Check.run("name", () -> { ... })`, `Check.eq(expected, actual)`, `Check.eq(double, double, eps)`, `Check.isTrue(cond, "message")`,
`Check.throwsEx(SomeException.class, () -> { ... })`. Nothing else exists. Deterministic: no randomness, time, threads or stdin.

Test names are what drives the steps:

- `Guard: ...`  2-3 tests of the **old behaviour** through the **old public API**. They pass on the starter AND on the reference. They must
  keep compiling at every step, so the old API stays available in the final design.
- `S1: ...`, `S2: ...` ...  tests for step N (1-3 each). Step N's tests may only need what step N's `md` asks for (exact class and method names).
- The **last** step defines a new variant (a rule, listener, product or wrapper) **inside TestMain** and checks it works without changing existing classes.

## The starter

Working, realistic code (about 40-120 lines in total) with ONE dominant design problem, plus an empty stub for every new type the step tests
reference, each with a `// TODO (step N): ...` comment, so it compiles and runs from the start. `Main.java` demonstrates the old behaviour with
deterministic output and stays valid after the refactor.

## Check it

```
cd backend && mvn -q test -Dtest=PatternScenariosTest -Dscenario=<id>
```

This runs exactly what the server runs at startup: the reference compiles and passes every test; the starter compiles, passes every `Guard:` test and
still fails step 1; the guide is well-formed; the brief does not name the pattern. Also check by hand that a partial solution behaves: finishing
only step 1 should pass `Guard:` and `S1:` but not `S2:`.
