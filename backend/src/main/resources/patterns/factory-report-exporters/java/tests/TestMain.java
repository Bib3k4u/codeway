import java.util.ArrayList;
import java.util.List;

public class TestMain {
    /** An ExporterRegistry defined here: records which formats were asked for and answers with a fixed exporter. */
    static class FakeRegistry extends ExporterRegistry {
        final List<String> asked = new ArrayList<>();

        @Override
        public Exporter create(String format) {
            asked.add(format);
            if (format.equals("boom")) throw new IllegalArgumentException("fake refuses boom");
            return rows -> "fake:" + format + ":" + rows.size();
        }
    }

    /** A brand-new format, defined here, that no existing class knows about. */
    static class UpperCaseExporter implements Exporter {
        @Override
        public String export(List<String> rows) {
            return String.join("|", rows).toUpperCase();
        }
    }

    public static void main(String[] args) {
        List<String> rows = List.of("a", "b,c");

        // ---- the old behaviour, through the old entry point ----
        Check.run("Guard: csv has a value header and one line per row, quoting rows that contain a comma", () -> {
            Check.eq("value\na\n\"b,c\"", new ReportService().export("csv", rows));
            Check.eq("value", new ReportService().export("csv", List.of()));
        });
        Check.run("Guard: json is an array of quoted strings", () -> {
            Check.eq("[\"a\",\"b,c\"]", new ReportService().export("json", rows));
            Check.eq("[\"say \\\"hi\\\"\"]", new ReportService().export("json", List.of("say \"hi\"")));
        });
        Check.run("Guard: text puts \"- \" in front of every row, and an unknown format is an error", () -> {
            Check.eq("- a\n- b,c", new ReportService().export("text", rows));
            try {
                new ReportService().export("xml", rows);
            } catch (IllegalArgumentException e) {
                Check.eq("Unknown format: xml", e.getMessage());
                return;
            }
            throw new AssertionError("expected IllegalArgumentException but nothing was thrown");
        });

        // ---- step 1: one shared type, Exporter ----
        Check.run("S1: CsvExporter is an Exporter", () -> {
            Object o = new CsvExporter();
            Check.isTrue(o instanceof Exporter, "CsvExporter should implement Exporter");
            Check.eq("value\na\n\"b,c\"", ((Exporter) o).export(rows));
        });
        Check.run("S1: JsonExporter is an Exporter", () -> {
            Object o = new JsonExporter();
            Check.isTrue(o instanceof Exporter, "JsonExporter should implement Exporter");
            Check.eq("[\"a\",\"b,c\"]", ((Exporter) o).export(rows));
        });
        Check.run("S1: TextExporter is an Exporter", () -> {
            Object o = new TextExporter();
            Check.isTrue(o instanceof Exporter, "TextExporter should implement Exporter");
            Check.eq("- a\n- b,c", ((Exporter) o).export(rows));
        });

        // ---- step 2: the registry picks the exporter ----
        Check.run("S2: create returns the matching exporter for each built-in format", () -> {
            ExporterRegistry registry = new ExporterRegistry();
            Check.eq("value\na\n\"b,c\"", registry.create("csv").export(rows));
            Check.eq("[\"a\",\"b,c\"]", registry.create("json").export(rows));
            Check.eq("- a\n- b,c", registry.create("text").export(rows));
        });
        Check.run("S2: an unknown format throws IllegalArgumentException", () -> {
            Check.throwsEx(IllegalArgumentException.class, () -> new ExporterRegistry().create("xml"));
        });
        Check.run("S2: the message is \"Unknown format: <format>\"", () -> {
            try {
                new ExporterRegistry().create("yaml");
            } catch (IllegalArgumentException e) {
                Check.eq("Unknown format: yaml", e.getMessage());
                return;
            }
            throw new AssertionError("expected IllegalArgumentException but nothing was thrown");
        });

        // ---- step 3: the service is handed its registry ----
        Check.run("S3: a service built with a registry uses that registry's exporters", () -> {
            FakeRegistry fake = new FakeRegistry();
            Check.eq("fake:anything:2", new ReportService(fake).export("anything", rows));
        });
        Check.run("S3: the service asks the registry for the requested format every time", () -> {
            FakeRegistry fake = new FakeRegistry();
            ReportService service = new ReportService(fake);
            service.export("one", rows);
            service.export("two", List.of());
            Check.eq(List.of("one", "two"), fake.asked);
        });
        Check.run("S3: a failure from the registry reaches the caller", () -> {
            ReportService service = new ReportService(new FakeRegistry());
            try {
                service.export("boom", rows);
            } catch (IllegalArgumentException e) {
                Check.eq("fake refuses boom", e.getMessage());
                return;
            }
            throw new AssertionError("expected IllegalArgumentException but nothing was thrown");
        });
        Check.run("S3: the default service still works through a registry of its own", () -> {
            ReportService service = new ReportService();
            Check.eq("- a\n- b,c", service.export("text", rows));
            Check.throwsEx(IllegalArgumentException.class, () -> service.export("xml", rows));
        });

        // ---- step 4: a brand-new format, defined right here, with no change to existing classes ----
        Check.run("S4: a supplier registered at runtime is used by create", () -> {
            ExporterRegistry registry = new ExporterRegistry();
            registry.register("upper", UpperCaseExporter::new);
            Check.eq("A|B,C", registry.create("upper").export(rows));
            Check.eq("- a\n- b,c", registry.create("text").export(rows));   // the built-in formats are untouched
        });
        Check.run("S4: a service using that registry can export the new format", () -> {
            ExporterRegistry registry = new ExporterRegistry();
            registry.register("upper", UpperCaseExporter::new);
            Check.eq("A|B,C", new ReportService(registry).export("upper", rows));
            Check.eq("[\"a\",\"b,c\"]", new ReportService(registry).export("json", rows));
        });
        Check.run("S4: registering a name again replaces it, and other registries are not affected", () -> {
            ExporterRegistry first = new ExporterRegistry();
            first.register("text", () -> r -> "replaced");
            Check.eq("replaced", first.create("text").export(rows));
            ExporterRegistry second = new ExporterRegistry();
            Check.eq("- a\n- b,c", second.create("text").export(rows));
            Check.throwsEx(IllegalArgumentException.class, () -> second.create("upper"));
        });
    }
}
