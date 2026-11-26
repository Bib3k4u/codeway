/** Counts how many times the website's pages were opened. There is only ever one. */
public class VisitCounter {
    private static VisitCounter instance;

    private int count = 0;

    private VisitCounter() { }

    public static VisitCounter getInstance() {
        if (instance == null) {
            instance = new VisitCounter();
        }
        return instance;
    }

    public void add() {
        count++;
    }

    public int total() {
        return count;
    }
}
