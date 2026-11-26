## The story

You are building a tiny website with two pages: a **Home** page and an **About** page. Every time somebody opens a page, the page adds 1 to a visit counter. The website should be able to say how many visits it had in **total**.

Here is the problem. Look at the code: each page creates its **own** counter with `new VisitCounter()`. So Home counts its visits, About counts its visits, and the two never add up. Open Home twice and About once: the website had 3 visits, but Home says 2 and About says 1.

## What must keep working

`HomePage` and `AboutPage` each have `open()`, which adds a visit, and `totalVisits()`, which tells how many visits were counted. Opening a page must still add exactly one visit.

## What you will do

Three small steps, and each one is checked by a few tests: first let `VisitCounter` keep **one** counter and hand it out, then make both pages use that one counter, then make sure nobody can create another. The Steps tab tells you exactly what to write.

## Example

When you are done, running `Main` prints:

```
Home sees 3 visits
About sees 3 visits
```

Format: one line per page, in the form `<Page> sees <number> visits`. Before you start the program prints 2 for Home and 1 for About.
