/** Anything that has a price and a description: a plain order, or an order with add-ons. */
public interface Order {
    double price();

    String description();
}
