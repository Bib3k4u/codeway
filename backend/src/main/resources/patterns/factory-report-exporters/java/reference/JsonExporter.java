import java.util.ArrayList;
import java.util.List;

public class JsonExporter implements Exporter {
    @Override
    public String export(List<String> rows) {
        List<String> items = new ArrayList<>();
        for (String row : rows) {
            items.add("\"" + row.replace("\"", "\\\"") + "\"");
        }
        return "[" + String.join(",", items) + "]";
    }
}
