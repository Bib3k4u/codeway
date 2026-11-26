/** The home page: every time it is opened it adds a visit. */
public class HomePage {
    // TODO (step 2): get the counter from VisitCounter.getInstance() instead of making a private one.
    private final VisitCounter counter = new VisitCounter();

    public void open() {
        counter.add();
    }

    public int totalVisits() {
        return counter.total();
    }
}
