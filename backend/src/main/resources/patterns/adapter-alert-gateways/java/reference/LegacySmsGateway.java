import java.util.ArrayList;
import java.util.List;

/**
 * The SMS vendor's own class. It ships as a library: treat it as unchangeable, you cannot edit it.
 * Note its shape: method sendText, the phone number first, a priority number, and an int status code as the result
 * (0 = accepted, 1 = invalid phone number, 2 = text too long).
 */
public class LegacySmsGateway {
    private final List<String> sent = new ArrayList<>();

    public int sendText(String phoneNumber, String body, int priority) {
        if (phoneNumber == null || !phoneNumber.startsWith("+")) {
            return 1;
        }
        if (body == null || body.length() > 160) {
            return 2;
        }
        sent.add("[" + priority + "] " + phoneNumber + ": " + body);
        return 0;
    }

    /** Everything this vendor has accepted so far, oldest first. */
    public List<String> sentLog() {
        return new ArrayList<>(sent);
    }
}
