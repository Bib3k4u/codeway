import java.util.List;

/** Works the same as it always has. Every new format means opening this class again. */
public class ReportService {

    public ReportService() {
    }

    // TODO (step 3): accept the registry here and keep it in a field (the default constructor above must keep working)
    public ReportService(ExporterRegistry registry) {
    }

    // TODO (step 3): ask the registry for the exporter instead of creating one here
    public String export(String format, List<String> rows) {
        if (format.equals("csv")) {
            CsvExporter exporter = new CsvExporter();
            return exporter.export(rows);
        } else if (format.equals("json")) {
            JsonExporter exporter = new JsonExporter();
            return exporter.export(rows);
        } else if (format.equals("text")) {
            TextExporter exporter = new TextExporter();
            return exporter.export(rows);
        } else {
            throw new IllegalArgumentException("Unknown format: " + format);
        }
    }
}
