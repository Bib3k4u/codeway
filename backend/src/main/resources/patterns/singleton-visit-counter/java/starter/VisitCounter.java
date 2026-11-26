/** Counts how many times the website's pages were opened. */
public class VisitCounter {
    private int count = 0;

    public void add() {
        count++;
    }

    public int total() {
        return count;
    }

    // TODO (step 1): keep ONE shared counter in a private static field called instance, and build it the first time it is asked for.
    public static VisitCounter getInstance() {
        throw new UnsupportedOperationException("TODO (step 1): return the one shared VisitCounter, creating it on first use");
    }

    // TODO (step 3): once the pages no longer call new VisitCounter(), add a private constructor so nobody else can.
}
