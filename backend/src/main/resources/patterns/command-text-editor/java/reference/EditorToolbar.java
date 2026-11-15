/** The buttons of the editor. Every change goes through the history, so it can be taken back. */
public class EditorToolbar {
    private final Document document = new Document();
    private final ActionHistory history = new ActionHistory();

    public Document document() {
        return document;
    }

    public String text() {
        return document.text();
    }

    public void onTypeHello() {
        history.run(new InsertAction(document, "Hello"));
    }

    public void onTypeWorld() {
        history.run(new InsertAction(document, " world"));
    }

    /** Deletes the last word, together with the space in front of it (the whole text if it is a single word). */
    public void onDeleteWord() {
        String current = document.text();
        int space = current.lastIndexOf(' ');
        int count = current.length() - (space < 0 ? 0 : space);
        if (count > 0) {
            history.run(new DeleteLastAction(document, count));
        }
    }

    public boolean onUndo() {
        if (!history.canUndo()) {
            return false;
        }
        history.undo();
        return true;
    }

    public void run(EditAction action) {
        history.run(action);
    }
}
