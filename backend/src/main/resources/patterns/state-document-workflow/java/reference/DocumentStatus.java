/**
 * What a document can do while it is in one particular status. Each operation returns the status the document
 * moves to, or refuses the move by throwing IllegalStateException.
 */
public interface DocumentStatus {
    String name();

    DocumentStatus submit();

    DocumentStatus approve();

    DocumentStatus reject();

    DocumentStatus unpublish();
}
