import java.lang.reflect.Field;
import java.util.ArrayList;
import java.util.List;

public class TestMain {
    /** Test-defined stand-ins for the four subsystems (used by the step 4 tests). Each one writes what it was asked into a shared log. */
    static final class FakeInventory implements Inventory {
        final List<String> log;
        boolean failReserve = false;

        FakeInventory(List<String> log) { this.log = log; }

        public void reserve(String sku, int qty) {
            log.add("reserve " + sku + " x" + qty);
            if (failReserve) throw new IllegalStateException("fake out of stock");
        }
        public void release(String sku, int qty) { log.add("release " + sku + " x" + qty); }
        public int available(String sku) { return 10; }
        public int unitPrice(String sku) { return 100; }
    }

    static final class FakePayments implements PaymentGateway {
        final List<String> log;
        boolean decline = false;

        FakePayments(List<String> log) { this.log = log; }

        public void charge(String account, int cents) {
            log.add("charge " + account + " " + cents);
            if (decline) throw new IllegalStateException("fake declined");
        }
    }

    static final class FakeShipping implements ShippingService {
        final List<String> log;

        FakeShipping(List<String> log) { this.log = log; }

        public void schedule(String address, String sku) { log.add("schedule " + address + " " + sku); }
    }

    static final class FakeReceipts implements Receipts {
        final List<String> log;

        FakeReceipts(List<String> log) { this.log = log; }

        public String issue(String account, int totalCents) {
            log.add("issue " + account + " " + totalCents);
            return "FAKE-1";
        }
    }

    /** True when a class has a field whose type is one of the subsystem types or their in-memory versions. */
    static boolean holdsSubsystem(Class<?> c) {
        for (Field f : c.getDeclaredFields()) {
            Class<?> t = f.getType();
            if (Inventory.class.isAssignableFrom(t) || PaymentGateway.class.isAssignableFrom(t)
                    || ShippingService.class.isAssignableFrom(t) || Receipts.class.isAssignableFrom(t)) {
                return true;
            }
        }
        return false;
    }

    public static void main(String[] args) {
        // ---- Guard: the old behaviour, through WebCheckout.submit and PhoneCheckout.takeOrder ----
        Check.run("Guard: a web order is confirmed with a receipt id and takes the items out of stock", () -> {
            WebCheckout web = new WebCheckout();
            Check.eq("WEB OK R-1", web.submit("BOOK", 2, "ALICE", "12 Oak St"));
            Check.eq(3, web.stockLeft("BOOK"));
            Check.eq("WEB OK R-2", web.submit("BOOK", 1, "ALICE", "12 Oak St"));
        });
        Check.run("Guard: a web order that is declined or out of stock reports why and leaves the stock as it was", () -> {
            WebCheckout web = new WebCheckout();
            Check.eq("WEB FAILED Payment declined for EMPTY", web.submit("BOOK", 2, "EMPTY", "12 Oak St"));
            Check.eq(5, web.stockLeft("BOOK"));
            Check.eq("WEB FAILED Out of stock: LAMP", web.submit("LAMP", 3, "ALICE", "12 Oak St"));
            Check.eq(2, web.stockLeft("LAMP"));
        });
        Check.run("Guard: a phone order is confirmed, or politely refused when the item is out of stock or the payment is declined", () -> {
            PhoneCheckout phone = new PhoneCheckout();
            Check.eq("PHONE: confirmed R-1", phone.takeOrder("LAMP", 1, "BOB", "9 Elm Rd"));
            Check.eq("PHONE: sorry, Out of stock: LAMP", phone.takeOrder("LAMP", 2, "BOB", "9 Elm Rd"));
            Check.eq("PHONE: sorry, Payment declined for EMPTY", phone.takeOrder("BOOK", 1, "EMPTY", "9 Elm Rd"));
            Check.eq("PHONE: confirmed R-2", phone.takeOrder("BOOK", 1, "BOB", "9 Elm Rd"));
        });

        // ---- Step 1: OrderDesk.placeOrder hides the sequence ----
        Check.run("S1: placeOrder returns a receipt id and the items leave the stock", () -> {
            OrderDesk desk = new OrderDesk();
            Check.eq("R-1", desk.placeOrder("BOOK", 2, "ALICE", "12 Oak St"));
            Check.eq(3, desk.stockLeft("BOOK"));
            Check.eq("R-2", desk.placeOrder("DESK", 1, "ALICE", "12 Oak St"));
            Check.eq(0, desk.stockLeft("DESK"));
        });
        Check.run("S1: placeOrder throws IllegalStateException when there is not enough stock, and the stock is unchanged", () -> {
            OrderDesk desk = new OrderDesk();
            Check.throwsEx(IllegalStateException.class, () -> desk.placeOrder("LAMP", 3, "ALICE", "12 Oak St"));
            Check.eq(2, desk.stockLeft("LAMP"));
        });
        Check.run("S1: two desks do not share stock", () -> {
            OrderDesk one = new OrderDesk();
            OrderDesk two = new OrderDesk();
            one.placeOrder("DESK", 1, "ALICE", "12 Oak St");
            Check.eq(1, two.stockLeft("DESK"));
            Check.eq("R-1", two.placeOrder("DESK", 1, "BOB", "9 Elm Rd"));
        });

        // ---- Step 2: a declined payment gives the reserved stock back ----
        Check.run("S2: a declined payment throws IllegalStateException with the gateway's message", () -> {
            OrderDesk desk = new OrderDesk();
            try {
                desk.placeOrder("BOOK", 2, "EMPTY", "12 Oak St");
                Check.isTrue(false, "placeOrder should have thrown for the account EMPTY");
            } catch (IllegalStateException e) {
                Check.eq("Payment declined for EMPTY", e.getMessage());
            }
        });
        Check.run("S2: after a declined payment all the reserved items are back in stock and the next order works", () -> {
            OrderDesk desk = new OrderDesk();
            Check.throwsEx(IllegalStateException.class, () -> desk.placeOrder("BOOK", 4, "EMPTY", "12 Oak St"));
            Check.eq(5, desk.stockLeft("BOOK"));
            Check.eq("R-1", desk.placeOrder("BOOK", 5, "ALICE", "12 Oak St"));
            Check.eq(0, desk.stockLeft("BOOK"));
        });
        Check.run("S2: an order that is out of stock gives nothing back (nothing was reserved)", () -> {
            OrderDesk desk = new OrderDesk();
            Check.throwsEx(IllegalStateException.class, () -> desk.placeOrder("LAMP", 3, "ALICE", "12 Oak St"));
            Check.eq(2, desk.stockLeft("LAMP"));
        });

        // ---- Step 3: the existing callers delegate to OrderDesk ----
        Check.run("S3: a declined phone payment no longer leaves the stock reserved", () -> {
            PhoneCheckout phone = new PhoneCheckout();
            Check.eq("PHONE: sorry, Payment declined for EMPTY", phone.takeOrder("DESK", 1, "EMPTY", "9 Elm Rd"));
            Check.eq(1, phone.stockLeft("DESK"));
            Check.eq("PHONE: confirmed R-1", phone.takeOrder("DESK", 1, "BOB", "9 Elm Rd"));
        });
        Check.run("S3: WebCheckout no longer holds any of the four subsystems itself", () -> {
            Check.isTrue(!holdsSubsystem(WebCheckout.class), "WebCheckout still has a field of a subsystem type; use an OrderDesk instead");
        });
        Check.run("S3: PhoneCheckout no longer holds any of the four subsystems itself", () -> {
            Check.isTrue(!holdsSubsystem(PhoneCheckout.class), "PhoneCheckout still has a field of a subsystem type; use an OrderDesk instead");
        });

        // ---- Step 4: the desk works with any subsystems, so stand-ins defined in this file can record what it does ----
        Check.run("S4: a successful order calls the four subsystems in the right order and returns the receipt id", () -> {
            List<String> log = new ArrayList<>();
            OrderDesk desk = new OrderDesk(new FakeInventory(log), new FakePayments(log), new FakeShipping(log), new FakeReceipts(log));
            Check.eq("FAKE-1", desk.placeOrder("BOOK", 3, "ALICE", "1 Main St"));
            Check.eq(List.of("reserve BOOK x3", "charge ALICE 300", "schedule 1 Main St BOOK", "issue ALICE 300"), log);
        });
        Check.run("S4: a declined payment releases the reservation, stops there, and passes the failure on", () -> {
            List<String> log = new ArrayList<>();
            FakePayments payments = new FakePayments(log);
            payments.decline = true;
            OrderDesk desk = new OrderDesk(new FakeInventory(log), payments, new FakeShipping(log), new FakeReceipts(log));
            Check.throwsEx(IllegalStateException.class, () -> desk.placeOrder("BOOK", 3, "ALICE", "1 Main St"));
            Check.eq(List.of("reserve BOOK x3", "charge ALICE 300", "release BOOK x3"), log);
        });
        Check.run("S4: when the reservation itself fails nothing else is called", () -> {
            List<String> log = new ArrayList<>();
            FakeInventory inventory = new FakeInventory(log);
            inventory.failReserve = true;
            OrderDesk desk = new OrderDesk(inventory, new FakePayments(log), new FakeShipping(log), new FakeReceipts(log));
            Check.throwsEx(IllegalStateException.class, () -> desk.placeOrder("BOOK", 3, "ALICE", "1 Main St"));
            Check.eq(List.of("reserve BOOK x3"), log);
        });
        Check.run("S4: the no-argument constructor still gives a desk with the real in-memory subsystems", () -> {
            OrderDesk desk = new OrderDesk();
            Check.eq("R-1", desk.placeOrder("BOOK", 1, "ALICE", "12 Oak St"));
            Check.eq(4, desk.stockLeft("BOOK"));
        });
    }
}
