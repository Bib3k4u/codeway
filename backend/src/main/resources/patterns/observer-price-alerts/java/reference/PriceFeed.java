import java.util.ArrayList;
import java.util.List;

/** Receives price updates and passes them on to everything that subscribed. */
public class PriceFeed {
    private final List<String> output = new ArrayList<>();
    private final List<PriceListener> listeners = new ArrayList<>();

    /** The default setup: the same three consumers as before. */
    public PriceFeed() {
        this(true);
    }

    private PriceFeed(boolean withDefaults) {
        if (withDefaults) {
            subscribe(new ConsoleDisplay(output));
            subscribe(new AlertLogger(output, 100.0));
            subscribe(new TrendAnalyzer(output));
        }
    }

    public static PriceFeed empty() {
        return new PriceFeed(false);
    }

    public List<String> output() {
        return output;
    }

    public void subscribe(PriceListener listener) {
        listeners.add(listener);
    }

    public void unsubscribe(PriceListener listener) {
        listeners.remove(listener);
    }

    public void update(String symbol, double price) {
        // iterate over a copy: a listener may unsubscribe (itself or another one) while it is being called
        for (PriceListener listener : new ArrayList<>(listeners)) {
            listener.onPrice(symbol, price);
        }
    }
}
