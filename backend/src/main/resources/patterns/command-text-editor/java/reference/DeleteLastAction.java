/** Removes the last characters of a document, remembering what it removed so that undo can put it back. */
public class DeleteLastAction implements EditAction {
    private final Document document;
    private final int count;
    private String removed = "";

    public DeleteLastAction(Document document, int count) {
        this.document = document;
        this.count = count;
    }

    @Override
    public void execute() {
        removed = document.deleteLast(count);
    }

    @Override
    public void undo() {
        document.insert(removed);
    }
}
