/** Builds report headers and formats money according to the app's settings. */
public class ReportService {
    private final AppSettings settings = AppSettings.getInstance();

    public String header() {
        return "== Report (" + settings.get("theme") + ", " + settings.get("language") + ") ==";
    }

    public String money(double amount) {
        return amount + " " + settings.get("currency");
    }

    public void useTheme(String theme) {
        settings.set("theme", theme);
    }
}
