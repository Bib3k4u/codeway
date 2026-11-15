/**
 * TODO (step 2): runs actions and remembers them so the latest one can be taken back.
 * run(action) executes it and remembers it; undo() reverses the most recent one that has not been undone yet.
 */
public class ActionHistory {
    public void run(EditAction action) {
        throw new UnsupportedOperationException("TODO (step 2): execute the action and remember it");
    }

    public void undo() {
        throw new UnsupportedOperationException("TODO (step 2): reverse the latest action; throw IllegalStateException(\"Nothing to undo\") when there is none");
    }

    public boolean canUndo() {
        throw new UnsupportedOperationException("TODO (step 2): true when at least one action can be undone");
    }
}
