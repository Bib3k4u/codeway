// TODO (step 3): wrap another Order. price() is the wrapped price + 7.0; description() is the wrapped description + " + express".
public class ExpressHandling implements Order {
    public ExpressHandling(Order inner) {
        // TODO (step 3): remember the order that is being wrapped
    }

    @Override
    public double price() {
        return 0.0; // TODO (step 3)
    }

    @Override
    public String description() {
        return ""; // TODO (step 3)
    }
}
