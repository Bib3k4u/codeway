/** Wraps another Order and adds express handling (+7.0) to it. */
public class ExpressHandling implements Order {
    private final Order inner;

    public ExpressHandling(Order inner) {
        this.inner = inner;
    }

    @Override
    public double price() {
        return inner.price() + 7.0;
    }

    @Override
    public String description() {
        return inner.description() + " + express";
    }
}
