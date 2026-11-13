/**
 * Anything that wants to hear about a new price.
 *
 * TODO (step 1): make ConsoleDisplay, AlertLogger and TrendAnalyzer implement this interface,
 * so that each one's current show / check / track method becomes onPrice.
 */
public interface PriceListener {
    void onPrice(String symbol, double price);
}
