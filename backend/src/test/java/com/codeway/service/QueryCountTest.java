package com.codeway.service;

import com.codeway.auth.AuthInterceptor;
import com.codeway.domain.*;
import com.codeway.repo.*;
import com.codeway.web.LearnController;
import jakarta.servlet.http.HttpServletRequest;
import org.junit.jupiter.api.Test;
import java.time.Instant;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** Guards against N+1 queries: these endpoints must ask the database a fixed number of times, however much data there is. */
class QueryCountTest {
    private static Challenge challenge(long id, String concept) {
        Challenge c = new Challenge();
        c.id = id; c.title = "Challenge " + id; c.concept = concept; c.language = "java"; c.difficulty = 1;
        return c;
    }

    private static Attempt attempt(long id, long challengeId, boolean submitted) {
        Attempt a = new Attempt();
        a.id = id; a.userId = 7L; a.challengeId = challengeId;
        if (submitted) { a.submittedAt = Instant.parse("2026-11-12T10:00:00Z").plusSeconds(id * 60); a.testsPassed = 5; a.testsTotal = 5; a.scoreAfter = 0.2; a.scoreBefore = 0.15; }
        return a;
    }

    @Test void conceptListNeedsOneQueryNotOnePerConcept() {
        MasteryRepo repo = mock(MasteryRepo.class);
        ConceptMastery stored = new ConceptMastery();
        stored.userId = 7L; stored.language = "java"; stored.concept = "classes-objects"; stored.score = 0.42; stored.attemptsCount = 3;
        when(repo.findByUserIdAndLanguage(7L, "java")).thenReturn(List.of(stored));
        AttemptService attempts = mock(AttemptService.class);
        when(attempts.openConcepts(7L, "java")).thenReturn(Set.of("encapsulation"));
        ChallengeService challenges = mock(ChallengeService.class);
        when(challenges.hasContent(anyString(), anyString())).thenReturn(true);
        LearnController controller = new LearnController(new ConceptCatalog(), repo, new MasteryService(repo), mock(LessonService.class), challenges, attempts, mock(StatsService.class));
        HttpServletRequest req = mock(HttpServletRequest.class);
        when(req.getAttribute(AuthInterceptor.ATTR)).thenReturn(7L);

        var rows = controller.mastery(req, "java");

        assertEquals(27, rows.size());
        verify(repo, times(1)).findByUserIdAndLanguage(7L, "java");
        verify(repo, never()).findByUserIdAndLanguageAndConcept(any(), any(), any());   // was one per concept
        var started = rows.stream().filter(r -> r.get("id").equals("classes-objects")).findFirst().orElseThrow();
        assertEquals(0.42, (double) started.get("score"), 1e-9);
        assertEquals(3, started.get("attempts"));
        var fresh = rows.stream().filter(r -> r.get("id").equals("polymorphism")).findFirst().orElseThrow();
        assertEquals(0.0, (double) fresh.get("progress"), 1e-9);
        assertEquals(0, fresh.get("attempts"));
        assertEquals(true, rows.stream().filter(r -> r.get("id").equals("encapsulation")).findFirst().orElseThrow().get("inProgress"));
    }

    @Test void historyLooksUpChallengesInOneBatch() {
        AttemptRepo attempts = mock(AttemptRepo.class);
        List<Attempt> mine = new ArrayList<>();
        for (long i = 1; i <= 30; i++) mine.add(attempt(i, 100 + (i % 3), true));    // 30 submissions of 3 challenges
        Collections.reverse(mine);                                                    // newest first, as the repository returns them
        when(attempts.findByUserIdAndSubmittedAtIsNotNullOrderBySubmittedAtDesc(7L)).thenReturn(mine);
        ChallengeRepo challenges = mock(ChallengeRepo.class);
        when(challenges.findAllById(any())).thenReturn(List.of(challenge(100, "polymorphism"), challenge(101, "inheritance"), challenge(102, "interfaces")));
        AttemptService service = new AttemptService(attempts, challenges, null, null, null, null, null, new ConceptCatalog());

        var page = service.historyPage(7L, 0, 20, null);
        service.historySummary(7L, 30, 0);
        service.historyByChallenge(7L, null);

        assertEquals(30, page.get("total"));
        verify(challenges, never()).findById(any());                                  // was one per challenge, per call
        verify(challenges, times(3)).findAllById(any());                              // one batch per call
    }

    @Test void openAttemptsAndRecentSubmissionsAreBatchedToo() {
        AttemptRepo attempts = mock(AttemptRepo.class);
        when(attempts.findByUserIdAndSubmittedAtIsNullOrderByStartedAtDesc(7L)).thenReturn(List.of(attempt(1, 100, false), attempt(2, 101, false), attempt(3, 102, false)));
        ChallengeRepo challenges = mock(ChallengeRepo.class);
        when(challenges.findAllById(any())).thenReturn(List.of(challenge(100, "polymorphism"), challenge(101, "inheritance"), challenge(102, "interfaces")));
        AttemptService service = new AttemptService(attempts, challenges, null, null, null, null, null, new ConceptCatalog());

        assertEquals(Set.of("polymorphism", "inheritance", "interfaces"), service.openConcepts(7L, "java"));
        verify(challenges, never()).findById(any());
        verify(challenges, times(1)).findAllById(any());

        // dashboard stats: 10 submitted attempts, 8 shown as recent
        AttemptRepo stats = mock(AttemptRepo.class);
        List<Attempt> ten = new ArrayList<>();
        for (long i = 1; i <= 10; i++) ten.add(attempt(i, 100 + (i % 3), true));
        when(stats.findByUserId(7L)).thenReturn(ten);
        ChallengeRepo ch2 = mock(ChallengeRepo.class);
        when(ch2.findAllById(any())).thenReturn(List.of(challenge(100, "polymorphism"), challenge(101, "inheritance"), challenge(102, "interfaces")));
        UserRepo users = mock(UserRepo.class);
        when(users.findById(7L)).thenReturn(Optional.empty());
        MasteryRepo mr = mock(MasteryRepo.class);
        when(mr.findByUserIdAndLanguage(7L, "java")).thenReturn(List.of());
        var out = new StatsService(stats, mock(EventRepo.class), ch2, mr, users, new ConceptCatalog()).stats(7L, "java", 0);
        assertEquals(8, ((List<?>) out.get("recent")).size());
        verify(ch2, never()).findById(any());
        verify(ch2, times(1)).findAllById(any());
    }
}
