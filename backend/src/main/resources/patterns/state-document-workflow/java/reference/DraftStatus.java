public class DraftStatus implements DocumentStatus {
    @Override
    public String name() {
        return "draft";
    }

    @Override
    public DocumentStatus submit() {
        return new ReviewStatus();
    }

    @Override
    public DocumentStatus approve() {
        throw new IllegalStateException("Cannot approve a draft document");
    }

    @Override
    public DocumentStatus reject() {
        throw new IllegalStateException("Cannot reject a draft document");
    }

    @Override
    public DocumentStatus unpublish() {
        throw new IllegalStateException("Cannot unpublish a draft document");
    }
}
