package com.codeway.seed;

import com.codeway.domain.Challenge;
import com.codeway.repo.ChallengeRepo;
import org.junit.jupiter.api.Test;
import java.util.List;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.Mockito.*;

/** A scenario that is replaced or removed stops being served, along with its translations; the current ones are left alone. */
class RetireRemovedScenariosTest {
    private static Challenge row(long id, String title, String lang, String origin, Long family) {
        Challenge c = new Challenge();
        c.id = id; c.title = title; c.language = lang; c.origin = origin; c.familyId = family; c.validated = true;
        return c;
    }

    @Test void aReplacedScenarioAndItsTranslationsAreRetired() {
        var oldJava = row(34, "Old title", "java", SeedPatterns.ORIGIN, null);
        var oldPython = row(35, "Old title", "python", "ai-translation", 34L);
        var current = row(60, "New title", "java", SeedPatterns.ORIGIN, null);
        var ai = row(70, "A variant", "java", "ai", null);
        ChallengeRepo repo = mock(ChallengeRepo.class);
        when(repo.findAll()).thenReturn(List.of(oldJava, oldPython, current, ai));
        new SeedPatterns(repo, null).retireRemoved(List.of(current));
        assertFalse(oldJava.validated, "the replaced scenario is retired");
        assertFalse(oldPython.validated, "so is the copy translated from it");
        assertTrue(current.validated, "the current scenario stays");
        assertTrue(ai.validated, "an unrelated challenge is untouched");
    }
}
