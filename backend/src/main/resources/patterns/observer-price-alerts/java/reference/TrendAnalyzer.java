import java.util.HashMap;
import java.util.List;
import java.util.Map;

/** Remembers the last price of each symbol and reports UP or DOWN when the next one differs. */
public class TrendAnalyzer implements PriceListener {
    private final List<String> output;
    private final Map<String, Double> lastPrice = new HashMap<>();

    public TrendAnalyzer(List<String> output) {
        this.output = output;
    }

    @Override
    public void onPrice(String symbol, double price) {
        Double previous = lastPrice.put(symbol, price);
        if (previous == null || price == previous) {
            return;
        }
        output.add(symbol + (price > previous ? " UP" : " DOWN"));
    }
}
