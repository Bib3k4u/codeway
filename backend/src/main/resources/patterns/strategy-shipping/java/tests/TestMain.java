import java.util.Map;

public class TestMain {
    private static final double EPS = 1e-9;

    public static void main(String[] args) {
        // ---- the old behaviour, through the old entry point ----
        Check.run("Guard: standard shipping costs 5.0 plus 1.0 per kg", () -> {
            ShippingCalculator calc = new ShippingCalculator();
            Check.eq(7.0, calc.cost("standard", 2.0), EPS);
            Check.eq(15.0, calc.cost("standard", 10.0), EPS);
        });
        Check.run("Guard: express shipping costs 10.0 plus 2.5 per kg", () -> {
            Check.eq(20.0, new ShippingCalculator().cost("express", 4.0), EPS);
        });
        Check.run("Guard: overnight shipping costs 25.0 plus 4.0 per kg, even for an empty parcel", () -> {
            ShippingCalculator calc = new ShippingCalculator();
            Check.eq(31.0, calc.cost("overnight", 1.5), EPS);
            Check.eq(25.0, calc.cost("overnight", 0.0), EPS);
        });

        // ---- step 1: ShippingRule and one class per delivery type ----
        Check.run("S1: StandardShipping is a ShippingRule priced 5.0 + 1.0 per kg", () -> {
            ShippingRule rule = new StandardShipping();
            Check.eq(7.0, rule.cost(2.0), EPS);
            Check.eq(5.0, rule.cost(0.0), EPS);
        });
        Check.run("S1: ExpressShipping is a ShippingRule priced 10.0 + 2.5 per kg", () -> {
            ShippingRule rule = new ExpressShipping();
            Check.eq(20.0, rule.cost(4.0), EPS);
            Check.eq(10.0, rule.cost(0.0), EPS);
        });
        Check.run("S1: OvernightShipping is a ShippingRule priced 25.0 + 4.0 per kg", () -> {
            ShippingRule rule = new OvernightShipping();
            Check.eq(33.0, rule.cost(2.0), EPS);
            Check.eq(25.0, rule.cost(0.0), EPS);
        });

        // ---- step 2: the calculator asks a rule instead of switching ----
        Check.run("S2: a calculator built from a map of rules uses those rules", () -> {
            ShippingCalculator calc = new ShippingCalculator(Map.<String, ShippingRule>of("standard", kg -> 100.0 + kg));
            Check.eq(102.0, calc.cost("standard", 2.0), EPS);
        });
        Check.run("S2: each type in the map gets its own rule, and the rule receives the weight", () -> {
            ShippingCalculator calc = new ShippingCalculator(Map.<String, ShippingRule>of(
                    "flat", kg -> 1.0,
                    "heavy", kg -> kg * 10.0));
            Check.eq(1.0, calc.cost("flat", 7.0), EPS);
            Check.eq(30.0, calc.cost("heavy", 3.0), EPS);
        });
        Check.run("S2: the default calculator still prices all three original types", () -> {
            ShippingCalculator calc = new ShippingCalculator();
            Check.eq(7.0, calc.cost("standard", 2.0), EPS);
            Check.eq(20.0, calc.cost("express", 4.0), EPS);
            Check.eq(33.0, calc.cost("overnight", 2.0), EPS);
        });

        // ---- step 3: unknown types are an error, not a silent price ----
        Check.run("S3: an unknown type throws IllegalArgumentException", () -> {
            Check.throwsEx(IllegalArgumentException.class, () -> new ShippingCalculator().cost("drone", 2.0));
        });
        Check.run("S3: the message is \"Unknown shipping type: <type>\"", () -> {
            try {
                new ShippingCalculator().cost("drone", 2.0);
            } catch (IllegalArgumentException e) {
                Check.eq("Unknown shipping type: drone", e.getMessage());
                return;
            }
            throw new AssertionError("expected IllegalArgumentException but nothing was thrown");
        });
        Check.run("S3: a type missing from a custom map is unknown too", () -> {
            ShippingCalculator calc = new ShippingCalculator(Map.<String, ShippingRule>of("flat", kg -> 1.0));
            Check.throwsEx(IllegalArgumentException.class, () -> calc.cost("express", 4.0));
        });

        // ---- step 4: a brand-new delivery type, defined right here, with no change to existing classes ----
        Check.run("S4: a rule registered at runtime (a lambda defined in the test) is used", () -> {
            ShippingCalculator calc = new ShippingCalculator();
            calc.register("drone", kg -> 8.0 + 0.5 * kg);
            Check.eq(9.0, calc.cost("drone", 2.0), EPS);
            Check.eq(7.0, calc.cost("standard", 2.0), EPS);   // the old types are untouched
        });
        Check.run("S4: a rule written as its own class (defined in the test) works too", () -> {
            class FreightShipping implements ShippingRule {
                @Override
                public double cost(double kg) {
                    return 50.0 + 0.25 * kg;
                }
            }
            ShippingCalculator calc = new ShippingCalculator();
            calc.register("freight", new FreightShipping());
            Check.eq(100.0, calc.cost("freight", 200.0), EPS);
        });
        Check.run("S4: registering a name again replaces the old rule, and other calculators are not affected", () -> {
            ShippingCalculator first = new ShippingCalculator();
            first.register("standard", kg -> 0.0);
            Check.eq(0.0, first.cost("standard", 5.0), EPS);
            ShippingCalculator second = new ShippingCalculator();
            Check.eq(10.0, second.cost("standard", 5.0), EPS);
            Check.throwsEx(IllegalArgumentException.class, () -> second.cost("drone", 1.0));
        });
    }
}
