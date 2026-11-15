public class InMemoryReceipts implements Receipts {
    private int count = 0;

    public String issue(String account, int totalCents) {
        count++;
        return "R-" + count;
    }
}
