import java.util.List;

/** Shows every price it is given as one line of output. */
public class ConsoleDisplay {
    private final List<String> output;

    public ConsoleDisplay(List<String> output) {
        this.output = output;
    }

    public void show(String symbol, double price) {
        output.add(symbol + " @ " + price);
    }
}
