import java.util.List;

/** Shows every price it is given as one line of output. */
public class ConsoleDisplay implements PriceListener {
    private final List<String> output;

    public ConsoleDisplay(List<String> output) {
        this.output = output;
    }

    @Override
    public void onPrice(String symbol, double price) {
        output.add(symbol + " @ " + price);
    }
}
