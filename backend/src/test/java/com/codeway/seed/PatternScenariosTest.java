package com.codeway.seed;

import com.codeway.domain.Challenge;
import com.codeway.runner.LocalRunner;
import com.codeway.service.ChallengeService;
import com.codeway.service.ConceptCatalog;
import org.junit.jupiter.api.Test;

import java.util.List;

import static org.junit.jupiter.api.Assertions.*;

/**
 * Every hand-written pattern scenario must pass the same validation the server applies at startup: the reference solution
 * passes all tests, the starter compiles and passes every "Guard:" test, step 1 is still to do, and the guide is well-formed.
 * Run one scenario with -Dscenario=<id>. (Guided practice is switched off, see ConceptCatalog.GUIDED; the scenarios are kept valid so it can come back.)
 */
class PatternScenariosTest {
    @Test void everyScenarioValidates() throws Exception {
        List<Challenge> all = SeedPatterns.load(System.getProperty("scenario"));
        var service = new ChallengeService(null, null, new LocalRunner(), null, new ConceptCatalog(), null);
        for (Challenge c : all) {
            assertFalse(java.util.regex.Pattern.compile("\\b" + c.concept.replace('-', ' ') + "s?\\b", java.util.regex.Pattern.CASE_INSENSITIVE).matcher(c.descriptionMd).find(), c.title + ": the brief must not name the pattern");
            assertNull(service.validate(c), c.title + " (" + c.language + ")");
        }
        // (with no scenarios yet there is nothing to check)
    }

    @Test void theGuideNeverShipsTheAnswerKeyInTheStartResponse() throws Exception {
        for (Challenge c : SeedPatterns.load(System.getProperty("scenario"))) {
            var g = ChallengeService.guide(c);
            assertEquals(5, g.path("quiz").path("options").size(), "five principle options");
            assertTrue(g.path("quiz").path("answers").size() >= 1);
        }
    }
}
