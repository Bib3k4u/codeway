import java.util.List;

/** Records an alert line for every price that is strictly above its threshold. */
public class AlertLogger {
    private final List<String> output;
    private final double threshold;

    public AlertLogger(List<String> output, double threshold) {
        this.output = output;
        this.threshold = threshold;
    }

    public void check(String symbol, double price) {
        if (price > threshold) {
            output.add("ALERT " + symbol + " " + price);
        }
    }
}
