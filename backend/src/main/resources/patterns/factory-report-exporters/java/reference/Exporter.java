import java.util.List;

/** One way of turning a report into text. */
public interface Exporter {
    String export(List<String> rows);
}
