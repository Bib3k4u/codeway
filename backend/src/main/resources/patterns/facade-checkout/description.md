## The brief

You work on a small shop that takes orders in several ways. Placing an order is always the same four-part job on four helper classes:

- `Inventory.reserve(sku, qty)` takes the items out of stock (and throws `Out of stock: SKU` when there are not enough).
- `PaymentGateway.charge(account, cents)` takes the money (and throws `Payment declined for ACCOUNT` for the account named `EMPTY`).
- `ShippingService.schedule(address, sku)` books the delivery.
- `Receipts.issue(account, totalCents)` writes the receipt and returns its id, such as `R-1`.

It all works today. The trouble is that `WebCheckout`, `PhoneCheckout` and `Main` each repeat those four calls in the same order, and each one creates and knows all four helpers. The same rules are copied three times, and the copies have drifted: `WebCheckout` gives the reserved items back when the payment is declined, but `PhoneCheckout` forgot to, so every declined phone order quietly loses stock. Nobody wants to touch checkout again because every change has to be repeated everywhere.

## What must keep working

`WebCheckout.submit(sku, qty, account, address)` and `PhoneCheckout.takeOrder(sku, qty, account, address)` keep their signatures and return the same messages as today (see the example), and `stockLeft(sku)` keeps reporting the stock. Stock is `BOOK` 5, `LAMP` 2 and `DESK` 1 in every new checkout. These stay true after every step.

## What you will do

You will reshape the code in four small steps so that the callers stop knowing the helpers: first write one class that places an order, then make it undo the reservation when payment fails, then let the existing callers use it, and finally prove that it works with helpers written elsewhere that record how they are called. Each step names the exact types and methods to use.

## Example

Running `Main` prints:

```
WEB OK R-1
WEB FAILED Payment declined for EMPTY
web stock BOOK: 3
PHONE: confirmed R-1
PHONE: sorry, Out of stock: LAMP
PHONE: sorry, Payment declined for EMPTY
STAFF OK R-1
```

Format: one line per order, as returned by `submit` or `takeOrder` (or `STAFF OK` plus the receipt id for the staff order in `Main`), followed by one line with the stock left of `BOOK` after the web orders.
