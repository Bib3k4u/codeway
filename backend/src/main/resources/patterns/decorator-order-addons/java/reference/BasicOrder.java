/** A plain order for one item: its price and its description. */
public class BasicOrder implements Order {
    private final String item;
    private final double basePrice;

    public BasicOrder(String item, double basePrice) {
        this.item = item;
        this.basePrice = basePrice;
    }

    @Override
    public double price() {
        return basePrice;
    }

    @Override
    public String description() {
        return item;
    }
}
