/** A document that hands every decision to its current status object, so a new status needs no edit here. */
public class Document {
    private final String title;
    private DocumentStatus status;

    public Document(String title) {
        this(title, new DraftStatus());
    }

    public Document(String title, DocumentStatus initial) {
        this.title = title;
        this.status = initial;
    }

    public String title() {
        return title;
    }

    public String status() {
        return status.name();
    }

    public void submit() {
        status = status.submit();
    }

    public void approve() {
        status = status.approve();
    }

    public void reject() {
        status = status.reject();
    }

    public void unpublish() {
        status = status.unpublish();
    }

    public void moveTo(DocumentStatus next) {
        this.status = next;
    }
}
