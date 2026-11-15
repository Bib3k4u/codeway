## The brief

You work on the ordering screen of a small restaurant. A `MealOrder` describes one order: a main dish, a side, a drink, a size (`"S"`, `"M"` or `"L"`), an optional extra sauce, whether it is for takeaway or which table it is for, and a free-text note. `summary()` describes the order in one line and `total()` prices it. Both have worked well for a year.

The trouble is how an order gets created. `MealOrder` has five constructors, from `new MealOrder("Burger")` up to one that takes eight values in a row, and a new option has always meant yet another overload. At the call sites it is hard to see what `true, false, 12` means, and it is easy to swap two arguments of the same type (the side and the drink are both plain strings, and the compiler will not complain). Nothing stops an order without a main dish, and setters let anyone change an order after it was placed. The team lead wants orders that are easy to read, impossible to half-fill by accident, and fixed once created.

## What must keep working

The existing constructors must stay available at every step: `new MealOrder("Burger")`, `new MealOrder("Pizza", "Salad", "Cola")` and the long one with eight arguments must give exactly the same `summary()` and `total()` as today. A missing side, drink or size means `"none"`, `"water"` and `"M"`.

## What you will do

First you answer a short question about what is wrong with the current design. Then you refactor in four small steps, and each step has its own tests: you give `MealOrder` a nested helper class with one clearly named method per value, make it refuse orders that make no sense, give the optional values their defaults, and finally make the finished order unchangeable. The last tests define new ready-made menus of their own, without touching any existing class.

## Example

Running `Main` prints:

```
Burger + none + water (M) = 9.50
Pizza + Salad + Cola (M) = 14.00
Burger + Fries + Cola (L), extra sauce, table 12, note: no onions = 15.00
Salad + none + water (S), takeaway = 6.75
```

Format: one line per order, `<summary> = <total>`, with the total printed with two decimals. The output must stay identical after your refactoring.
