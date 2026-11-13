/** One way of pricing a parcel. */
public interface ShippingRule {
    double cost(double kg);
}
