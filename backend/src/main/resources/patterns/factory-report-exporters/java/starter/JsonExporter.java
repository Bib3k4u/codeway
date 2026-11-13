import java.util.ArrayList;
import java.util.List;

public class JsonExporter {
    // TODO (step 1): make this class implement Exporter (the method below already has the right shape)
    public String export(List<String> rows) {
        List<String> items = new ArrayList<>();
        for (String row : rows) {
            items.add("\"" + row.replace("\"", "\\\"") + "\"");
        }
        return "[" + String.join(",", items) + "]";
    }
}
