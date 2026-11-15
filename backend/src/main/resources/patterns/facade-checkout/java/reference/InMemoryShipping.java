import java.util.ArrayList;
import java.util.List;

public class InMemoryShipping implements ShippingService {
    private final List<String> booked = new ArrayList<>();

    public void schedule(String address, String sku) {
        booked.add(sku + " -> " + address);
    }
}
