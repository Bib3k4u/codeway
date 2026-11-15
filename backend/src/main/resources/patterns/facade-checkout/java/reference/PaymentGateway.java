/** Takes money from an account. */
public interface PaymentGateway {
    /** Throws IllegalStateException("Payment declined for ACCOUNT") when the payment is refused. */
    void charge(String account, int cents);
}
