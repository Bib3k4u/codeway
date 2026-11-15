/** Accepts every payment except those from the account named "EMPTY". */
public class InMemoryPayments implements PaymentGateway {
    public void charge(String account, int cents) {
        if (account.equals("EMPTY")) {
            throw new IllegalStateException("Payment declined for " + account);
        }
    }
}
