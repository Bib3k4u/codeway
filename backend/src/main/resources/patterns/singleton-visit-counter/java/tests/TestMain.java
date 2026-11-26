import java.lang.reflect.Constructor;

public class TestMain {
    /** A brand-new page, written here and not in the learner's code: it reaches the counter through getInstance() like any other part of the site. */
    static final class ContactPage {
        void open() {
            VisitCounter.getInstance().add();
        }
    }

    public static void main(String[] args) {
        // ---- Guard: what a page did before, through the pages' own methods. Every check counts RELATIVE to the start, so it holds with one counter or many ----
        Check.run("Guard: opening the home page adds one visit", () -> {
            HomePage home = new HomePage();
            int before = home.totalVisits();
            home.open();
            Check.eq(before + 1, home.totalVisits());
        });
        Check.run("Guard: opening the home page three times adds three visits", () -> {
            HomePage home = new HomePage();
            int before = home.totalVisits();
            home.open();
            home.open();
            home.open();
            Check.eq(before + 3, home.totalVisits());
        });
        Check.run("Guard: opening the about page adds one visit", () -> {
            AboutPage about = new AboutPage();
            int before = about.totalVisits();
            about.open();
            Check.eq(before + 1, about.totalVisits());
        });

        // ---- Step 1: VisitCounter keeps one counter and hands it out ----
        Check.run("S1: getInstance gives back the same counter every time", () -> {
            VisitCounter a = VisitCounter.getInstance();
            VisitCounter b = VisitCounter.getInstance();
            Check.isTrue(a != null, "getInstance() returned nothing (null). It should create the counter the first time and return it.");
            Check.isTrue(a == b, "getInstance() gave two different counters. It should keep one in the instance field and return that same one every time.");
        });
        Check.run("S1: visits added through one getInstance() call show up in the next one", () -> {
            int before = VisitCounter.getInstance().total();
            VisitCounter.getInstance().add();
            VisitCounter.getInstance().add();
            Check.eq(before + 2, VisitCounter.getInstance().total());
        });

        // ---- Step 2: both pages use that one counter ----
        Check.run("S2: Home and About now add to the same counter", () -> {
            HomePage home = new HomePage();
            AboutPage about = new AboutPage();
            int before = home.totalVisits();
            home.open();
            about.open();
            Check.isTrue(home.totalVisits() == before + 2,
                    "After one visit to Home and one to About, Home should count 2 more visits, but it counted " + (home.totalVisits() - before)
                            + ". Does HomePage still say new VisitCounter()? It should use VisitCounter.getInstance().");
            Check.isTrue(about.totalVisits() == before + 2,
                    "About should see the same total as Home, but it counted " + (about.totalVisits() - before) + " more. Does AboutPage still say new VisitCounter()?");
        });
        Check.run("S2: a page created later sees the visits of the pages before it", () -> {
            new HomePage().open();
            AboutPage late = new AboutPage();
            int seen = late.totalVisits();
            new HomePage().open();
            Check.isTrue(late.totalVisits() == seen + 1, "A visit to a new Home page should show up on the About page too, because they share one counter.");
        });
        Check.run("S2: the pages and VisitCounter.getInstance() show the same total", () -> {
            HomePage home = new HomePage();
            int before = VisitCounter.getInstance().total();
            home.open();
            Check.isTrue(VisitCounter.getInstance().total() == before + 1, "A visit to Home should be counted in VisitCounter.getInstance() too: the page must use that very counter.");
        });

        // ---- Step 3: nobody else can make a counter, and new parts just use getInstance ----
        Check.run("S3: nobody outside VisitCounter can create a counter with new", () -> {
            Constructor<?>[] all = VisitCounter.class.getDeclaredConstructors();
            for (Constructor<?> c : all) {
                Check.isTrue(java.lang.reflect.Modifier.isPrivate(c.getModifiers()),
                        "VisitCounter still has a constructor that is not private. Add: private VisitCounter() { }");
            }
        });
        Check.run("S3: the pages still count visits", () -> {
            HomePage home = new HomePage();
            AboutPage about = new AboutPage();
            int before = about.totalVisits();
            home.open();
            about.open();
            Check.isTrue(about.totalVisits() == before + 2,
                    "After one visit to Home and one to About, the total should go up by 2, but it went up by " + (about.totalVisits() - before) + ". Both pages must use VisitCounter.getInstance().");
        });
        Check.run("S3: a page written later can use the same counter", () -> {
            HomePage home = new HomePage();
            ContactPage contact = new ContactPage();
            int before = home.totalVisits();
            contact.open();
            Check.isTrue(home.totalVisits() == before + 1, "A visit to the new Contact page should be counted by the same counter that Home uses.");
        });
    }
}
