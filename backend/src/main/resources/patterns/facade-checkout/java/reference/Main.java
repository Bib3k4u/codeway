public class Main {
    public static void main(String[] args) {
        WebCheckout web = new WebCheckout();
        System.out.println(web.submit("BOOK", 2, "ALICE", "12 Oak St"));
        System.out.println(web.submit("BOOK", 1, "EMPTY", "12 Oak St"));
        System.out.println("web stock BOOK: " + web.stockLeft("BOOK"));

        PhoneCheckout phone = new PhoneCheckout();
        System.out.println(phone.takeOrder("LAMP", 1, "BOB", "9 Elm Rd"));
        System.out.println(phone.takeOrder("LAMP", 2, "BOB", "9 Elm Rd"));
        System.out.println(phone.takeOrder("DESK", 1, "EMPTY", "9 Elm Rd"));

        // A staff order
        System.out.println("STAFF OK " + new OrderDesk().placeOrder("LAMP", 1, "STAFF", "1 Office Rd"));
    }
}
