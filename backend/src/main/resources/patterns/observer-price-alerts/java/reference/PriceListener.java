/** Anything that wants to hear about a new price. */
public interface PriceListener {
    void onPrice(String symbol, double price);
}
