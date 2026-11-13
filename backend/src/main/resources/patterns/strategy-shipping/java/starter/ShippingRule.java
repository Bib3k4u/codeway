/** One way of pricing a parcel. */
public interface ShippingRule {
    // TODO (step 1): every delivery type will provide this one method (the signature is already here)
    double cost(double kg);
}
