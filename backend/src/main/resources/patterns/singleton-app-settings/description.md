## The brief

You work on a small app that shows reports and greets users. Both behaviours depend on the app's settings: a theme, a language and a currency. They come from a block of `key=value` text, and `AppSettings` parses it.

- `ReportService` prints a header such as `== Report (light, en) ==`, formats money such as `12.5 EUR`, and can switch the theme with `useTheme`.
- `GreetingService` says `Hello, Ada!` (or `Bonjour, Ada!`, `Hallo, Ada!` depending on the language) and can switch the language with `useLanguage`.

It all works when you look at one part alone. The trouble shows up when you use two of them together: each service runs `new AppSettings()` itself, so each one has its own copy. When the greeting is switched to French, the report header still says `en`. And every copy parses the settings text again, which is slow work to repeat for every part of the app. Nobody decided how many copies of the settings should exist, and nobody owns that decision.

## What must keep working

`new ReportService()` and `new GreetingService()` with their public methods (`header()`, `money(double)`, `useTheme(String)`, `greet(String)`, `useLanguage(String)`), and `AppSettings.loadCount()`, which tells how many times the settings text has been parsed. A fresh service starts with the theme `light`, the language `en` and the currency `EUR`. These stay true after every step.

## What you will do

You will reshape the code in four small steps so that exactly one `AppSettings` exists and everyone shares it: first let the class hand out one shared instance, then make the services use it, then close the door on extra copies, and finally prove that a class written elsewhere can reach the same settings without changing existing classes. Each step names the exact types and methods to use.

## Example

Running `Main` prints:

```
== Report (light, en) ==
Hello, Ada!
== Report (dark, fr) ==
Bonjour, Ada!
12.5 EUR
Settings loaded 1 time(s)
```

Format: one line per `println`. The header is `== Report (` followed by the theme, a comma and a space, the language and `) ==`. Money is the amount as Java prints a `double` followed by a space and the currency. The last line is the load counter, which is 2 in the starter and must be 1 when you are done.
