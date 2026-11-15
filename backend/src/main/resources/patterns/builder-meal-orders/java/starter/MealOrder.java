/**
 * One order at the restaurant counter. It works today, but every new option has meant another constructor,
 * and the orders can still be changed after they were created.
 */
public class MealOrder {
    private String mainDish;
    private String side;
    private String drink;
    private String size;          // "S", "M" or "L"
    private boolean extraSauce;
    private boolean takeaway;
    private int tableNumber;      // 0 means no table
    private String note;

    // TODO (step 4): once the orders are created through the Builder, make every field above final and delete the setters below

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

    public void setTableNumber(int tableNumber) { this.tableNumber = tableNumber; }

    public void setExtraSauce(boolean extraSauce) { this.extraSauce = extraSauce; }

    public void setNote(String note) { this.note = note; }

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

    /** Collects the values for an order one at a time. */
    public static class Builder {
        // TODO (step 3): give the optional values their defaults: side "none", drink "water", size "M", no sauce, not takeaway, table 0, note ""

        // TODO (step 1): remember the value and return this builder, for each of the eight setters below
        public Builder mainDish(String mainDish) { return this; }

        public Builder side(String side) { return this; }

        public Builder drink(String drink) { return this; }

        public Builder size(String size) { return this; }

        public Builder extraSauce(boolean extraSauce) { return this; }

        public Builder takeaway(boolean takeaway) { return this; }

        public Builder tableNumber(int tableNumber) { return this; }

        public Builder note(String note) { return this; }

        // TODO (step 1): create the MealOrder from the collected values with the long constructor
        // TODO (step 2): check the values first and throw IllegalStateException for a missing main dish, a negative table number or an unknown size
        public MealOrder build() {
            throw new UnsupportedOperationException("TODO (step 1)");
        }
    }
}
