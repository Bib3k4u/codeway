import java.util.ArrayList;
import java.util.List;

/** Sends an alert text to people and keeps a record of the ones that were delivered. */
public class AlertService {
    private final MessageSender sender;
    private final List<String> sentLog = new ArrayList<>();

    public AlertService() {
        this(new LegacySmsSender(new LegacySmsGateway()));
    }

    public AlertService(MessageSender sender) {
        this.sender = sender;
    }

    /** Returns true when the alert was delivered. */
    public boolean alert(String to, String message) {
        if (!sender.send(to, "ALERT: " + message)) {
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
