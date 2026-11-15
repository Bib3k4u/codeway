/** Keeps track of what is in stock. */
public interface Inventory {
    /** Takes qty items out of stock; throws IllegalStateException("Out of stock: SKU") when there are not enough. */
    void reserve(String sku, int qty);

    /** Puts qty previously reserved items back. */
    void release(String sku, int qty);

    int available(String sku);

    /** Price of one item, in cents. */
    int unitPrice(String sku);
}
