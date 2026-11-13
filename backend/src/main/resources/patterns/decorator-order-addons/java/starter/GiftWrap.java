// TODO (step 2): wrap another Order. price() is the wrapped price + 3.0; description() is the wrapped description + " + gift wrap".
public class GiftWrap implements Order {
    public GiftWrap(Order inner) {
        // TODO (step 2): remember the order that is being wrapped
    }

    @Override
    public double price() {
        return 0.0; // TODO (step 2)
    }

    @Override
    public String description() {
        return ""; // TODO (step 2)
    }
}
