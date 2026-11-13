import java.util.ArrayList;
import java.util.List;

public class CsvExporter implements Exporter {
    @Override
    public String export(List<String> rows) {
        List<String> lines = new ArrayList<>();
        lines.add("value");
        for (String row : rows) {
            lines.add(row.contains(",") ? "\"" + row + "\"" : row);
        }
        return String.join("\n", lines);
    }
}
