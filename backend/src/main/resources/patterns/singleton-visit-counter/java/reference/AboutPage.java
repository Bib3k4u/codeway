/** The about page: every time it is opened it adds a visit. */
public class AboutPage {
    private final VisitCounter counter = VisitCounter.getInstance();

    public void open() {
        counter.add();
    }

    public int totalVisits() {
        return counter.total();
    }
}
