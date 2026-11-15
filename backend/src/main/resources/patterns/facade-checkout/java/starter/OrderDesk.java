/**
 * The one place that knows how an order is placed.
 *
 * TODO (step 1): placeOrder runs the whole sequence and stockLeft reports the stock; the no-argument constructor
 * uses the in-memory subsystems. TODO (step 2): give stock back when the payment fails.
 * TODO (step 4): the four-argument constructor lets the caller choose the subsystems.
 */
public class OrderDesk {
    public OrderDesk() {
        // TODO (step 1): create the real in-memory subsystems
    }

    public OrderDesk(Inventory inventory, PaymentGateway payments, ShippingService shipping, Receipts receipts) {
        // TODO (step 4): use the subsystems you are given
    }

    public String placeOrder(String sku, int qty, String account, String address) {
        throw new UnsupportedOperationException("TODO (step 1): reserve, charge, schedule, issue; return the receipt id");
    }

    public int stockLeft(String sku) {
        throw new UnsupportedOperationException("TODO (step 1): ask the inventory");
    }
}
