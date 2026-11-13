public class StandardShipping implements ShippingRule {
    @Override
    public double cost(double kg) {
        return 5.0 + 1.0 * kg;
    }
}
