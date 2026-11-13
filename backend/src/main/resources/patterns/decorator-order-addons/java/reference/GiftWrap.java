/** Wraps another Order and adds gift wrapping (+3.0) to it. */
public class GiftWrap implements Order {
    private final Order inner;

    public GiftWrap(Order inner) {
        this.inner = inner;
    }

    @Override
    public double price() {
        return inner.price() + 3.0;
    }

    @Override
    public String description() {
        return inner.description() + " + gift wrap";
    }
}
