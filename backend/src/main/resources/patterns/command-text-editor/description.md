## The brief

You work on a tiny text editor. A `Document` holds the text and knows how to `insert` text and `deleteLast` characters. An `EditorToolbar` has the buttons, and each button handler changes the document directly:

- `onTypeHello()` adds `Hello` to the end of the text.
- `onTypeWorld()` adds ` world` (with a leading space).
- `onDeleteWord()` removes the last word together with the space in front of it (the whole text if it is a single word).

It all works today. The trouble is that a button does its work and leaves no trace: the toolbar cannot take anything back, because nobody remembers what was done or what was removed. And every new button (upper-case the text, replace a word, ...) means writing one more method in `EditorToolbar` that reaches into the document in its own way. Last week a teammate asked for an Undo button and found there was nothing to build it from.

## What must keep working

`new EditorToolbar()`, the three button methods above and `text()`, with exactly the results they give now. Pressing delete-word on an empty text does nothing. These stay true after every step.

## What you will do

You will reshape the code in four small steps so that every change to the document is a small object that can be carried out and taken back: first one type for an editing action with two small classes for the existing operations, then a history that runs actions and remembers them, then the toolbar buttons going through that history with a working undo, and finally a brand-new action that needs no edit to any existing class. Each step names the exact types and methods to use.

## Example

Running `Main` prints:

```
"Hello"
"Hello world"
"Hello"
"HelloHello"
""
""
```

Format: after each button press, `Main` prints the current text between double quotes, so an empty text shows as `""`.
