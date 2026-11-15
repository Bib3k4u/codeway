/** Greets people in the language chosen in the app's settings. */
public class GreetingService {
    // TODO (step 2): get the settings from AppSettings.getInstance() instead of creating a private copy.
    private final AppSettings settings = new AppSettings();

    public String greet(String name) {
        String word = switch (settings.get("language")) {
            case "fr" -> "Bonjour";
            case "de" -> "Hallo";
            default -> "Hello";
        };
        return word + ", " + name + "!";
    }

    public void useLanguage(String language) {
        settings.set("language", language);
    }
}
