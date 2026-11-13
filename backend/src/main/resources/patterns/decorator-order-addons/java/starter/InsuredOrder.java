/** An order with shipping insurance (+2.5). */
public class InsuredOrder extends BasicOrder {
    public InsuredOrder(String item, double basePrice) {
        super(item, basePrice);
    }

    @Override
    public double price() {
        return super.price() + 2.5;
    }

    @Override
    public String description() {
        return super.description() + " + insurance";
    }
}
