/** Greets people in the language chosen in the app's settings. */
public class GreetingService {
    private final AppSettings settings = AppSettings.getInstance();

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
