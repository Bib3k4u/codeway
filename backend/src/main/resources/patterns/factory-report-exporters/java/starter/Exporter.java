import java.util.List;

/** One way of turning a report into text. */
public interface Exporter {
    // TODO (step 1): every export format will provide this one method (the signature is already here)
    String export(List<String> rows);
}
