## The brief

You work on the editorial tool of a small publishing team. A `Document` has a title and a status, and it moves through a simple workflow: an author **submits** a draft for review, a reviewer **approves** it (it becomes published) or **rejects** it (back to draft), and a published document can be **unpublished** (back to draft). Any other move is refused with an `IllegalStateException`. It all works today. The trouble is that each of the four operations checks the status text with its own `if`/`else` chain, deciding both what is allowed and what the next status is. Now the team wants an "archived" status, and after that probably "scheduled". Every new status means reopening all four methods and hoping the old rules still hold. The team lead wants a new status to be added without touching the code that already works.

## What must keep working

`new Document("Spec")` starts as `"draft"`, and `submit()`, `approve()`, `reject()`, `unpublish()` and `status()` keep their names and meaning at every step of your refactoring.

| Status | submit | approve | reject | unpublish |
| --- | --- | --- | --- | --- |
| `draft` | review | refused | refused | refused |
| `review` | refused | published | draft | refused |
| `published` | refused | refused | refused | draft |

A refused move throws `IllegalStateException` with the message `Cannot <action> a <status> document` (for example `Cannot approve a draft document`) and leaves the status as it was.

## What you will do

First you answer a short question about what is wrong with the current design. Then you refactor in four small steps, and each step has its own tests: you give every status its own class, let each class refuse the moves it does not allow, make the document hand every operation to its current status, and finally let a brand-new status be plugged in without changing any existing class. The last tests define such a status themselves.

## Example

Running `Main` prints:

```
Release notes is draft
submit: review
approve: published
approve: refused (Cannot approve a published document)
unpublish: draft
reject: refused (Cannot reject a draft document)
```

Format: first the title and its status, then one line per call, `<action>: <new status>` or `<action>: refused (<message>)`. The output must stay identical after your refactoring.
