/** An order that is gift wrapped (+3.0) and has express handling (+7.0). The same charges, copied again. */
public class GiftWrappedExpressOrder extends BasicOrder {
    public GiftWrappedExpressOrder(String item, double basePrice) {
        super(item, basePrice);
    }

    @Override
    public double price() {
        return super.price() + 3.0 + 7.0;
    }

    @Override
    public String description() {
        return super.description() + " + gift wrap + express";
    }
}
