import java.util.ArrayList;
import java.util.List;

/** Receives price updates and passes them on to everything that cares. */
public class PriceFeed {
    private final List<String> output = new ArrayList<>();

    private final ConsoleDisplay display = new ConsoleDisplay(output);
    private final AlertLogger alerts = new AlertLogger(output, 100.0);
    private final TrendAnalyzer trends = new TrendAnalyzer(output);

    public List<String> output() {
        return output;
    }

    public void update(String symbol, double price) {
        display.show(symbol, price);
        alerts.check(symbol, price);
        trends.track(symbol, price);
    }

    // TODO (step 2): keep a list of PriceListener objects. update() must pass each price to all of them,
    // in the order they were subscribed, and "new PriceFeed()" must keep behaving exactly as it does today.
    public static PriceFeed empty() {
        throw new UnsupportedOperationException("TODO (step 2): a feed that starts with no listeners");
    }

    public void subscribe(PriceListener listener) {
        throw new UnsupportedOperationException("TODO (step 2): remember the listener");
    }

    // TODO (step 3): stop sending prices to a listener, even when it asks to be removed while a price is being delivered.
    public void unsubscribe(PriceListener listener) {
        throw new UnsupportedOperationException("TODO (step 3): forget the listener");
    }
}
