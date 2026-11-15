public class PublishedStatus implements DocumentStatus {
    @Override
    public String name() {
        return "published";
    }

    @Override
    public DocumentStatus submit() {
        throw new IllegalStateException("Cannot submit a published document");
    }

    @Override
    public DocumentStatus approve() {
        throw new IllegalStateException("Cannot approve a published document");
    }

    @Override
    public DocumentStatus reject() {
        throw new IllegalStateException("Cannot reject a published document");
    }

    @Override
    public DocumentStatus unpublish() {
        return new DraftStatus();
    }
}
