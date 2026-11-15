/** Writes receipts. */
public interface Receipts {
    /** Returns the new receipt's id, such as "R-1". */
    String issue(String account, int totalCents);
}
