/** Builds report headers and formats money according to the app's settings. */
public class ReportService {
    // TODO (step 2): get the settings from AppSettings.getInstance() instead of creating a private copy.
    private final AppSettings settings = new AppSettings();

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
