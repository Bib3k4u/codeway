import java.util.function.Supplier;

/** Hands out the right Exporter for a format name. */
public class ExporterRegistry {

    // TODO (step 2): return a new CsvExporter for "csv", a new JsonExporter for "json" and a new TextExporter for "text"
    // TODO (step 2): any other format throws IllegalArgumentException("Unknown format: <format>")
    // TODO (step 4): look the format up in a map instead, filled with the three built-in formats
    public Exporter create(String format) {
        throw new UnsupportedOperationException("TODO (step 2)");
    }

    // TODO (step 4): remember the supplier under that format name so create(format) can use it
    public void register(String format, Supplier<Exporter> supplier) {
        throw new UnsupportedOperationException("TODO (step 4)");
    }
}
