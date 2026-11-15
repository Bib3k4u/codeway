import java.util.ArrayList;
import java.util.List;

public class TestMain {
    /** A second vendor with yet another shape (used by the step 3 tests): defined here, not in the learner's code. */
    static final class PushService {
        final List<String> delivered = new ArrayList<>();

        /** Different name, different parameter order, and a text status instead of a number or a boolean. */
        String push(String title, String text, String deviceToken) {
            if (!deviceToken.startsWith("dev-")) {
                return "UNREACHABLE";
            }
            delivered.add(deviceToken + " | " + title + " | " + text);
            return "DELIVERED";
        }
    }

    /** The wrapper for that second vendor, also defined here. */
    static final class PushSender implements MessageSender {
        private final PushService service;

        PushSender(PushService service) {
            this.service = service;
        }

        @Override
        public boolean send(String to, String message) {
            return service.push("Alert", message, to).equals("DELIVERED");
        }
    }

    /** Casts through Object so this file compiles even before a class implements MessageSender. */
    static MessageSender asSender(Object o, String className) {
        Check.isTrue(o instanceof MessageSender, className + " must implement MessageSender");
        return (MessageSender) o;
    }

    public static void main(String[] args) {
        // ---- Guard: the old behaviour, through new AlertService() ----
        Check.run("Guard: an alert to a valid number is delivered and recorded", () -> {
            AlertService service = new AlertService();
            Check.isTrue(service.alert("+15550001", "Disk almost full"), "the alert should be delivered");
            Check.eq(List.of("+15550001: Disk almost full"), service.sentLog());
        });
        Check.run("Guard: an invalid number or a text the vendor refuses is reported as not delivered and is not recorded", () -> {
            AlertService service = new AlertService();
            Check.isTrue(!service.alert("bob", "Hello"), "bob is not a phone number");
            Check.isTrue(!service.alert("+15550001", "x".repeat(200)), "the vendor refuses very long texts");
            Check.eq(0, service.sentLog().size());
        });
        Check.run("Guard: alertAll returns the recipients it could not reach, in order, and records the others", () -> {
            AlertService service = new AlertService();
            List<String> failed = service.alertAll(List.of("bob", "+15550002", "alice", "+15550003"), "Backup done");
            Check.eq(List.of("bob", "alice"), failed);
            Check.eq(List.of("+15550002: Backup done", "+15550003: Backup done"), service.sentLog());
        });

        // ---- Step 1: a MessageSender that wraps the vendor's class ----
        Check.run("S1: LegacySmsSender is a MessageSender that passes the message to the vendor with priority 1", () -> {
            LegacySmsGateway gateway = new LegacySmsGateway();
            MessageSender sender = asSender(new LegacySmsSender(gateway), "LegacySmsSender");
            Check.isTrue(sender.send("+15550001", "hello"), "the vendor accepts this one");
            Check.isTrue(sender.send("+15550002", "again"), "the vendor accepts this one too");
            Check.eq(List.of("[1] +15550001: hello", "[1] +15550002: again"), gateway.sentLog());
        });
        Check.run("S1: the vendor's status code becomes false when it refuses the number", () -> {
            LegacySmsGateway gateway = new LegacySmsGateway();
            MessageSender sender = asSender(new LegacySmsSender(gateway), "LegacySmsSender");
            Check.isTrue(!sender.send("bob", "hello"), "status 1 (invalid number) means not sent");
            Check.eq(0, gateway.sentLog().size());
        });
        Check.run("S1: the vendor's status code becomes false when it refuses the text as too long", () -> {
            LegacySmsGateway gateway = new LegacySmsGateway();
            MessageSender sender = asSender(new LegacySmsSender(gateway), "LegacySmsSender");
            Check.isTrue(!sender.send("+15550001", "y".repeat(161)), "status 2 (too long) means not sent");
            Check.isTrue(sender.send("+15550001", "y".repeat(160)), "exactly 160 characters is fine");
            Check.eq(1, gateway.sentLog().size());
        });

        // ---- Step 2: AlertService depends only on MessageSender ----
        Check.run("S2: AlertService sends through whatever MessageSender it is given, with the ALERT: prefix", () -> {
            List<String> calls = new ArrayList<>();
            AlertService service = new AlertService((to, message) -> {
                calls.add(to + " <- " + message);
                return true;
            });
            Check.isTrue(service.alert("+15550001", "Disk almost full"), "the fake sender says true");
            Check.eq(List.of("+15550001 <- ALERT: Disk almost full"), calls);
            Check.eq(List.of("+15550001: Disk almost full"), service.sentLog());
        });
        Check.run("S2: when the sender says false the alert is reported as not delivered and is not recorded", () -> {
            AlertService service = new AlertService((to, message) -> false);
            Check.isTrue(!service.alert("+15550001", "Hello"), "the fake sender says false");
            Check.eq(0, service.sentLog().size());
        });
        Check.run("S2: alertAll works with a fake sender and a real LegacySmsSender wrapped around a gateway of our own", () -> {
            AlertService fake = new AlertService((to, message) -> !to.equals("bad"));
            Check.eq(List.of("bad"), fake.alertAll(List.of("a", "bad", "b"), "Hi"));
            Check.eq(List.of("a: Hi", "b: Hi"), fake.sentLog());

            LegacySmsGateway gateway = new LegacySmsGateway();
            AlertService real = new AlertService(asSender(new LegacySmsSender(gateway), "LegacySmsSender"));
            Check.isTrue(real.alert("+15550009", "Hi"), "delivered through the vendor");
            Check.eq(List.of("[1] +15550009: ALERT: Hi"), gateway.sentLog());
        });

        // ---- Step 3: a second vendor with a different shape, defined in this file ----
        Check.run("S3: a vendor with a completely different API plugs in without editing any existing class", () -> {
            PushService push = new PushService();
            AlertService service = new AlertService(new PushSender(push));
            Check.isTrue(service.alert("dev-1", "Disk almost full"), "the push vendor delivers to dev-1");
            Check.eq(List.of("dev-1 | Alert | ALERT: Disk almost full"), push.delivered);
            Check.eq(List.of("dev-1: Disk almost full"), service.sentLog());
        });
        Check.run("S3: the second vendor's own failure reply is translated into a plain false", () -> {
            PushService push = new PushService();
            AlertService service = new AlertService(new PushSender(push));
            Check.eq(List.of("+15550001"), service.alertAll(List.of("dev-1", "+15550001", "dev-2"), "Hi"));
            Check.eq(List.of("dev-1: Hi", "dev-2: Hi"), service.sentLog());
            Check.eq(2, push.delivered.size());
        });
        Check.run("S3: services with different vendors are independent and new AlertService() still uses the SMS gateway", () -> {
            PushService push = new PushService();
            AlertService viaPush = new AlertService(new PushSender(push));
            AlertService viaSms = new AlertService();
            Check.isTrue(viaSms.alert("+15550001", "Hi"), "SMS numbers work with the default service");
            Check.isTrue(!viaSms.alert("dev-1", "Hi"), "device ids are not phone numbers");
            Check.isTrue(!viaPush.alert("+15550001", "Hi"), "phone numbers are not device ids");
            Check.eq(List.of("+15550001: Hi"), viaSms.sentLog());
            Check.eq(0, viaPush.sentLog().size());
        });
    }
}
