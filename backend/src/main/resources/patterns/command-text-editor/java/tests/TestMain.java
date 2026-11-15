import java.util.ArrayList;
import java.util.List;

public class TestMain {
    /** An action that only writes into a log, so the history can be tested without any document (used by the step 2 tests). */
    static final class LogAction implements EditAction {
        private final String name;
        private final List<String> log;

        LogAction(String name, List<String> log) {
            this.name = name;
            this.log = log;
        }

        @Override
        public void execute() {
            log.add("do " + name);
        }

        @Override
        public void undo() {
            log.add("undo " + name);
        }
    }

    /** A brand-new editing action, defined here and not in the learner's code (used by the step 4 tests). */
    static final class UpperCaseAction implements EditAction {
        private final Document document;
        private String before = "";

        UpperCaseAction(Document document) {
            this.document = document;
        }

        @Override
        public void execute() {
            before = document.text();
            document.setText(before.toUpperCase());
        }

        @Override
        public void undo() {
            document.setText(before);
        }
    }

    /** Casts through Object so this file compiles even before a class implements EditAction. */
    static EditAction asAction(Object o, String className) {
        Check.isTrue(o instanceof EditAction, className + " must implement EditAction");
        return (EditAction) o;
    }

    public static void main(String[] args) {
        // ---- Guard: the old behaviour, through the toolbar buttons and text() ----
        Check.run("Guard: the type buttons add Hello and ' world' to the end of the text", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            Check.eq("", toolbar.text());
            toolbar.onTypeHello();
            Check.eq("Hello", toolbar.text());
            toolbar.onTypeWorld();
            Check.eq("Hello world", toolbar.text());
            toolbar.onTypeHello();
            Check.eq("Hello worldHello", toolbar.text());
        });
        Check.run("Guard: the delete-word button removes the last word and the space before it", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            toolbar.onTypeHello();
            toolbar.onTypeWorld();
            toolbar.onTypeWorld();
            Check.eq("Hello world world", toolbar.text());
            toolbar.onDeleteWord();
            Check.eq("Hello world", toolbar.text());
            toolbar.onDeleteWord();
            Check.eq("Hello", toolbar.text());
            toolbar.onDeleteWord();
            Check.eq("", toolbar.text());
        });
        Check.run("Guard: the delete-word button on an empty text does nothing", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            toolbar.onDeleteWord();
            Check.eq("", toolbar.text());
            toolbar.onTypeHello();
            Check.eq("Hello", toolbar.text());
        });

        // ---- Step 1: EditAction with InsertAction and DeleteLastAction ----
        Check.run("S1: InsertAction adds its text on execute and removes exactly that text on undo", () -> {
            Document doc = new Document();
            doc.insert("Hi");
            EditAction action = asAction(new InsertAction(doc, " there"), "InsertAction");
            action.execute();
            Check.eq("Hi there", doc.text());
            action.undo();
            Check.eq("Hi", doc.text());
        });
        Check.run("S1: DeleteLastAction removes the last characters on execute and puts them back on undo", () -> {
            Document doc = new Document();
            doc.insert("Hello world");
            EditAction action = asAction(new DeleteLastAction(doc, 5), "DeleteLastAction");
            action.execute();
            Check.eq("Hello ", doc.text());
            action.undo();
            Check.eq("Hello world", doc.text());
        });
        Check.run("S1: DeleteLastAction asked for more than the text holds removes only what exists, and undo restores just that", () -> {
            Document doc = new Document();
            doc.insert("abc");
            EditAction action = asAction(new DeleteLastAction(doc, 10), "DeleteLastAction");
            action.execute();
            Check.eq("", doc.text());
            action.undo();
            Check.eq("abc", doc.text());
        });

        // ---- Step 2: ActionHistory ----
        Check.run("S2: run executes the action and undo reverses it", () -> {
            List<String> log = new ArrayList<>();
            ActionHistory history = new ActionHistory();
            history.run(new LogAction("A", log));
            Check.eq(List.of("do A"), log);
            history.undo();
            Check.eq(List.of("do A", "undo A"), log);
        });
        Check.run("S2: undo goes back through the actions, latest first, and never undoes one twice", () -> {
            List<String> log = new ArrayList<>();
            ActionHistory history = new ActionHistory();
            history.run(new LogAction("A", log));
            history.run(new LogAction("B", log));
            history.run(new LogAction("C", log));
            history.undo();
            history.undo();
            Check.eq(List.of("do A", "do B", "do C", "undo C", "undo B"), log);
            history.run(new LogAction("D", log));
            history.undo();
            history.undo();
            Check.eq(List.of("do A", "do B", "do C", "undo C", "undo B", "do D", "undo D", "undo A"), log);
        });
        Check.run("S2: canUndo reports whether anything is left to take back", () -> {
            ActionHistory history = new ActionHistory();
            Check.isTrue(!history.canUndo(), "a new history has nothing to undo");
            history.run(new LogAction("A", new ArrayList<>()));
            Check.isTrue(history.canUndo(), "one action can be undone");
            history.undo();
            Check.isTrue(!history.canUndo(), "nothing is left after undoing it");
        });
        Check.run("S2: undo with nothing to undo throws IllegalStateException(\"Nothing to undo\")", () -> {
            ActionHistory history = new ActionHistory();
            try {
                history.undo();
                Check.isTrue(false, "expected an IllegalStateException");
            } catch (IllegalStateException e) {
                Check.eq("Nothing to undo", e.getMessage());
            }
            history.run(new LogAction("A", new ArrayList<>()));
            history.undo();
            Check.throwsEx(IllegalStateException.class, history::undo);
        });

        // ---- Step 3: the toolbar goes through the history ----
        Check.run("S3: onUndo takes back the latest typing, one button press at a time", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            toolbar.onTypeHello();
            toolbar.onTypeWorld();
            Check.isTrue(toolbar.onUndo(), "there was something to undo");
            Check.eq("Hello", toolbar.text());
            Check.isTrue(toolbar.onUndo(), "there was something to undo");
            Check.eq("", toolbar.text());
        });
        Check.run("S3: onUndo returns false and changes nothing when there is nothing to undo", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            Check.isTrue(!toolbar.onUndo(), "a fresh toolbar has nothing to undo");
            toolbar.onTypeHello();
            Check.isTrue(toolbar.onUndo(), "typing can be undone");
            Check.isTrue(!toolbar.onUndo(), "and only once");
            Check.eq("", toolbar.text());
        });
        Check.run("S3: undoing a delete-word brings the removed word back", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            toolbar.onTypeHello();
            toolbar.onTypeWorld();
            toolbar.onDeleteWord();
            Check.eq("Hello", toolbar.text());
            toolbar.onUndo();
            Check.eq("Hello world", toolbar.text());
            toolbar.onDeleteWord();
            toolbar.onDeleteWord();
            Check.eq("", toolbar.text());
            toolbar.onUndo();
            Check.eq("Hello", toolbar.text());
            toolbar.onUndo();
            Check.eq("Hello world", toolbar.text());
        });
        Check.run("S3: a delete-word on an empty text is not remembered, so undo still reaches the earlier change", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            toolbar.onTypeHello();
            toolbar.onDeleteWord();
            toolbar.onDeleteWord();
            Check.eq("", toolbar.text());
            toolbar.onUndo();
            Check.eq("Hello", toolbar.text());
        });

        // ---- Step 4: a new action defined in this file, no change to existing classes ----
        Check.run("S4: a new UpperCaseAction runs and undoes through the toolbar's history", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            toolbar.onTypeHello();
            toolbar.onTypeWorld();
            toolbar.run(new UpperCaseAction(toolbar.document()));
            Check.eq("HELLO WORLD", toolbar.text());
            Check.isTrue(toolbar.onUndo(), "the new action can be undone");
            Check.eq("Hello world", toolbar.text());
        });
        Check.run("S4: new and built-in actions undo in the reverse of the order they ran", () -> {
            EditorToolbar toolbar = new EditorToolbar();
            toolbar.onTypeHello();
            toolbar.run(new UpperCaseAction(toolbar.document()));
            toolbar.onTypeWorld();
            Check.eq("HELLO world", toolbar.text());
            toolbar.onUndo();
            Check.eq("HELLO", toolbar.text());
            toolbar.onUndo();
            Check.eq("Hello", toolbar.text());
            toolbar.onUndo();
            Check.eq("", toolbar.text());
            Check.isTrue(!toolbar.onUndo(), "everything has been undone");
        });
        Check.run("S4: the new action also works with a plain ActionHistory on its own document", () -> {
            Document doc = new Document();
            ActionHistory history = new ActionHistory();
            history.run(asAction(new InsertAction(doc, "abc"), "InsertAction"));
            history.run(new UpperCaseAction(doc));
            history.run(asAction(new DeleteLastAction(doc, 1), "DeleteLastAction"));
            Check.eq("AB", doc.text());
            history.undo();
            Check.eq("ABC", doc.text());
            history.undo();
            Check.eq("abc", doc.text());
            history.undo();
            Check.eq("", doc.text());
        });
    }
}
