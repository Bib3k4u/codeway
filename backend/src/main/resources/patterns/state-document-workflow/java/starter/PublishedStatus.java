public class PublishedStatus implements DocumentStatus {
    // TODO (step 1): name() is "published"; return the next status for the moves this phase allows (see the matching branch in Document)
    // TODO (step 2): refuse every other move with IllegalStateException("Cannot <action> a published document")
    @Override
    public String name() {
        throw new UnsupportedOperationException("TODO (step 1)");
    }

    @Override
    public DocumentStatus submit() {
        throw new UnsupportedOperationException("TODO (step 1)");
    }

    @Override
    public DocumentStatus approve() {
        throw new UnsupportedOperationException("TODO (step 1)");
    }

    @Override
    public DocumentStatus reject() {
        throw new UnsupportedOperationException("TODO (step 1)");
    }

    @Override
    public DocumentStatus unpublish() {
        throw new UnsupportedOperationException("TODO (step 1)");
    }
}
