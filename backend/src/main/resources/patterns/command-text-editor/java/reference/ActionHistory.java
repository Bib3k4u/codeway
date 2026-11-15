import java.util.ArrayDeque;
import java.util.Deque;

/** Runs actions and remembers them so the latest one can be taken back. */
public class ActionHistory {
    private final Deque<EditAction> done = new ArrayDeque<>();

    public void run(EditAction action) {
        action.execute();
        done.push(action);
    }

    public void undo() {
        if (done.isEmpty()) {
            throw new IllegalStateException("Nothing to undo");
        }
        done.pop().undo();
    }

    public boolean canUndo() {
        return !done.isEmpty();
    }
}
