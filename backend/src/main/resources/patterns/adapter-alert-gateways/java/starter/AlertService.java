import java.util.ArrayList;
import java.util.List;

/** Sends an alert text to people and keeps a record of the ones that were delivered. */
public class AlertService {
    private final LegacySmsGateway gateway = new LegacySmsGateway();
    private final List<String> sentLog = new ArrayList<>();

    public AlertService() {
    }

    // TODO (step 2): accept any MessageSender, keep it, and use it instead of the vendor class below.
    // new AlertService() must keep working exactly as it does today (it uses the vendor's SMS gateway).
    public AlertService(MessageSender sender) {
        throw new UnsupportedOperationException("TODO (step 2): remember the sender");
    }

    /** Returns true when the alert was delivered. */
    public boolean alert(String to, String message) {
        int status = gateway.sendText(to, "ALERT: " + message, 1);
        if (status != 0) {
            return false;
        }
        sentLog.add(to + ": " + message);
        return true;
    }

    /** Alerts everyone and returns the recipients it could not reach, in the order given. */
    public List<String> alertAll(List<String> recipients, String message) {
        List<String> failed = new ArrayList<>();
        for (String recipient : recipients) {
            if (!alert(recipient, message)) {
                failed.add(recipient);
            }
        }
        return failed;
    }

    /** One line per delivered alert, as "recipient: message". */
    public List<String> sentLog() {
        return sentLog;
    }
}
