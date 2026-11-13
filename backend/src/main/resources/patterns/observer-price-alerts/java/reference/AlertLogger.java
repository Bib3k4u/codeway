import java.util.List;

/** Records an alert line for every price that is strictly above its threshold. */
public class AlertLogger implements PriceListener {
    private final List<String> output;
    private final double threshold;

    public AlertLogger(List<String> output, double threshold) {
        this.output = output;
        this.threshold = threshold;
    }

    @Override
    public void onPrice(String symbol, double price) {
        if (price > threshold) {
            output.add("ALERT " + symbol + " " + price);
        }
    }
}
