/** An order with shipping insurance (+2.5) and express handling (+7.0). The same charges, copied yet again. */
public class InsuredExpressOrder extends BasicOrder {
    public InsuredExpressOrder(String item, double basePrice) {
        super(item, basePrice);
    }

    @Override
    public double price() {
        return super.price() + 2.5 + 7.0;
    }

    @Override
    public String description() {
        return super.description() + " + insurance + express";
    }
}
