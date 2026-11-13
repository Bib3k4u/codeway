## The brief

You work on the checkout of a small online shop. `ShippingCalculator` prices a parcel from its delivery type and its weight in kilograms, and it has worked well for a year. Then marketing asks for a drone delivery, then a pick-up point, then a freight option. Each request means opening `ShippingCalculator`, adding another `case` and hoping nothing else in that method breaks. The team lead has had enough: new delivery types should be addable without touching the code that already works.

## What must keep working

The public entry point stays exactly as it is: `new ShippingCalculator().cost("standard", 2.0)` and the same call for `"express"` and `"overnight"` must return the same prices as today, at every step of your refactoring.

| Type | Price |
| --- | --- |
| `standard` | 5.0 + 1.0 per kg |
| `express` | 10.0 + 2.5 per kg |
| `overnight` | 25.0 + 4.0 per kg |

## What you will do

First you answer a short question about what is wrong with the current design. Then you refactor in four small steps, and each step has its own tests: you give every delivery type its own class, make the calculator use them instead of its `switch`, make an unknown type fail loudly, and finally let a brand-new delivery type be added at runtime without changing any existing class. The last tests define such a new type themselves.

## Example

Running `Main` prints:

```
standard 2.0 kg: 7.0
express 4.0 kg: 20.0
overnight 1.5 kg: 31.0
standard 10.0 kg: 15.0
```

Format: one line per call, `<type> <weight> kg: <price>`, with the price printed as a plain Java `double`. The output must stay identical after your refactoring.
