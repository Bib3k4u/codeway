import java.util.HashMap;
import java.util.Map;

/** Prices a parcel by looking up the rule for its delivery type. New types are added with register(). */
public class ShippingCalculator {
    private final Map<String, ShippingRule> rules = new HashMap<>();

    public ShippingCalculator() {
        rules.put("standard", new StandardShipping());
        rules.put("express", new ExpressShipping());
        rules.put("overnight", new OvernightShipping());
    }

    public ShippingCalculator(Map<String, ShippingRule> rules) {
        this.rules.putAll(rules);
    }

    public double cost(String type, double kg) {
        ShippingRule rule = rules.get(type);
        if (rule == null) {
            throw new IllegalArgumentException("Unknown shipping type: " + type);
        }
        return rule.cost(kg);
    }

    public void register(String type, ShippingRule rule) {
        rules.put(type, rule);
    }
}
