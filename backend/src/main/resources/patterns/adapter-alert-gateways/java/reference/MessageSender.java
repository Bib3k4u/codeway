/** What our application wants from any message vendor: send one message to one recipient and say whether it worked. */
public interface MessageSender {
    boolean send(String to, String message);
}
