import java.util.HashMap;
import java.util.Map;

/** The app's settings, parsed from a block of key=value text. Parsing is the slow part, so it is counted. */
public class AppSettings {
    private static final String RAW = "theme=light\nlanguage=en\ncurrency=EUR\n";
    private static int loads = 0;

    private final Map<String, String> values = new HashMap<>();

    public AppSettings() {
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

    // TODO (step 1): keep ONE shared instance in a private static field and build it the first time it is asked for.
    public static AppSettings getInstance() {
        throw new UnsupportedOperationException("TODO (step 1): return the one shared AppSettings, creating it on first use");
    }

    // TODO (step 1): forget the shared instance as well as resetting the counter, so a test can start fresh.
    public static void resetForTests() {
        loads = 0;
    }

    // TODO (step 3): once the services no longer call it, make the constructor above private.
}
