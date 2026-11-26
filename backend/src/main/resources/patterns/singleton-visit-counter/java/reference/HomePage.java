/** The home page: every time it is opened it adds a visit. */
public class HomePage {
    private final VisitCounter counter = VisitCounter.getInstance();

    public void open() {
        counter.add();
    }

    public int totalVisits() {
        return counter.total();
    }
}
