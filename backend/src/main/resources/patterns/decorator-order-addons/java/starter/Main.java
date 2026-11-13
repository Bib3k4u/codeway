public class Main {
    public static void main(String[] args) {
        show(new BasicOrder("Book", 12.0));
        show(new GiftWrappedOrder("Book", 12.0));
        show(new ExpressOrder("Book", 12.0));
        show(new GiftWrappedExpressOrder("Lamp", 30.0));
    }

    static void show(BasicOrder order) {
        System.out.println(order.description() + " = " + order.price());
    }
}
