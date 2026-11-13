/** An order that is gift wrapped (+3.0). */
public class GiftWrappedOrder extends BasicOrder {
    public GiftWrappedOrder(String item, double basePrice) {
        super(item, basePrice);
    }

    @Override
    public double price() {
        return super.price() + 3.0;
    }

    @Override
    public String description() {
        return super.description() + " + gift wrap";
    }
}
