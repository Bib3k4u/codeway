/** The online shop's checkout. */
public class WebCheckout {
    private final Inventory inventory = new InMemoryInventory();
    private final PaymentGateway payments = new InMemoryPayments();
    private final ShippingService shipping = new InMemoryShipping();
    private final Receipts receipts = new InMemoryReceipts();

    public String submit(String sku, int qty, String account, String address) {
        try {
            inventory.reserve(sku, qty);
        } catch (IllegalStateException e) {
            return "WEB FAILED " + e.getMessage();
        }
        int total = inventory.unitPrice(sku) * qty;
        try {
            payments.charge(account, total);
        } catch (IllegalStateException e) {
            inventory.release(sku, qty);
            return "WEB FAILED " + e.getMessage();
        }
        shipping.schedule(address, sku);
        return "WEB OK " + receipts.issue(account, total);
    }

    public int stockLeft(String sku) {
        return inventory.available(sku);
    }
}
