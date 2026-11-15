/** The call centre's checkout. */
public class PhoneCheckout {
    private final Inventory inventory = new InMemoryInventory();
    private final PaymentGateway payments = new InMemoryPayments();
    private final ShippingService shipping = new InMemoryShipping();
    private final Receipts receipts = new InMemoryReceipts();

    public String takeOrder(String sku, int qty, String account, String address) {
        try {
            inventory.reserve(sku, qty);
            int total = inventory.unitPrice(sku) * qty;
            payments.charge(account, total);
            shipping.schedule(address, sku);
            return "PHONE: confirmed " + receipts.issue(account, total);
        } catch (IllegalStateException e) {
            return "PHONE: sorry, " + e.getMessage();
        }
    }

    public int stockLeft(String sku) {
        return inventory.available(sku);
    }
}
