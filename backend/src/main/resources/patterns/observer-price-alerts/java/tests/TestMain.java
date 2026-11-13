import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

public class TestMain {
    /** A brand-new kind of consumer, defined here and not in the learner's code (used by the step 4 tests). */
    static final class PeakTracker implements PriceListener {
        final Map<String, Double> peaks = new TreeMap<>();

        @Override
        public void onPrice(String symbol, double price) {
            peaks.merge(symbol, price, Math::max);
        }
    }

    /** Casts through Object so this file compiles even before a class implements PriceListener. */
    static PriceListener asListener(Object o, String className) {
        Check.isTrue(o instanceof PriceListener, className + " must implement PriceListener");
        return (PriceListener) o;
    }

    public static void main(String[] args) {
        // ---- Guard: the old behaviour, through new PriceFeed() / update() / output() ----
        Check.run("Guard: a first price below 100 is shown and raises no alert and no trend", () -> {
            PriceFeed feed = new PriceFeed();
            feed.update("ACME", 90.0);
            Check.eq(List.of("ACME @ 90.0"), feed.output());
        });
        Check.run("Guard: prices above 100 raise an alert and a changed price reports UP or DOWN", () -> {
            PriceFeed feed = new PriceFeed();
            feed.update("ACME", 90.0);
            feed.update("ACME", 120.0);
            feed.update("ACME", 110.0);
            Check.eq(List.of("ACME @ 90.0",
                    "ACME @ 120.0", "ALERT ACME 120.0", "ACME UP",
                    "ACME @ 110.0", "ALERT ACME 110.0", "ACME DOWN"), feed.output());
        });
        Check.run("Guard: symbols are tracked separately and an unchanged price has no trend", () -> {
            PriceFeed feed = new PriceFeed();
            feed.update("ACME", 50.0);
            feed.update("ZETA", 60.0);
            feed.update("ZETA", 60.0);
            feed.update("ACME", 40.0);
            Check.eq(List.of("ACME @ 50.0", "ZETA @ 60.0", "ZETA @ 60.0", "ACME @ 40.0", "ACME DOWN"), feed.output());
        });

        // ---- Step 1: the three consumers share the PriceListener type ----
        Check.run("S1: ConsoleDisplay is a PriceListener that shows each price", () -> {
            List<String> out = new ArrayList<>();
            PriceListener l = asListener(new ConsoleDisplay(out), "ConsoleDisplay");
            l.onPrice("ACME", 12.5);
            l.onPrice("ZETA", 3.0);
            Check.eq(List.of("ACME @ 12.5", "ZETA @ 3.0"), out);
        });
        Check.run("S1: AlertLogger is a PriceListener that only reacts to prices strictly above its threshold", () -> {
            List<String> out = new ArrayList<>();
            PriceListener l = asListener(new AlertLogger(out, 100.0), "AlertLogger");
            l.onPrice("ACME", 99.0);
            l.onPrice("ACME", 100.0);
            Check.eq(0, out.size());
            l.onPrice("ACME", 100.5);
            Check.eq(List.of("ALERT ACME 100.5"), out);
        });
        Check.run("S1: TrendAnalyzer is a PriceListener that reports UP and DOWN per symbol", () -> {
            List<String> out = new ArrayList<>();
            PriceListener l = asListener(new TrendAnalyzer(out), "TrendAnalyzer");
            l.onPrice("A", 10.0);
            l.onPrice("A", 12.0);
            l.onPrice("B", 5.0);
            l.onPrice("A", 11.0);
            Check.eq(List.of("A UP", "A DOWN"), out);
        });

        // ---- Step 2: the feed keeps a list of listeners ----
        Check.run("S2: update passes the price to every subscribed listener, in subscription order", () -> {
            PriceFeed feed = PriceFeed.empty();
            List<String> seen = new ArrayList<>();
            feed.subscribe((s, p) -> seen.add("first " + s + " " + p));
            feed.subscribe((s, p) -> seen.add("second " + s + " " + p));
            feed.update("ACME", 10.0);
            feed.update("ZETA", 20.5);
            Check.eq(List.of("first ACME 10.0", "second ACME 10.0", "first ZETA 20.5", "second ZETA 20.5"), seen);
        });
        Check.run("S2: an empty feed accepts updates and says nothing", () -> {
            PriceFeed feed = PriceFeed.empty();
            feed.update("ACME", 500.0);
            Check.eq(0, feed.output().size());
        });
        Check.run("S2: the old consumers can be subscribed one by one, and the default feed accepts extra listeners", () -> {
            PriceFeed custom = PriceFeed.empty();
            custom.subscribe(asListener(new ConsoleDisplay(custom.output()), "ConsoleDisplay"));
            custom.update("ACME", 150.0);
            Check.eq(List.of("ACME @ 150.0"), custom.output());

            PriceFeed standard = new PriceFeed();
            List<String> extra = new ArrayList<>();
            standard.subscribe((s, p) -> extra.add(s + "=" + p));
            standard.update("ACME", 150.0);
            Check.eq(List.of("ACME=150.0"), extra);
            Check.eq(List.of("ACME @ 150.0", "ALERT ACME 150.0"), standard.output());
        });

        // ---- Step 3: unsubscribe ----
        Check.run("S3: an unsubscribed listener stops receiving prices and the others keep receiving them", () -> {
            PriceFeed feed = PriceFeed.empty();
            List<String> seen = new ArrayList<>();
            PriceListener a = (s, p) -> seen.add("A" + p);
            PriceListener b = (s, p) -> seen.add("B" + p);
            feed.subscribe(a);
            feed.subscribe(b);
            feed.update("X", 1.0);
            feed.unsubscribe(a);
            feed.update("X", 2.0);
            Check.eq(List.of("A1.0", "B1.0", "B2.0"), seen);
        });
        Check.run("S3: unsubscribing a listener that was never subscribed changes nothing", () -> {
            PriceFeed feed = PriceFeed.empty();
            List<String> seen = new ArrayList<>();
            feed.subscribe((s, p) -> seen.add("A" + p));
            feed.unsubscribe((s, p) -> { });
            feed.update("X", 1.0);
            Check.eq(List.of("A1.0"), seen);
        });
        Check.run("S3: a listener may unsubscribe itself while being notified, without errors or skipping anyone", () -> {
            PriceFeed feed = PriceFeed.empty();
            List<String> seen = new ArrayList<>();
            PriceListener[] once = new PriceListener[1];
            once[0] = (s, p) -> {
                seen.add("B" + p);
                feed.unsubscribe(once[0]);
            };
            feed.subscribe((s, p) -> seen.add("A" + p));
            feed.subscribe(once[0]);
            feed.subscribe((s, p) -> seen.add("C" + p));
            feed.update("X", 1.0);
            feed.update("X", 2.0);
            Check.eq(List.of("A1.0", "B1.0", "C1.0", "A2.0", "C2.0"), seen);
        });

        // ---- Step 4: a new kind of listener, defined in this file, needs no change to existing classes ----
        Check.run("S4: a brand-new listener defined in the tests receives updates from an empty feed", () -> {
            PriceFeed feed = PriceFeed.empty();
            PeakTracker peaks = new PeakTracker();
            feed.subscribe(peaks);
            feed.update("ACME", 10.0);
            feed.update("ACME", 30.0);
            feed.update("ACME", 20.0);
            feed.update("ZETA", 5.0);
            Check.eq(Map.of("ACME", 30.0, "ZETA", 5.0), peaks.peaks);
            Check.eq(0, feed.output().size());
        });
        Check.run("S4: the same listener also works next to the three default ones, which behave as before", () -> {
            PriceFeed feed = new PriceFeed();
            PeakTracker peaks = new PeakTracker();
            feed.subscribe(peaks);
            feed.update("ACME", 90.0);
            feed.update("ACME", 120.0);
            Check.eq(Map.of("ACME", 120.0), peaks.peaks);
            Check.eq(List.of("ACME @ 90.0", "ACME @ 120.0", "ALERT ACME 120.0", "ACME UP"), feed.output());
            feed.unsubscribe(peaks);
            feed.update("ACME", 200.0);
            Check.eq(Map.of("ACME", 120.0), peaks.peaks);
        });
    }
}
