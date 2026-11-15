/** The call centre's checkout. */
public class PhoneCheckout {
    private final OrderDesk desk = new OrderDesk();

    public String takeOrder(String sku, int qty, String account, String address) {
        try {
            return "PHONE: confirmed " + desk.placeOrder(sku, qty, account, address);
        } catch (IllegalStateException e) {
            return "PHONE: sorry, " + e.getMessage();
        }
    }

    public int stockLeft(String sku) {
        return desk.stockLeft(sku);
    }
}
