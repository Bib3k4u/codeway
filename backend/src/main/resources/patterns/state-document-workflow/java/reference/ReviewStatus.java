public class ReviewStatus implements DocumentStatus {
    @Override
    public String name() {
        return "review";
    }

    @Override
    public DocumentStatus submit() {
        throw new IllegalStateException("Cannot submit a review document");
    }

    @Override
    public DocumentStatus approve() {
        return new PublishedStatus();
    }

    @Override
    public DocumentStatus reject() {
        return new DraftStatus();
    }

    @Override
    public DocumentStatus unpublish() {
        throw new IllegalStateException("Cannot unpublish a review document");
    }
}
