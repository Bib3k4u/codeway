import java.lang.reflect.Constructor;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class TestMain {
    /** A brand-new part of the app, defined here and not in the learner's code: it reads the shared settings every time it renders. */
    static final class ThemeBadge {
        String render() {
            AppSettings s = AppSettings.getInstance();
            return "[" + s.get("theme") + "/" + s.get("language") + "]";
        }
    }

    /** Another new part: it changes a shared value. */
    static final class CurrencySwitcher {
        void use(String currency) {
            AppSettings.getInstance().set("currency", currency);
        }
    }

    public static void main(String[] args) {
        // ---- Guard: the old behaviour, through the services' public API ----
        Check.run("Guard: a fresh report service shows the default theme, language and currency", () -> {
            AppSettings.resetForTests();
            ReportService report = new ReportService();
            Check.eq("== Report (light, en) ==", report.header());
            Check.eq("12.5 EUR", report.money(12.5));
        });
        Check.run("Guard: the greeting follows the language, and an unknown language falls back to English", () -> {
            AppSettings.resetForTests();
            GreetingService greeting = new GreetingService();
            Check.eq("Hello, Ada!", greeting.greet("Ada"));
            greeting.useLanguage("fr");
            Check.eq("Bonjour, Ada!", greeting.greet("Ada"));
            greeting.useLanguage("de");
            Check.eq("Hallo, Bo!", greeting.greet("Bo"));
            greeting.useLanguage("xx");
            Check.eq("Hello, Bo!", greeting.greet("Bo"));
        });
        Check.run("Guard: a service sees its own changes", () -> {
            AppSettings.resetForTests();
            ReportService report = new ReportService();
            report.useTheme("dark");
            Check.eq("== Report (dark, en) ==", report.header());
        });

        // ---- Step 1: one shared AppSettings, created on first use ----
        Check.run("S1: getInstance returns the same object every time", () -> {
            AppSettings.resetForTests();
            AppSettings a = AppSettings.getInstance();
            AppSettings b = AppSettings.getInstance();
            Check.isTrue(a != null && a == b, "getInstance() must return the same AppSettings object on every call");
            Check.eq("light", a.get("theme"));
        });
        Check.run("S1: a value set through one reference is seen through the other", () -> {
            AppSettings.resetForTests();
            AppSettings.getInstance().set("theme", "dark");
            Check.eq("dark", AppSettings.getInstance().get("theme"));
        });
        Check.run("S1: the settings are created only when first asked for, and loaded once", () -> {
            AppSettings.resetForTests();
            Check.eq(0, AppSettings.loadCount());
            AppSettings.getInstance();
            AppSettings.getInstance();
            AppSettings.getInstance();
            Check.eq(1, AppSettings.loadCount());
        });
        Check.run("S1: resetForTests gives the next getInstance a fresh object with the default values", () -> {
            AppSettings.resetForTests();
            AppSettings first = AppSettings.getInstance();
            first.set("language", "de");
            AppSettings.resetForTests();
            AppSettings second = AppSettings.getInstance();
            Check.isTrue(first != second, "after resetForTests, getInstance() must build a new AppSettings");
            Check.eq("en", second.get("language"));
            Check.eq(1, AppSettings.loadCount());
        });

        // ---- Step 2: the services share it ----
        Check.run("S2: a theme and a language changed through different services are seen by both", () -> {
            AppSettings.resetForTests();
            ReportService report = new ReportService();
            GreetingService greeting = new GreetingService();
            report.useTheme("dark");
            greeting.useLanguage("fr");
            Check.eq("== Report (dark, fr) ==", report.header());
            Check.eq("Bonjour, Ada!", greeting.greet("Ada"));
        });
        Check.run("S2: a service created later sees the values changed earlier", () -> {
            AppSettings.resetForTests();
            new GreetingService().useLanguage("de");
            Check.eq("== Report (light, de) ==", new ReportService().header());
        });
        Check.run("S2: creating several services loads the settings only once", () -> {
            AppSettings.resetForTests();
            new ReportService();
            new GreetingService();
            new ReportService();
            new GreetingService();
            Check.eq(1, AppSettings.loadCount());
        });

        // ---- Step 3: no way to build another copy ----
        Check.run("S3: every constructor of AppSettings is private", () -> {
            Constructor<?>[] all = AppSettings.class.getDeclaredConstructors();
            Check.isTrue(all.length > 0, "AppSettings needs a constructor");
            for (Constructor<?> c : all) {
                Check.isTrue(Modifier.isPrivate(c.getModifiers()), "the constructor of AppSettings must be private");
            }
        });
        Check.run("S3: getInstance is a public static method that returns AppSettings", () -> {
            Method m = AppSettings.class.getDeclaredMethod("getInstance");
            Check.isTrue(Modifier.isPublic(m.getModifiers()) && Modifier.isStatic(m.getModifiers()), "getInstance() must be public static");
            Check.isTrue(m.getReturnType() == AppSettings.class, "getInstance() must return AppSettings");
        });
        Check.run("S3: the services keep working and still share the one object", () -> {
            AppSettings.resetForTests();
            AppSettings before = AppSettings.getInstance();
            ReportService report = new ReportService();
            GreetingService greeting = new GreetingService();
            report.useTheme("sepia");
            Check.isTrue(before == AppSettings.getInstance(), "creating services must not replace the shared object");
            Check.eq("sepia", before.get("theme"));
            Check.eq("Hello, Ada!", greeting.greet("Ada"));
            Check.eq(1, AppSettings.loadCount());
        });

        // ---- Step 4: new parts defined in this file reach the same settings without changing existing classes ----
        Check.run("S4: a new part sees what the services changed", () -> {
            AppSettings.resetForTests();
            ThemeBadge badge = new ThemeBadge();
            Check.eq("[light/en]", badge.render());
            new ReportService().useTheme("dark");
            new GreetingService().useLanguage("fr");
            Check.eq("[dark/fr]", badge.render());
        });
        Check.run("S4: a new part can change a value that the existing services then use", () -> {
            AppSettings.resetForTests();
            ReportService report = new ReportService();
            new CurrencySwitcher().use("USD");
            Check.eq("3.0 USD", report.money(3.0));
        });
        Check.run("S4: old and new parts together still load the settings only once", () -> {
            AppSettings.resetForTests();
            ReportService report = new ReportService();
            GreetingService greeting = new GreetingService();
            ThemeBadge badge = new ThemeBadge();
            new CurrencySwitcher().use("GBP");
            greeting.useLanguage("de");
            Check.eq("[light/de]", badge.render());
            Check.eq("== Report (light, de) ==", report.header());
            Check.eq("2.5 GBP", report.money(2.5));
            Check.eq(1, AppSettings.loadCount());
        });
    }
}
