import java.lang.reflect.Field;
import java.lang.reflect.Method;
import java.lang.reflect.Modifier;

public class TestMain {
    private static final double EPS = 1e-9;

    /** Runs the body and returns the message of the IllegalStateException it must throw. */
    static String stateErrorOf(Check.Body body) {
        try {
            body.run();
        } catch (IllegalStateException e) {
            return e.getMessage();
        } catch (Throwable t) {
            throw new AssertionError("expected IllegalStateException but got " + t);
        }
        throw new AssertionError("expected IllegalStateException but nothing was thrown");
    }

    /** A helper that exists only in this test file: a children's menu, filled into any builder. */
    static MealOrder.Builder kidsMenu(MealOrder.Builder b) {
        return b.mainDish("Nuggets").side("Fries").drink("Juice").size("S");
    }

    /** Another helper defined only here: a quick lunch to take away. */
    static MealOrder.Builder lunchToGo(MealOrder.Builder b) {
        return b.mainDish("Salad").side("none").drink("Cola").takeaway(true);
    }

    public static void main(String[] args) {
        // ---- Guard: the old behaviour, through the old constructors ----
        Check.run("Guard: a main dish alone gives the default side, drink and size", () -> {
            MealOrder order = new MealOrder("Burger");
            Check.eq("Burger + none + water (M)", order.summary());
            Check.eq(9.5, order.total(), EPS);
        });
        Check.run("Guard: the short constructors take main dish, side, drink and size in that order", () -> {
            Check.eq("Pizza + Salad + Cola (M)", new MealOrder("Pizza", "Salad", "Cola").summary());
            Check.eq(14.0, new MealOrder("Pizza", "Salad", "Cola").total(), EPS);
            Check.eq("Burger + Fries + water (L)", new MealOrder("Burger", "Fries", "water", "L").summary());
            Check.eq(13.5, new MealOrder("Burger", "Fries", "water", "L").total(), EPS);
        });
        Check.run("Guard: the long constructor adds sauce, takeaway or table, and a note to the summary and total", () -> {
            MealOrder dineIn = new MealOrder("Burger", "Fries", "Cola", "L", true, false, 12, "no onions");
            Check.eq("Burger + Fries + Cola (L), extra sauce, table 12, note: no onions", dineIn.summary());
            Check.eq(15.0, dineIn.total(), EPS);
            MealOrder toGo = new MealOrder("Salad", "none", "water", "S", false, true, 0, "");
            Check.eq("Salad + none + water (S), takeaway", toGo.summary());
            Check.eq(6.75, toGo.total(), EPS);
        });

        // ---- step 1: a nested Builder with one named setter per value ----
        Check.run("S1: Builder.build() gives the same order as the long constructor", () -> {
            MealOrder built = new MealOrder.Builder()
                    .mainDish("Burger").side("Fries").drink("Cola").size("L")
                    .extraSauce(true).takeaway(false).tableNumber(12).note("no onions")
                    .build();
            MealOrder old = new MealOrder("Burger", "Fries", "Cola", "L", true, false, 12, "no onions");
            Check.eq(old.summary(), built.summary());
            Check.eq(old.total(), built.total(), EPS);
            Check.eq("Burger + Fries + Cola (L), extra sauce, table 12, note: no onions", built.summary());
        });
        Check.run("S1: every setter returns the same Builder, so the calls can be chained in any order", () -> {
            MealOrder.Builder b = new MealOrder.Builder();
            Check.isTrue(b.mainDish("Pizza") == b, "mainDish must return this");
            Check.isTrue(b.side("Salad") == b, "side must return this");
            Check.isTrue(b.drink("Cola") == b, "drink must return this");
            Check.isTrue(b.size("S") == b, "size must return this");
            Check.isTrue(b.extraSauce(false) == b, "extraSauce must return this");
            Check.isTrue(b.takeaway(true) == b, "takeaway must return this");
            Check.isTrue(b.tableNumber(0) == b, "tableNumber must return this");
            Check.isTrue(b.note("") == b, "note must return this");
            MealOrder order = new MealOrder.Builder()
                    .note("").tableNumber(0).takeaway(true).extraSauce(false)
                    .size("S").drink("Cola").side("Salad").mainDish("Pizza")
                    .build();
            Check.eq(new MealOrder("Pizza", "Salad", "Cola", "S", false, true, 0, "").summary(), order.summary());
            Check.eq(9.0 + 3.0 + 2.0 - 1.0 + 0.25, order.total(), EPS);
        });
        Check.run("S1: two builders do not share their values", () -> {
            MealOrder.Builder first = new MealOrder.Builder().mainDish("Burger").side("Fries").drink("Cola").size("M")
                    .extraSauce(false).takeaway(true).tableNumber(0).note("");
            MealOrder.Builder second = new MealOrder.Builder().mainDish("Salad").side("none").drink("water").size("S")
                    .extraSauce(false).takeaway(true).tableNumber(0).note("");
            Check.eq("Burger + Fries + Cola (M), takeaway", first.build().summary());
            Check.eq("Salad + none + water (S), takeaway", second.build().summary());
        });

        // ---- step 2: build() refuses an order that makes no sense ----
        Check.run("S2: a missing main dish is an IllegalStateException with the exact message", () -> {
            Check.eq("A meal needs a main dish", stateErrorOf(() -> new MealOrder.Builder().build()));
            Check.eq("A meal needs a main dish", stateErrorOf(() -> new MealOrder.Builder().side("Fries").drink("Cola").build()));
            Check.eq("A meal needs a main dish", stateErrorOf(() -> new MealOrder.Builder().mainDish("   ").build()));
        });
        Check.run("S2: a negative table number is rejected with the exact message", () -> {
            Check.eq("Table number cannot be negative",
                    stateErrorOf(() -> new MealOrder.Builder().mainDish("Burger").tableNumber(-3).build()));
        });
        Check.run("S2: a size other than S, M or L is rejected, and its message names the size", () -> {
            Check.eq("Unknown size: XL", stateErrorOf(() -> new MealOrder.Builder().mainDish("Burger").size("XL").build()));
            Check.eq("Unknown size: m", stateErrorOf(() -> new MealOrder.Builder().mainDish("Burger").size("m").build()));
            for (String ok : new String[] {"S", "M", "L"}) {
                Check.isTrue(new MealOrder.Builder().mainDish("Burger").size(ok).build() != null, "size " + ok + " must be accepted");
            }
        });
        Check.run("S2: the checks run in this order: main dish, then table number, then size", () -> {
            Check.eq("A meal needs a main dish",
                    stateErrorOf(() -> new MealOrder.Builder().tableNumber(-1).size("XL").build()));
            Check.eq("Table number cannot be negative",
                    stateErrorOf(() -> new MealOrder.Builder().mainDish("Burger").tableNumber(-1).size("XL").build()));
        });
        Check.run("S2: a valid order still builds and the old constructors do not validate", () -> {
            MealOrder order = new MealOrder.Builder().mainDish("Pizza").build();
            Check.isTrue(order != null, "an order with only a main dish must build");
            Check.eq("Pizza + none + water (M)", new MealOrder("Pizza").summary());
        });

        // ---- step 3: optional values have defaults ----
        Check.run("S3: with only a main dish the optional values take their defaults", () -> {
            MealOrder order = new MealOrder.Builder().mainDish("Burger").build();
            Check.eq("Burger + none + water (M)", order.summary());
            Check.eq(9.5, order.total(), EPS);
        });
        Check.run("S3: a builder with defaults gives the same order as the matching old constructor", () -> {
            MealOrder built = new MealOrder.Builder().mainDish("Pizza").side("Salad").drink("Cola").build();
            Check.eq(new MealOrder("Pizza", "Salad", "Cola").summary(), built.summary());
            Check.eq(new MealOrder("Pizza", "Salad", "Cola").total(), built.total(), EPS);
        });
        Check.run("S3: values you set override the defaults one by one", () -> {
            MealOrder sauce = new MealOrder.Builder().mainDish("Burger").extraSauce(true).build();
            Check.eq("Burger + none + water (M), extra sauce", sauce.summary());
            Check.eq(10.0, sauce.total(), EPS);
            MealOrder small = new MealOrder.Builder().mainDish("Burger").size("S").drink("Juice").build();
            Check.eq("Burger + none + Juice (S)", small.summary());
            Check.eq(10.0, small.total(), EPS);
            MealOrder table = new MealOrder.Builder().mainDish("Burger").tableNumber(5).note("window seat").build();
            Check.eq("Burger + none + water (M), table 5, note: window seat", table.summary());
        });
        Check.run("S3: defaults do not switch validation off", () -> {
            Check.eq("A meal needs a main dish", stateErrorOf(() -> new MealOrder.Builder().build()));
            Check.eq("Unknown size: XL", stateErrorOf(() -> new MealOrder.Builder().mainDish("Burger").size("XL").build()));
        });

        // ---- step 4: the order is immutable, and new presets need no change to existing classes ----
        Check.run("S4: every field of MealOrder is final", () -> {
            for (Field f : MealOrder.class.getDeclaredFields()) {
                if (Modifier.isStatic(f.getModifiers()) || f.isSynthetic()) continue;
                Check.isTrue(Modifier.isFinal(f.getModifiers()), "field " + f.getName() + " must be final");
            }
        });
        Check.run("S4: MealOrder has no setter methods any more", () -> {
            for (Method m : MealOrder.class.getDeclaredMethods()) {
                Check.isTrue(!m.getName().startsWith("set"), "remove the setter " + m.getName());
            }
        });
        Check.run("S4: an order is not changed by later calls on the builder that made it", () -> {
            MealOrder.Builder b = new MealOrder.Builder().mainDish("Burger").tableNumber(1);
            MealOrder first = b.build();
            b.mainDish("Pizza").tableNumber(2).extraSauce(true);
            MealOrder second = b.build();
            Check.eq("Burger + none + water (M), table 1", first.summary());
            Check.eq("Pizza + none + water (M), extra sauce, table 2", second.summary());
        });
        Check.run("S4: a children's menu defined in the test fills any builder and the table is added afterwards", () -> {
            MealOrder order = kidsMenu(new MealOrder.Builder()).tableNumber(4).build();
            Check.eq("Nuggets + Fries + Juice (S), table 4", order.summary());
            Check.eq(9.0, order.total(), EPS);
        });
        Check.run("S4: a test-defined preset can be overridden and combined with another one", () -> {
            MealOrder bigger = kidsMenu(new MealOrder.Builder()).size("M").extraSauce(true).build();
            Check.eq("Nuggets + Fries + Juice (M), extra sauce", bigger.summary());
            Check.eq(10.5, bigger.total(), EPS);
            MealOrder lunch = lunchToGo(new MealOrder.Builder()).note("no dressing").build();
            Check.eq("Salad + none + Cola (M), takeaway, note: no dressing", lunch.summary());
            Check.eq(8.75, lunch.total(), EPS);
        });
        Check.run("S4: one builder can produce a whole row of tables, and the presets still get validated", () -> {
            MealOrder.Builder b = kidsMenu(new MealOrder.Builder());
            StringBuilder out = new StringBuilder();
            for (int table = 1; table <= 3; table++) out.append(b.tableNumber(table).build().summary().replace("Nuggets + Fries + Juice (S), ", "")).append(";");
            Check.eq("table 1;table 2;table 3;", out.toString());
            Check.eq("Unknown size: XXL", stateErrorOf(() -> kidsMenu(new MealOrder.Builder()).size("XXL").build()));
        });
    }
}
