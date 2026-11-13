import java.util.ArrayList;
import java.util.List;

public class TextExporter implements Exporter {
    @Override
    public String export(List<String> rows) {
        List<String> lines = new ArrayList<>();
        for (String row : rows) {
            lines.add("- " + row);
        }
        return String.join("\n", lines);
    }
}
