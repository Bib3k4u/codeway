import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Hands out the right Exporter for a format name. New formats are added with register(), not by editing this class. */
public class ExporterRegistry {
    private final Map<String, Supplier<Exporter>> suppliers = new HashMap<>();

    public ExporterRegistry() {
        register("csv", CsvExporter::new);
        register("json", JsonExporter::new);
        register("text", TextExporter::new);
    }

    public Exporter create(String format) {
        Supplier<Exporter> supplier = suppliers.get(format);
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown format: " + format);
        }
        return supplier.get();
    }

    public void register(String format, Supplier<Exporter> supplier) {
        suppliers.put(format, supplier);
    }
}
