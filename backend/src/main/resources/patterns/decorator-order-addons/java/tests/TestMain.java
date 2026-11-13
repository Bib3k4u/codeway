public class TestMain {
    /** A brand-new add-on, defined here and not in the learner's code: shipping insurance (+2.5). */
    static class Insurance implements Order {
        private final Order inner;

        Insurance(Order inner) { this.inner = inner; }

        @Override public double price() { return inner.price() + 2.5; }

        @Override public String description() { return inner.description() + " + insurance"; }
    }

    /** A plain order as an Order. Goes through Object so this file compiles even before BasicOrder implements Order. */
    static Order basic(String item, double price) {
        Object o = new BasicOrder(item, price);
        Check.isTrue(o instanceof Order, "BasicOrder must implement Order (step 1)");
        return (Order) o;
    }

    static boolean classExists(String name) {
        try { Class.forName(name); return true; } catch (ClassNotFoundException e) { return false; }
    }

    public static void main(String[] args) {
        // ---- the old behaviour: BasicOrder must keep working through its old API ----
        Check.run("Guard: a basic order reports its price", () -> {
            Check.eq(12.0, new BasicOrder("Book", 12.0).price(), 1e-9);
            Check.eq(30.0, new BasicOrder("Lamp", 30.0).price(), 1e-9);
        });
        Check.run("Guard: a basic order describes itself with just the item name", () -> {
            Check.eq("Book", new BasicOrder("Book", 12.0).description());
            Check.eq("Desk Lamp", new BasicOrder("Desk Lamp", 30.0).description());
        });
        Check.run("Guard: two basic orders do not affect each other", () -> {
            BasicOrder a = new BasicOrder("Book", 12.0);
            BasicOrder b = new BasicOrder("Lamp", 30.0);
            Check.eq(12.0, a.price(), 1e-9);
            Check.eq("Lamp", b.description());
        });

        // ---- step 1: one common type ----
        Check.run("S1: BasicOrder implements Order", () ->
                Check.isTrue(new BasicOrder("Book", 12.0) instanceof Order, "BasicOrder must implement Order"));
        Check.run("S1: a BasicOrder used as an Order gives the same price and description", () -> {
            Order order = basic("Lamp", 30.0);
            Check.eq(30.0, order.price(), 1e-9);
            Check.eq("Lamp", order.description());
        });

        // ---- step 2: GiftWrap ----
        Check.run("S2: GiftWrap adds 3.0 to the wrapped price", () -> {
            Check.eq(15.0, new GiftWrap(basic("Book", 12.0)).price(), 1e-9);
            Check.eq(33.0, new GiftWrap(basic("Lamp", 30.0)).price(), 1e-9);
        });
        Check.run("S2: GiftWrap appends \" + gift wrap\" to the wrapped description", () ->
                Check.eq("Book + gift wrap", new GiftWrap(basic("Book", 12.0)).description()));
        Check.run("S2: GiftWrap wraps any Order, not only BasicOrder", () -> {
            Order thing = new Order() {
                @Override public double price() { return 100.0; }
                @Override public String description() { return "Thing"; }
            };
            Check.eq(103.0, new GiftWrap(thing).price(), 1e-9);
            Check.eq("Thing + gift wrap", new GiftWrap(thing).description());
        });

        // ---- step 3: ExpressHandling, and stacking ----
        Check.run("S3: ExpressHandling adds 7.0 and \" + express\"", () -> {
            Order order = new ExpressHandling(basic("Book", 12.0));
            Check.eq(19.0, order.price(), 1e-9);
            Check.eq("Book + express", order.description());
        });
        Check.run("S3: add-ons stack in either order and the description lists them as applied", () -> {
            Order wrapThenExpress = new ExpressHandling(new GiftWrap(basic("Book", 12.0)));
            Check.eq(22.0, wrapThenExpress.price(), 1e-9);
            Check.eq("Book + gift wrap + express", wrapThenExpress.description());
            Order expressThenWrap = new GiftWrap(new ExpressHandling(basic("Book", 12.0)));
            Check.eq(22.0, expressThenWrap.price(), 1e-9);
            Check.eq("Book + express + gift wrap", expressThenWrap.description());
        });
        Check.run("S3: the same add-on can be applied twice", () -> {
            Order order = new ExpressHandling(new ExpressHandling(basic("Lamp", 30.0)));
            Check.eq(44.0, order.price(), 1e-9);
            Check.eq("Lamp + express + express", order.description());
        });

        // ---- step 4: a new add-on from outside, and the combination classes are gone ----
        Check.run("S4: a new add-on defined in the tests stacks on top of the existing ones", () -> {
            Order order = new Insurance(new ExpressHandling(new GiftWrap(basic("Book", 12.0))));
            Check.eq(24.5, order.price(), 1e-9);
            Check.eq("Book + gift wrap + express + insurance", order.description());
        });
        Check.run("S4: the new add-on also works underneath the existing ones", () -> {
            Order order = new GiftWrap(new Insurance(basic("Book", 12.0)));
            Check.eq(17.5, order.price(), 1e-9);
            Check.eq("Book + insurance + gift wrap", order.description());
        });
        Check.run("S4: the combination classes have been deleted", () -> {
            for (String name : new String[]{"GiftWrappedOrder", "ExpressOrder", "GiftWrappedExpressOrder", "InsuredOrder", "InsuredExpressOrder"})
                Check.isTrue(!classExists(name), "delete " + name + ": wrapping BasicOrder replaces it");
        });
    }
}
