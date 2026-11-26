public class Main {
    public static void main(String[] args) {
        HomePage home = new HomePage();
        AboutPage about = new AboutPage();
        home.open();
        home.open();
        about.open();
        System.out.println("Home sees " + home.totalVisits() + " visits");
        System.out.println("About sees " + about.totalVisits() + " visits");
    }
}
