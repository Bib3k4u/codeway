package com.codeway.seed;

import com.codeway.domain.Challenge;
import com.codeway.repo.ChallengeRepo;
import com.codeway.service.*;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;
import java.util.*;

/** Hardcoded Java challenges. Each seed is validated through the runner at startup like AI output would be. */
@Component
public class SeedChallenges implements ApplicationRunner {
    private final ChallengeRepo repo;
    private final ChallengeService service;

    public SeedChallenges(ChallengeRepo repo, ChallengeService service) { this.repo = repo; this.service = service; }

    @Override
    public void run(ApplicationArguments args) {
        Thread t = new Thread(this::seed, "seed-challenges");
        t.setDaemon(true);
        t.start();
    }

    private void seed() {
        for (Challenge c : all()) {
            try {
                var existing = repo.findByTitleAndOrigin(c.title, "seed");
                if (existing.isPresent() && existing.get().validated) continue;
                Challenge target = existing.orElse(c);
                if (existing.isPresent()) { c.id = existing.get().id; target = c; }
                String problem = service.validate(target);
                target.validated = problem == null;
                repo.save(target);
                System.out.println("[seed] " + c.title + (problem == null ? " validated" : " REJECTED: " + problem));
            } catch (Exception e) {
                System.err.println("[seed] " + c.title + " failed: " + e.getMessage());
            }
        }
    }

    private static Map<String, String> files(String... kv) {
        Map<String, String> m = new LinkedHashMap<>();
        for (int i = 0; i < kv.length; i += 2) m.put(kv[i], kv[i + 1]);
        return m;
    }

    private static Challenge make(String concept, String title, int difficulty, String scaffolding, int minutes, String desc,
                                  Map<String, String> starter, Map<String, String> tests, Map<String, String> ref, List<String> hints) {
        Challenge c = new Challenge();
        c.language = "java"; c.concept = concept; c.title = title; c.difficulty = difficulty; c.scaffolding = scaffolding;
        c.expectedMinutes = minutes; c.descriptionMd = desc;
        c.starterFilesJson = Json.write(starter); c.testsJson = Json.write(tests);
        c.referenceSolutionJson = Json.write(ref); c.hintsJson = Json.write(hints);
        c.origin = "seed";
        return c;
    }

    static List<Challenge> all() { return List.of(shapeParade(), bankAccount(), payroll()); }

    // ------------------------------------------------------------------ Polymorphism
    static Challenge shapeParade() {
        String shape = """
                public abstract class Shape {
                    public abstract double area();

                    public String describe() {
                        return getClass().getSimpleName() + " with area " + String.format(java.util.Locale.ROOT, "%.2f", area());
                    }
                }
                """;
        String circle = """
                public class Circle extends Shape {
                    private final double radius;

                    public Circle(double radius) { this.radius = radius; }

                    @Override
                    public double area() { return Math.PI * radius * radius; }
                }
                """;
        String main = """
                import java.util.List;

                public class Main {
                    public static void main(String[] args) {
                        List<Shape> shapes = List.of(new Circle(1), new Rectangle(2, 3), new Square(2));
                        for (Shape s : shapes) System.out.println(s.describe());
                        System.out.printf("Total area: %.2f%n", ShapeUtils.totalArea(shapes));
                    }
                }
                """;
        Map<String, String> starter = files(
                "Shape.java", shape, "Circle.java", circle, "Main.java", main,
                "Rectangle.java", """
                        public class Rectangle extends Shape {
                            protected final double width;
                            protected final double height;

                            public Rectangle(double width, double height) {
                                this.width = width;
                                this.height = height;
                            }

                            @Override
                            public double area() {
                                // TODO 1: return the area of the rectangle
                                return 0;
                            }
                        }
                        """,
                "Square.java", """
                        public class Square extends Rectangle {
                            public Square(double side) {
                                // TODO 2: a square is a rectangle whose width and height are both `side`
                                super(0, 0);
                            }
                        }
                        """,
                "ShapeUtils.java", """
                        import java.util.List;

                        public class ShapeUtils {
                            // TODO 3: add up the area of every shape in the list.
                            // Work with the general Shape type: do not check which kind each shape is.
                            public static double totalArea(List<Shape> shapes) {
                                return 0;
                            }
                        }
                        """);
        Map<String, String> ref = new LinkedHashMap<>(starter);
        ref.put("Rectangle.java", """
                public class Rectangle extends Shape {
                    protected final double width;
                    protected final double height;

                    public Rectangle(double width, double height) {
                        this.width = width;
                        this.height = height;
                    }

                    @Override
                    public double area() { return width * height; }
                }
                """);
        ref.put("Square.java", """
                public class Square extends Rectangle {
                    public Square(double side) { super(side, side); }
                }
                """);
        ref.put("ShapeUtils.java", """
                import java.util.List;

                public class ShapeUtils {
                    public static double totalArea(List<Shape> shapes) {
                        double total = 0;
                        for (Shape s : shapes) total += s.area();
                        return total;
                    }
                }
                """);
        Map<String, String> tests = files("TestMain.java", """
                import java.util.List;

                public class TestMain {
                    public static void main(String[] args) {
                        Check.run("Rectangle computes width x height", () -> Check.eq(12.0, new Rectangle(3, 4).area(), 1e-9));
                        Check.run("Square is a Rectangle with equal sides", () -> {
                            Object o = new Square(5);
                            Check.isTrue(o instanceof Rectangle, "Square should extend Rectangle");
                            Check.eq(25.0, ((Shape) o).area(), 1e-9);
                        });
                        Check.run("describe() uses each shape's own area()", () -> {
                            Check.eq("Circle with area 3.14", new Circle(1).describe());
                            Check.eq("Rectangle with area 6.00", new Rectangle(2, 3).describe());
                        });
                        Check.run("totalArea adds up different shapes", () -> {
                            List<Shape> shapes = List.of(new Circle(1), new Rectangle(2, 3), new Square(2));
                            Check.eq(Math.PI + 6 + 4, ShapeUtils.totalArea(shapes), 1e-9);
                        });
                        Check.run("totalArea of an empty list is zero", () -> Check.eq(0.0, ShapeUtils.totalArea(List.of()), 1e-9));
                    }
                }
                """);
        return make("polymorphism", "Shape Parade", 1, "HEAVY", 8, """
                # Shape Parade

                A drawing app keeps a list of shapes. Each shape knows how to compute **its own** area, and the app should never need to ask *what kind* of shape it is. That is polymorphism.

                `Shape`, `Circle` and `Main` are already written. Fill in the three `TODO`s:

                1. **`Rectangle.area()`** should return `width * height`.
                2. **`Square`** is a `Rectangle` whose width and height are both `side`. Pass the right values to `super(...)`.
                3. **`ShapeUtils.totalArea(List<Shape>)`** returns the sum of every shape's area, using only the `Shape` type.

                Notice how `Shape.describe()` calls `area()` and still prints the right number for every subclass.

                Press **Run** to see your program's output, **Run tests** to check your work, **Submit** when you are done.
                """, starter, tests, ref, List.of(
                "Look at `Shape.describe()`. It calls `area()` without knowing the subclass. What decides which version of `area()` runs?",
                "Each subclass overrides `area()`. In `totalArea` you can just call `shape.area()` on a `Shape`: Java picks the right override at runtime. `Square` can reuse `Rectangle` by calling `super(side, side)`.",
                "```java\npublic double area() { return width * height; }\n\npublic Square(double side) { super(side, side); }\n\ndouble total = 0;\nfor (Shape s : shapes) total += s.area();\nreturn total;\n```"));
    }

    // ------------------------------------------------------------------ Encapsulation
    static Challenge bankAccount() {
        Map<String, String> starter = files(
                "BankAccount.java", """
                        public class BankAccount {
                            // TODO 1: this field must not be reachable from outside the class
                            public double balance;

                            // TODO 2: deposit(double amount)
                            //   - amount must be > 0, otherwise throw IllegalArgumentException

                            // TODO 3: withdraw(double amount)
                            //   - amount must be > 0, otherwise throw IllegalArgumentException
                            //   - you cannot withdraw more than the balance: throw IllegalStateException

                            // TODO 4: getBalance()
                        }
                        """,
                "Main.java", """
                        public class Main {
                            public static void main(String[] args) {
                                BankAccount account = new BankAccount();
                                account.deposit(100);
                                account.withdraw(30);
                                System.out.println("Balance: " + account.getBalance());
                            }
                        }
                        """);
        Map<String, String> ref = new LinkedHashMap<>(starter);
        ref.put("BankAccount.java", """
                public class BankAccount {
                    private double balance;

                    public void deposit(double amount) {
                        if (amount <= 0) throw new IllegalArgumentException("Deposit must be positive");
                        balance += amount;
                    }

                    public void withdraw(double amount) {
                        if (amount <= 0) throw new IllegalArgumentException("Withdrawal must be positive");
                        if (amount > balance) throw new IllegalStateException("Insufficient funds");
                        balance -= amount;
                    }

                    public double getBalance() { return balance; }
                }
                """);
        Map<String, String> tests = files("TestMain.java", """
                import java.lang.reflect.Modifier;

                public class TestMain {
                    public static void main(String[] args) {
                        Check.run("The balance field is private", () -> {
                            var f = BankAccount.class.getDeclaredField("balance");
                            Check.isTrue(Modifier.isPrivate(f.getModifiers()), "balance should be private");
                        });
                        Check.run("A new account starts at zero", () -> Check.eq(0.0, new BankAccount().getBalance(), 1e-9));
                        Check.run("Deposits and withdrawals change the balance", () -> {
                            BankAccount a = new BankAccount();
                            a.deposit(100);
                            a.withdraw(30);
                            Check.eq(70.0, a.getBalance(), 1e-9);
                        });
                        Check.run("Deposits must be positive", () -> {
                            Check.throwsEx(IllegalArgumentException.class, () -> new BankAccount().deposit(0));
                            Check.throwsEx(IllegalArgumentException.class, () -> new BankAccount().deposit(-5));
                        });
                        Check.run("Overdrawing throws IllegalStateException and leaves the balance alone", () -> {
                            BankAccount a = new BankAccount();
                            a.deposit(50);
                            Check.throwsEx(IllegalStateException.class, () -> a.withdraw(80));
                            Check.eq(50.0, a.getBalance(), 1e-9);
                        });
                        Check.run("Withdrawals must be positive", () ->
                            Check.throwsEx(IllegalArgumentException.class, () -> new BankAccount().withdraw(-1)));
                    }
                }
                """);
        return make("encapsulation", "Safe Vault", 2, "PARTIAL", 10, """
                # Safe Vault

                Right now anyone can write `account.balance = 1_000_000;`. A bank cannot allow that.

                Rework `BankAccount` so that **it** controls how its balance changes:

                - Make the `balance` field **private**.
                - `void deposit(double amount)`: throws `IllegalArgumentException` if `amount <= 0`.
                - `void withdraw(double amount)`: throws `IllegalArgumentException` if `amount <= 0`, and `IllegalStateException` if `amount` is more than the balance. A failed withdrawal must not change the balance.
                - `double getBalance()`: returns the current balance.

                A new account starts with a balance of `0`.
                """, starter, tests, ref, List.of(
                "Who is allowed to change `balance` today? What single keyword would limit that to the class itself?",
                "Make the field `private`, then expose behaviour instead of data: `deposit` and `withdraw` validate their input *before* touching `balance`, and `getBalance` is read-only.",
                "```java\nprivate double balance;\n\npublic void withdraw(double amount) {\n    if (amount <= 0) throw new IllegalArgumentException(\"...\");\n    if (amount > balance) throw new IllegalStateException(\"...\");\n    balance -= amount;\n}\n```"));
    }

    // ------------------------------------------------------------------ Inheritance
    static Challenge payroll() {
        Map<String, String> starter = files(
                "Employee.java", """
                        public class Employee {
                            private final String name;
                            private final double baseSalary;

                            public Employee(String name, double baseSalary) {
                                this.name = name;
                                this.baseSalary = baseSalary;
                            }

                            public String getName() { return name; }

                            public double pay() { return baseSalary; }

                            @Override
                            public String toString() { return name + " earns " + pay(); }
                        }
                        """,
                "Manager.java", """
                        // TODO: Manager is an Employee with an extra bonus.
                        //  - constructor: Manager(String name, double baseSalary, double bonus)
                        //  - pay() = the Employee pay plus the bonus (reuse the parent's pay(), do not copy its logic)
                        public class Manager {
                        }
                        """,
                "Intern.java", """
                        // TODO: Intern is an Employee who is paid only half of the base salary.
                        //  - constructor: Intern(String name, double baseSalary)
                        public class Intern {
                        }
                        """,
                "Main.java", """
                        public class Main {
                            public static void main(String[] args) {
                                Employee[] team = { new Employee("Ana", 1000), new Manager("Ben", 2000, 500), new Intern("Cleo", 1200) };
                                for (Employee e : team) System.out.println(e);
                            }
                        }
                        """);
        Map<String, String> ref = new LinkedHashMap<>(starter);
        ref.put("Manager.java", """
                public class Manager extends Employee {
                    private final double bonus;

                    public Manager(String name, double baseSalary, double bonus) {
                        super(name, baseSalary);
                        this.bonus = bonus;
                    }

                    @Override
                    public double pay() { return super.pay() + bonus; }
                }
                """);
        ref.put("Intern.java", """
                public class Intern extends Employee {
                    public Intern(String name, double baseSalary) { super(name, baseSalary); }

                    @Override
                    public double pay() { return super.pay() / 2; }
                }
                """);
        Map<String, String> tests = files("TestMain.java", """
                public class TestMain {
                    public static void main(String[] args) {
                        Check.run("Manager extends Employee", () -> {
                            Object m = new Manager("Ben", 2000, 500);
                            Check.isTrue(m instanceof Employee, "Manager should extend Employee");
                        });
                        Check.run("Manager pay is base salary plus bonus", () -> Check.eq(2500.0, new Manager("Ben", 2000, 500).pay(), 1e-9));
                        Check.run("Manager keeps its name from Employee", () -> Check.eq("Ben", new Manager("Ben", 2000, 500).getName()));
                        Check.run("Intern extends Employee and earns half", () -> {
                            Object i = new Intern("Cleo", 1200);
                            Check.isTrue(i instanceof Employee, "Intern should extend Employee");
                            Check.eq(600.0, ((Employee) i).pay(), 1e-9);
                        });
                        Check.run("toString uses the overridden pay()", () -> Check.eq("Ben earns 2500.0", new Manager("Ben", 2000, 500).toString()));
                    }
                }
                """);
        return make("inheritance", "Payroll Hierarchy", 2, "PARTIAL", 10, """
                # Payroll Hierarchy

                `Employee` already knows a name and a base salary. Build two specialised kinds of employee without copying code.

                - **`Manager`** extends `Employee`, takes an extra `bonus`, and earns the base salary **plus** the bonus. Constructor: `Manager(String name, double baseSalary, double bonus)`.
                - **`Intern`** extends `Employee` and earns **half** of the base salary. Constructor: `Intern(String name, double baseSalary)`.

                Reuse the parent with `super(...)` and `super.pay()` rather than duplicating its fields or logic. `toString()` is inherited and should report the right pay for each type.
                """, starter, tests, ref, List.of(
                "`Employee` keeps its fields private. How can a subclass hand the name and salary to the parent when it is created?",
                "Use `extends Employee`, call `super(name, baseSalary)` in the constructor, then `@Override public double pay()` and build on `super.pay()`.",
                "```java\npublic class Manager extends Employee {\n    private final double bonus;\n    public Manager(String name, double baseSalary, double bonus) {\n        super(name, baseSalary);\n        this.bonus = bonus;\n    }\n    @Override public double pay() { return super.pay() + bonus; }\n}\n```"));
    }
}
