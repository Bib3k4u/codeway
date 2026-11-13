## The brief

You work on a small stock-price feed. Prices arrive one at a time through `PriceFeed.update(symbol, price)`, and three things react to every price:

- `ConsoleDisplay` shows the price as a line such as `ACME @ 90.0`.
- `AlertLogger` records `ALERT ACME 120.0` when a price is above its threshold (100.0).
- `TrendAnalyzer` remembers the last price of each symbol and reports `ACME UP` or `ACME DOWN` when the next price differs.

It all works today. The trouble is that `PriceFeed` creates these three objects itself and calls each one by name. Last week a teammate asked for a fourth consumer, and it meant editing `PriceFeed` again. Nobody can try a new consumer without touching the feed, and the feed cannot be used without all three.

## What must keep working

`new PriceFeed()` followed by `update(symbol, price)` calls, and `output()` returning the lines produced so far, in the order they were produced. For each update the order is: display line, alert line (if any), trend line (if any). Unchanged prices produce no trend line, and each symbol is tracked on its own. These stay true after every step.

## What you will do

You will reshape the code in four small steps so that the feed no longer knows which consumers exist: first give the consumers a common type, then let the feed hold a list of them, then let a consumer leave, and finally prove that a consumer written elsewhere can plug in without changing existing classes. Each step names the exact types and methods to use.

## Example

Running `Main` prints:

```
ACME @ 90.0
ACME @ 120.0
ALERT ACME 120.0
ACME UP
ZETA @ 40.0
ACME @ 110.0
ALERT ACME 110.0
ACME DOWN
ZETA @ 45.5
ZETA UP
```

Format: every line is added to the feed's `output()` list, and `Main` prints that list one line per entry. Prices print the way Java prints a `double` (`90.0`, `45.5`).
