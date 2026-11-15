/** The buttons of the editor. Every button works, but each one reaches into the document by itself. */
public class EditorToolbar {
    private final Document document = new Document();

    public Document document() {
        return document;
    }

    public String text() {
        return document.text();
    }

    // TODO (step 3): build an InsertAction / DeleteLastAction in each of the three methods below and
    // run it through an ActionHistory, instead of changing the document directly.
    public void onTypeHello() {
        document.insert("Hello");
    }

    public void onTypeWorld() {
        document.insert(" world");
    }

    /** Deletes the last word, together with the space in front of it (the whole text if it is a single word). */
    public void onDeleteWord() {
        String current = document.text();
        int space = current.lastIndexOf(' ');
        int count = current.length() - (space < 0 ? 0 : space);
        document.deleteLast(count);
    }

    // TODO (step 3): take back the most recent change; return false when there is nothing to undo
    public boolean onUndo() {
        throw new UnsupportedOperationException("TODO (step 3)");
    }

    // TODO (step 4): carry out any EditAction through the same history, so onUndo can take it back too
    public void run(EditAction action) {
        throw new UnsupportedOperationException("TODO (step 4)");
    }
}
