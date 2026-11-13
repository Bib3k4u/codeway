import java.util.List;

public class Main {
    public static void main(String[] args) {
        List<String> rows = List.of("apples", "pears, ripe", "figs");
        ReportService service = new ReportService();
        for (String format : List.of("csv", "json", "text")) {
            System.out.println("== " + format + " ==");
            System.out.println(service.export(format, rows));
        }
    }
}
