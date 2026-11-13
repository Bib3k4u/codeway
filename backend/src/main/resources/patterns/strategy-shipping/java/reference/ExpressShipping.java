public class ExpressShipping implements ShippingRule {
    @Override
    public double cost(double kg) {
        return 10.0 + 2.5 * kg;
    }
}
