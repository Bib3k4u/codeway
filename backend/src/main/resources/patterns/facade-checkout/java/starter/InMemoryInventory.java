import java.util.HashMap;
import java.util.Map;

public class InMemoryInventory implements Inventory {
    private final Map<String, Integer> stock = new HashMap<>(Map.of("BOOK", 5, "LAMP", 2, "DESK", 1));
    private final Map<String, Integer> prices = Map.of("BOOK", 1200, "LAMP", 4500, "DESK", 15000);

    public void reserve(String sku, int qty) {
        if (available(sku) < qty) {
            throw new IllegalStateException("Out of stock: " + sku);
        }
        stock.put(sku, available(sku) - qty);
    }

    public void release(String sku, int qty) {
        stock.put(sku, available(sku) + qty);
    }

    public int available(String sku) {
        return stock.getOrDefault(sku, 0);
    }

    public int unitPrice(String sku) {
        return prices.getOrDefault(sku, 0);
    }
}
