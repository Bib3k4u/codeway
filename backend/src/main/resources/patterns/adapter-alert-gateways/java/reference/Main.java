import java.util.List;

public class Main {
    public static void main(String[] args) {
        AlertService service = new AlertService();
        System.out.println("alert 1: " + service.alert("+15550001", "Disk almost full"));
        System.out.println("alert 2: " + service.alert("bob", "Nobody to call"));
        System.out.println("failed: " + service.alertAll(List.of("+15550002", "bob", "+15550003"), "Backup done"));
        for (String line : service.sentLog()) {
            System.out.println("sent: " + line);
        }
    }
}
