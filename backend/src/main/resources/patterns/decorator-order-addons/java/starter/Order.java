// TODO (step 1): this is meant to be the common type of every order. Make BasicOrder implement it.
public interface Order {
    double price();

    String description();
}
