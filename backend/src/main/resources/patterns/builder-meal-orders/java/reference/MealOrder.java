/**
 * One order at the restaurant counter. Create it with the nested Builder, which names every value and checks them in build().
 * The older constructors still work, and an order can no longer be changed once it exists.
 */
public class MealOrder {
    private final String mainDish;
    private final String side;
    private final String drink;
    private final String size;          // "S", "M" or "L"
    private final boolean extraSauce;
    private final boolean takeaway;
    private final int tableNumber;      // 0 means no table
    private final String note;

    public MealOrder(String mainDish) {
        this(mainDish, "none");
    }

    public MealOrder(String mainDish, String side) {
        this(mainDish, side, "water");
    }

    public MealOrder(String mainDish, String side, String drink) {
        this(mainDish, side, drink, "M");
    }

    public MealOrder(String mainDish, String side, String drink, String size) {
        this(mainDish, side, drink, size, false, false, 0, "");
    }

    public MealOrder(String mainDish, String side, String drink, String size,
                     boolean extraSauce, boolean takeaway, int tableNumber, String note) {
        this.mainDish = mainDish;
        this.side = side;
        this.drink = drink;
        this.size = size;
        this.extraSauce = extraSauce;
        this.takeaway = takeaway;
        this.tableNumber = tableNumber;
        this.note = note;
    }

    public double total() {
        double sum = mainPrice() + sidePrice() + drinkPrice();
        if (size.equals("S")) sum -= 1.0;
        if (size.equals("L")) sum += 1.5;
        if (extraSauce) sum += 0.5;
        if (takeaway) sum += 0.25;
        return sum;
    }

    public String summary() {
        String text = mainDish + " + " + side + " + " + drink + " (" + size + ")";
        if (extraSauce) text += ", extra sauce";
        if (takeaway) text += ", takeaway";
        else if (tableNumber > 0) text += ", table " + tableNumber;
        if (note != null && !note.isEmpty()) text += ", note: " + note;
        return text;
    }

    private double mainPrice() {
        switch (mainDish) {
            case "Burger": return 8.5;
            case "Pizza": return 9.0;
            case "Salad": return 6.5;
            case "Nuggets": return 5.0;
            default: return 7.0;
        }
    }

    private double sidePrice() {
        switch (side) {
            case "none": return 0.0;
            case "Fries": return 2.5;
            case "Salad": return 3.0;
            default: return 2.0;
        }
    }

    private double drinkPrice() {
        switch (drink) {
            case "water": return 1.0;
            case "Cola": return 2.0;
            default: return 2.5;
        }
    }

    /** Collects the values for an order one at a time; build() checks them and creates the order. */
    public static class Builder {
        private String mainDish;
        private String side = "none";
        private String drink = "water";
        private String size = "M";
        private boolean extraSauce;
        private boolean takeaway;
        private int tableNumber;
        private String note = "";

        public Builder mainDish(String mainDish) { this.mainDish = mainDish; return this; }

        public Builder side(String side) { this.side = side; return this; }

        public Builder drink(String drink) { this.drink = drink; return this; }

        public Builder size(String size) { this.size = size; return this; }

        public Builder extraSauce(boolean extraSauce) { this.extraSauce = extraSauce; return this; }

        public Builder takeaway(boolean takeaway) { this.takeaway = takeaway; return this; }

        public Builder tableNumber(int tableNumber) { this.tableNumber = tableNumber; return this; }

        public Builder note(String note) { this.note = note; return this; }

        public MealOrder build() {
            if (mainDish == null || mainDish.isBlank()) throw new IllegalStateException("A meal needs a main dish");
            if (tableNumber < 0) throw new IllegalStateException("Table number cannot be negative");
            if (size != null && !size.equals("S") && !size.equals("M") && !size.equals("L")) {
                throw new IllegalStateException("Unknown size: " + size);
            }
            return new MealOrder(mainDish, side, drink, size, extraSauce, takeaway, tableNumber, note);
        }
    }
}
