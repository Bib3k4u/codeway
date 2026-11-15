/** Books the delivery of an item. */
public interface ShippingService {
    void schedule(String address, String sku);
}
