public class Main {
    public static void main(String[] args) {
        PriceFeed feed = new PriceFeed();
        feed.update("ACME", 90.0);
        feed.update("ACME", 120.0);
        feed.update("ZETA", 40.0);
        feed.update("ACME", 110.0);
        feed.update("ZETA", 45.5);
        feed.output().forEach(System.out::println);
    }
}
