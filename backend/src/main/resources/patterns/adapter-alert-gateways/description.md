## The brief

You work on the alerting part of a small operations tool. `AlertService` sends a short alert text to people, for example "Disk almost full", and keeps a record of the alerts that were delivered:

- `alert(to, message)` sends one alert and returns `true` when it was delivered, `false` when it was not.
- `alertAll(recipients, message)` alerts everyone and returns the recipients it could not reach, in the order they were given.
- `sentLog()` has one line per delivered alert, written as `recipient: message`.

Texts go out through the SMS vendor's library class `LegacySmsGateway`. It is not your code and you cannot change it: its method is called `sendText`, it wants the phone number first, then the text, then a priority number, and it answers with a status code (`0` accepted, `1` invalid phone number, `2` text too long) instead of `true` or `false`. `AlertService` creates that gateway itself and talks to it directly, including the `ALERT: ` prefix and the priority `1`. Now the team wants push notifications from a second vendor, whose class looks completely different again. Adding it would mean editing `AlertService`, and nobody can test `AlertService` without really calling the vendor.

## What must keep working

`new AlertService()` with `alert`, `alertAll` and `sentLog` behaves exactly as it does today: a valid number (starting with `+`) is delivered, anything else is not, and a text the vendor refuses is reported as not delivered and not recorded. These stay true after every step.

## What you will do

You will reshape the code in three small steps so that `AlertService` only knows an interface of its own: first write a class that makes the vendor's gateway usable through that interface, then make `AlertService` depend on the interface (still working with no arguments), and finally prove that a second vendor with a different API, defined in the test file, plugs in without editing any existing class. Each step names the exact types and methods to use.

## Example

Running `Main` prints:

```
alert 1: true
alert 2: false
failed: [bob]
sent: +15550001: Disk almost full
sent: +15550002: Backup done
sent: +15550003: Backup done
```

Format: the first three lines show the results of `alert` and `alertAll` (a list prints as `[bob]`, or `[]` when it is empty), followed by one `sent: ` line for each entry of `sentLog()`.
