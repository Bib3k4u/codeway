import java.util.ArrayList;
import java.util.List;

public class TextExporter {
    // TODO (step 1): make this class implement Exporter (the method below already has the right shape)
    public String export(List<String> rows) {
        List<String> lines = new ArrayList<>();
        for (String row : rows) {
            lines.add("- " + row);
        }
        return String.join("\n", lines);
    }
}
