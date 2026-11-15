/** Adds text to the end of a document, and can take it out again. */
public class InsertAction implements EditAction {
    private final Document document;
    private final String text;

    public InsertAction(Document document, String text) {
        this.document = document;
        this.text = text;
    }

    @Override
    public void execute() {
        document.insert(text);
    }

    @Override
    public void undo() {
        document.deleteLast(text.length());
    }
}
