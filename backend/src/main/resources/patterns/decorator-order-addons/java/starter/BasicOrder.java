/** A plain order for one item: its price and its description. */
public class BasicOrder {
    private final String item;
    private final double basePrice;

    public BasicOrder(String item, double basePrice) {
        this.item = item;
        this.basePrice = basePrice;
    }

    public double price() {
        return basePrice;
    }

    public String description() {
        return item;
    }
}
