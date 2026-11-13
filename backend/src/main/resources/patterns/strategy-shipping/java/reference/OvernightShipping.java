public class OvernightShipping implements ShippingRule {
    @Override
    public double cost(double kg) {
        return 25.0 + 4.0 * kg;
    }
}
