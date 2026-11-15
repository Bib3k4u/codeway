/** The online shop's checkout. */
public class WebCheckout {
    private final OrderDesk desk = new OrderDesk();

    public String submit(String sku, int qty, String account, String address) {
        try {
            return "WEB OK " + desk.placeOrder(sku, qty, account, address);
        } catch (IllegalStateException e) {
            return "WEB FAILED " + e.getMessage();
        }
    }

    public int stockLeft(String sku) {
        return desk.stockLeft(sku);
    }
}
