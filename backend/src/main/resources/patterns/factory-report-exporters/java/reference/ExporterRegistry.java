import java.util.HashMap;
import java.util.Map;
import java.util.function.Supplier;

/** Hands out the right Exporter for a format name. New formats are added with addFormat(), not by editing this class. */
public class ExporterRegistry {
    private final Map<String, Supplier<Exporter>> suppliers = new HashMap<>();

    public ExporterRegistry() {
        addFormat("csv", CsvExporter::new);
        addFormat("json", JsonExporter::new);
        addFormat("text", TextExporter::new);
    }

    public Exporter create(String format) {
        Supplier<Exporter> supplier = suppliers.get(format);
        if (supplier == null) {
            throw new IllegalArgumentException("Unknown format: " + format);
        }
        return supplier.get();
    }

    public void addFormat(String format, Supplier<Exporter> supplier) {
        suppliers.put(format, supplier);
    }
}
