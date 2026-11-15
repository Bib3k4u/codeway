import java.util.Locale;

public class Main {
    public static void main(String[] args) {
        MealOrder quick = new MealOrder("Burger");
        MealOrder lunch = new MealOrder("Pizza", "Salad", "Cola");
        MealOrder big = new MealOrder("Burger", "Fries", "Cola", "L", true, false, 12, "no onions");
        MealOrder togo = new MealOrder("Salad", "none", "water", "S", false, true, 0, "");
        print(quick);
        print(lunch);
        print(big);
        print(togo);
    }

    private static void print(MealOrder order) {
        System.out.println(order.summary() + " = " + String.format(Locale.ROOT, "%.2f", order.total()));
    }
}
