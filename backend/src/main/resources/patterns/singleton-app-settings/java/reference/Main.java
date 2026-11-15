public class Main {
    public static void main(String[] args) {
        ReportService report = new ReportService();
        GreetingService greeting = new GreetingService();
        System.out.println(report.header());
        System.out.println(greeting.greet("Ada"));
        report.useTheme("dark");
        greeting.useLanguage("fr");
        System.out.println(report.header());
        System.out.println(greeting.greet("Ada"));
        System.out.println(report.money(12.5));
        System.out.println("Settings loaded " + AppSettings.loadCount() + " time(s)");
    }
}
