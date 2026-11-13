import java.util.List;

/** Knows how to export a report, but not which exporters exist: the registry decides that. */
public class ReportService {
    private final ExporterRegistry registry;

    public ReportService() {
        this(new ExporterRegistry());
    }

    public ReportService(ExporterRegistry registry) {
        this.registry = registry;
    }

    public String export(String format, List<String> rows) {
        Exporter exporter = registry.create(format);
        return exporter.export(rows);
    }
}
