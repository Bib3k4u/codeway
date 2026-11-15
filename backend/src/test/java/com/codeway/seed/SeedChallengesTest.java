package com.codeway.seed;

import com.codeway.domain.Challenge;
import com.codeway.runner.LocalRunner;
import com.codeway.service.ChallengeService;
import com.codeway.service.ConceptCatalog;
import org.junit.jupiter.api.Test;

import static org.junit.jupiter.api.Assertions.*;

/** The hand-written Java challenges pass the same validation as AI ones, including "uses only what the lessons teach". */
class SeedChallengesTest {
    @Test void genericTestNamesAreRejected() {
        var service = new ChallengeService(null, null, new LocalRunner(), null, new ConceptCatalog(), null);
        Challenge c = SeedChallenges.all().get(1);                       // Safe Vault
        c.testsJson = c.testsJson.replace("A new account starts at zero", "Test 2");
        String problem = service.validate(c);
        assertNotNull(problem);
        assertTrue(problem.contains("plain words"), problem);
    }

    @Test void everySeedValidates() {
        var service = new ChallengeService(null, null, new LocalRunner(), null, new ConceptCatalog(), null);
        for (Challenge c : SeedChallenges.all()) {
            assertNull(service.validate(c), c.title);
            assertTrue(c.descriptionMd.contains("## Before you start"), c.title + " should say what to know first");
        }
    }
}
