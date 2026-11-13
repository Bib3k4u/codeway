A small reporting tool turns a list of rows (plain strings) into text that other programs can read. It supports three formats today: `"csv"`, `"json"` and `"text"`. Callers use one entry point, `ReportService.export(String format, List<String> rows)`, and get back a single `String`.

It works, but the service is awkward to live with. To export a report, `ReportService` itself decides which exporter class to use and creates it. Each new format means opening `ReportService` again, and the service cannot be tested or reused without dragging in every exporter. Next quarter someone wants `"xml"`, and the quarter after that, `"yaml"`.

**What must keep working:** every call like `new ReportService().export("csv", rows)` must return exactly what it returns today, for all three formats, and an unknown format must still throw `IllegalArgumentException` with the message `Unknown format: <format>`.

**What you will do:** first answer a short question about what is wrong with the current design. Then, in four small steps, rework the code so that `ReportService` no longer knows which exporters exist, and finish by adding a brand-new format without touching any existing class. The tests for each step tell you whether you got there.

## Example

`Main` exports the rows `apples`, `pears, ripe` and `figs` in each format and prints:

```
== csv ==
value
apples
"pears, ripe"
figs
== json ==
["apples","pears, ripe","figs"]
== text ==
- apples
- pears, ripe
- figs
```

Format: plain Java with no libraries. Every export is one `String`, lines are separated by `\n`, and there is no trailing newline.
