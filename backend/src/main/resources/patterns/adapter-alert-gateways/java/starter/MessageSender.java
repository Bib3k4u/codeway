/**
 * What our application wants from any message vendor: send one message to one recipient and say whether it worked.
 *
 * TODO (step 1): write LegacySmsSender so that the vendor's LegacySmsGateway can be used as a MessageSender.
 * Keep this interface as it is.
 */
public interface MessageSender {
    boolean send(String to, String message);
}
