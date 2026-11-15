/** The one place that knows how an order is placed: reserve, charge, schedule, receipt, and undo the reservation on failure. */
public class OrderDesk {
    private final Inventory inventory;
    private final PaymentGateway payments;
    private final ShippingService shipping;
    private final Receipts receipts;

    public OrderDesk() {
        this(new InMemoryInventory(), new InMemoryPayments(), new InMemoryShipping(), new InMemoryReceipts());
    }

    public OrderDesk(Inventory inventory, PaymentGateway payments, ShippingService shipping, Receipts receipts) {
        this.inventory = inventory;
        this.payments = payments;
        this.shipping = shipping;
        this.receipts = receipts;
    }

    public String placeOrder(String sku, int qty, String account, String address) {
        inventory.reserve(sku, qty);
        int total = inventory.unitPrice(sku) * qty;
        try {
            payments.charge(account, total);
        } catch (IllegalStateException e) {
            inventory.release(sku, qty);
            throw e;
        }
        shipping.schedule(address, sku);
        return receipts.issue(account, total);
    }

    public int stockLeft(String sku) {
        return inventory.available(sku);
    }
}
