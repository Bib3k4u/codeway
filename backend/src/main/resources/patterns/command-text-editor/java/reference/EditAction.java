/**
 * One editing operation that can be carried out and taken back.
 *
 * TODO (step 1): make InsertAction and DeleteLastAction implement this interface.
 */
public interface EditAction {
    void execute();

    void undo();
}
