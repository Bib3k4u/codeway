## The problem

A small shop prices its orders in code. A `BasicOrder` is one item with a price, and customers can add extras to it: **gift wrap** (+3.0), **express handling** (+7.0), and **insurance** (+2.5). Every order can report its `price()` and its `description()`, for example `Book + gift wrap + express`.

Look at how the code handles the extras today: there is a separate subclass for every combination that has come up so far (`GiftWrappedOrder`, `ExpressOrder`, `GiftWrappedExpressOrder`, `InsuredOrder`, `InsuredExpressOrder`), and each one repeats the charges and the description text of the others. Marketing now wants a fourth extra, and then a fifth. Count what that does to the number of classes.

## What must keep working

A `BasicOrder` still gives the price and the description it was created with. `Main` must keep printing the same four lines, but without needing a class per combination.

## What you will do

You will give all orders one common type, build the extras so that any of them can be added to any order, in any order and as many times as you like, and finally prove it by adding a brand-new extra without touching your existing classes. Then the five combination classes can go.

Work through the steps one by one. The tests for each step only need what that step asks for.

## Example

Running `Main` prints:

```
Book = 12.0
Book + gift wrap = 15.0
Book + express = 19.0
Lamp + gift wrap + express = 40.0
```

Format: one line per order, `<description> = <price>`, with the price printed as a plain Java `double` (`12.0`, `15.0`). Extras appear in the description in the order they were added, separated by ` + `.
