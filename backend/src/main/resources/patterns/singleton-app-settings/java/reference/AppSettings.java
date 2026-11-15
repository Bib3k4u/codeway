import java.util.HashMap;
import java.util.Map;

/** The app's settings, parsed from a block of key=value text. Parsing is the slow part, so it is counted. */
public class AppSettings {
    private static final String RAW = "theme=light\nlanguage=en\ncurrency=EUR\n";
    private static int loads = 0;
    private static AppSettings instance;

    private final Map<String, String> values = new HashMap<>();

    private AppSettings() {
        load();
    }

    private void load() {
        loads++;
        for (String line : RAW.split("\n")) {
            int eq = line.indexOf('=');
            if (eq > 0) {
                values.put(line.substring(0, eq).trim(), line.substring(eq + 1).trim());
            }
        }
    }

    /** The one shared AppSettings, created and loaded the first time it is asked for. */
    public static AppSettings getInstance() {
        if (instance == null) {
            instance = new AppSettings();
        }
        return instance;
    }

    /** The value for a key, or an empty string when there is none. */
    public String get(String key) {
        return values.getOrDefault(key, "");
    }

    public void set(String key, String value) {
        values.put(key, value);
    }

    /** How many times the settings text has been parsed so far. */
    public static int loadCount() {
        return loads;
    }

    /** For tests only: forget the shared instance and the counter so that the next check starts fresh. */
    public static void resetForTests() {
        instance = null;
        loads = 0;
    }
}
