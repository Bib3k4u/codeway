/** Lets the vendor's LegacySmsGateway be used wherever the application expects a MessageSender. */
public class LegacySmsSender implements MessageSender {
    private static final int NORMAL_PRIORITY = 1;

    private final LegacySmsGateway gateway;

    public LegacySmsSender(LegacySmsGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public boolean send(String to, String message) {
        int status = gateway.sendText(to, message, NORMAL_PRIORITY);
        return status == 0;
    }
}
