/** An order with express handling (+7.0). */
public class ExpressOrder extends BasicOrder {
    public ExpressOrder(String item, double basePrice) {
        super(item, basePrice);
    }

    @Override
    public double price() {
        return super.price() + 7.0;
    }

    @Override
    public String description() {
        return super.description() + " + express";
    }
}
