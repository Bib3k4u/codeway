public class Main {
    public static void main(String[] args) {
        ShippingCalculator calculator = new ShippingCalculator();
        System.out.println("standard 2.0 kg: " + calculator.cost("standard", 2.0));
        System.out.println("express 4.0 kg: " + calculator.cost("express", 4.0));
        System.out.println("overnight 1.5 kg: " + calculator.cost("overnight", 1.5));
        System.out.println("standard 10.0 kg: " + calculator.cost("standard", 10.0));
    }
}
