import java.util.Map;

/** Works the same as it always has. Every new delivery type means opening this class again. */
public class ShippingCalculator {

    public ShippingCalculator() {
    }

    // TODO (step 2): keep the rules in a map, fill it here, and keep the default constructor above working
    public ShippingCalculator(Map<String, ShippingRule> rules) {
    }

    // TODO (step 2): look the rule up instead of using this switch
    // TODO (step 3): an unknown type must throw IllegalArgumentException("Unknown shipping type: <type>")
    public double cost(String type, double kg) {
        switch (type) {
            case "standard":
                return 5.0 + 1.0 * kg;
            case "express":
                return 10.0 + 2.5 * kg;
            case "overnight":
                return 25.0 + 4.0 * kg;
            default:
                return -1.0;
        }
    }

    // TODO (step 4): store the rule under that type name so cost(type, kg) can use it
    public void addRule(String type, ShippingRule rule) {
        throw new UnsupportedOperationException("TODO (step 4)");
    }
}
