package com.codeway.service;

import com.codeway.runner.Lang;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** Design patterns are practised like the SOLID principles: generated exercises that grow with the learner, not a guided lab. */
class PatternPracticeTest {
    private final ConceptCatalog catalog = new ConceptCatalog();

    @Test void everyPatternHasPracticeAndNoneIsGuided() {
        for (var c : catalog.list("java")) {
            if (!"patterns".equals(c.track())) continue;
            assertTrue(catalog.practiceOpen("java", c.id()), c.id() + " must be open for practice");
            assertFalse(catalog.isGuided(c.id()), c.id() + " uses the normal exercises");
            assertTrue(catalog.mainProvided(c.id()), c.id() + ": the demo main is provided, like SOLID");
        }
    }

    @Test void everyPatternHasItsOwnDesignRules() {
        for (var c : catalog.list("java")) {
            if (!"patterns".equals(c.track())) continue;
            String rules = ChallengeService.trackRules(c, Lang.JAVA, 1);
            assertTrue(rules.contains("DESIGN PATTERNS exercise about \"" + c.title() + "\""), c.id());
            assertFalse(rules.contains("A design that the pattern improves"), c.id() + " needs a specific description of its problem");
            assertTrue(rules.contains("independent"), "tests must not depend on each other or on order");
        }
    }

    @Test void theFirstExercisesAreSmallerThanTheLaterOnes() {
        assertTrue(ChallengeService.patternSize(1).contains("30 lines"));
        assertTrue(ChallengeService.patternSize(1).contains("At most 3 classes"));
        assertTrue(ChallengeService.patternSize(5).contains("two extra requirements"));
        assertNotEquals(ChallengeService.patternSize(2), ChallengeService.patternSize(3));
    }

    @Test void solidKeepsItsOwnRules() {
        var c = catalog.find("java", "open-closed").orElseThrow();
        assertTrue(ChallengeService.trackRules(c, Lang.JAVA, 3).contains("SOLID principles exercise"));
        assertEquals("", ChallengeService.trackRules(catalog.find("java", "inheritance").orElseThrow(), Lang.JAVA, 1));
    }

    // ---- tests may only use names the learner was given ----
    private static com.codeway.domain.Challenge shop(String description, String test) {
        var c = new com.codeway.domain.Challenge();
        c.language = "java"; c.origin = "ai"; c.descriptionMd = description;
        c.starterFilesJson = Json.write(java.util.Map.of(
                "Product.java", "public interface Product { String getName(); }",
                "ProductFactory.java", "public class ProductFactory { public static Product createProduct(String type) { throw new UnsupportedOperationException(); } }"));
        c.referenceSolutionJson = Json.write(java.util.Map.of(
                "ProductFactory.java", "import java.util.*; import java.util.function.Supplier;\npublic class ProductFactory { public static Map<String, Supplier<Product>> registered = new HashMap<>();\n"
                        + " public static Product createProduct(String type) { return registered.get(type).get(); } }"));
        c.testsJson = Json.write(java.util.Map.of("TestMain.java", test));
        return c;
    }

    @Test void aTestThatUsesANameTheBriefNeverGivesIsRejected() {
        var c = shop("Use `ProductFactory.createProduct(String)` to get a Product.",
                "public class TestMain { public static void main(String[] a) { Check.run(\"x\", () -> { ProductFactory.registered.put(\"fake\", () -> () -> \"F\"); Check.eq(\"F\", ProductFactory.createProduct(\"fake\").getName()); }); } }");
        String why = ChallengeService.undeclaredApi(c);
        assertNotNull(why);
        assertTrue(why.contains("`registered`"), why);
        assertFalse(why.contains("getName"), "names the starter gives are fine");
        assertTrue(ChallengeService.retired(c, false), "an exercise like this is not served any more");
    }

    @Test void namesTheBriefOrTheStarterGiveAreFine() {
        var c = shop("Use `ProductFactory.createProduct(String)` and `ProductFactory.registered` (a map from name to Supplier).",
                "public class TestMain { public static void main(String[] a) { Check.run(\"x\", () -> { ProductFactory.registered.put(\"fake\", () -> () -> \"F\"); Check.eq(\"F\", ProductFactory.createProduct(\"fake\").getName()); }); } }");
        assertNull(ChallengeService.undeclaredApi(c));
        assertFalse(ChallengeService.retired(c, false));
    }

    @Test void libraryCallsAndNamesTheTestDeclaresAreNotFlagged() {
        var c = shop("Use `ProductFactory.createProduct(String)`.",
                "import java.util.*;\npublic class TestMain { static class Fake implements Product { public String getName() { return \"F\"; } }\n"
                        + "public static void main(String[] a) { Check.run(\"x\", () -> { List<String> l = new ArrayList<>(); l.add(\"a\"); Check.eq(1, l.size()); Check.eq(\"F\", new Fake().getName()); }); } }");
        assertNull(ChallengeService.undeclaredApi(c));
    }

    @Test void factoryMethodIsTaughtWithSubclassesNotRegistries() {
        String rules = ChallengeService.trackRules(catalog.find("java", "factory-method").orElseThrow(), Lang.JAVA, 1);
        assertTrue(rules.contains("Do NOT use strings or type codes"));
        assertTrue(rules.contains("Supplier"));
    }
}
