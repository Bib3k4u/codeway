import java.util.Locale;

public class Main {
    public static void main(String[] args) {
        MealOrder quick = new MealOrder("Burger");
        MealOrder lunch = new MealOrder("Pizza", "Salad", "Cola");
        MealOrder big = new MealOrder.Builder()
                .mainDish("Burger").side("Fries").drink("Cola").size("L")
                .extraSauce(true).tableNumber(12).note("no onions")
                .build();
        MealOrder togo = new MealOrder.Builder()
                .mainDish("Salad").size("S").takeaway(true)
                .build();
        print(quick);
        print(lunch);
        print(big);
        print(togo);
    }

    private static void print(MealOrder order) {
        System.out.println(order.summary() + " = " + String.format(Locale.ROOT, "%.2f", order.total()));
    }
}
