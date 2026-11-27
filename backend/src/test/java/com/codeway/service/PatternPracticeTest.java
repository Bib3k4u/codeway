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
}
