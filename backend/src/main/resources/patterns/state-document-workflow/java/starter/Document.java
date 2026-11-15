/**
 * Works the same as it always has. Every operation decides what is allowed by looking at the status text,
 * so a new status means opening every method again.
 */
public class Document {
    private final String title;
    private String status = "draft";

    public Document(String title) {
        this.title = title;
    }

    // TODO (step 3): start in the given status object instead of the plain text "draft"
    public Document(String title, DocumentStatus initial) {
        this.title = title;
    }

    public String title() {
        return title;
    }

    // TODO (step 3): return the name of the current status object
    public String status() {
        return status;
    }

    // TODO (step 3): delegate to the current status object instead of this if/else chain
    public void submit() {
        if (status.equals("draft")) {
            status = "review";
        } else if (status.equals("review")) {
            throw new IllegalStateException("Cannot submit a review document");
        } else if (status.equals("published")) {
            throw new IllegalStateException("Cannot submit a published document");
        }
    }

    // TODO (step 3): delegate to the current status object
    public void approve() {
        if (status.equals("draft")) {
            throw new IllegalStateException("Cannot approve a draft document");
        } else if (status.equals("review")) {
            status = "published";
        } else if (status.equals("published")) {
            throw new IllegalStateException("Cannot approve a published document");
        }
    }

    // TODO (step 3): delegate to the current status object
    public void reject() {
        if (status.equals("draft")) {
            throw new IllegalStateException("Cannot reject a draft document");
        } else if (status.equals("review")) {
            status = "draft";
        } else if (status.equals("published")) {
            throw new IllegalStateException("Cannot reject a published document");
        }
    }

    // TODO (step 3): delegate to the current status object
    public void unpublish() {
        if (status.equals("draft")) {
            throw new IllegalStateException("Cannot unpublish a draft document");
        } else if (status.equals("review")) {
            throw new IllegalStateException("Cannot unpublish a review document");
        } else if (status.equals("published")) {
            status = "draft";
        }
    }

    // TODO (step 4): put the document into the given status object, whatever kind it is
    public void moveTo(DocumentStatus next) {
        throw new UnsupportedOperationException("TODO (step 4)");
    }
}
