public class Main {
    public static void main(String[] args) {
        Order book = new BasicOrder("Book", 12.0);
        show(book);
        show(new GiftWrap(book));
        show(new ExpressHandling(book));
        show(new ExpressHandling(new GiftWrap(new BasicOrder("Lamp", 30.0))));
    }

    static void show(Order order) {
        System.out.println(order.description() + " = " + order.price());
    }
}
