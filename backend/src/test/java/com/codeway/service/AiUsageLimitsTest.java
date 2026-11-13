package com.codeway.service;

import com.codeway.ai.AiClient;
import com.codeway.domain.*;
import com.codeway.repo.*;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.http.HttpStatus;
import org.springframework.web.server.ResponseStatusException;
import java.time.Duration;
import java.time.Instant;
import java.util.*;
import java.util.concurrent.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

/** The two AI cost limits: 5 hints per learner per hour, and one AI generation per lesson for everyone. */
class AiUsageLimitsTest {
    // ---------------------------------------------------------------- hints
    private static final Instant NOW = Instant.parse("2026-11-13T12:00:00Z");

    @Test void hourlyAllowanceIsCountedOverARollingHour() {
        var times = List.of(NOW.minus(Duration.ofMinutes(59)), NOW.minus(Duration.ofMinutes(30)), NOW.minus(Duration.ofMinutes(10)),
                NOW.minus(Duration.ofMinutes(5)), NOW.minus(Duration.ofMinutes(1)));
        var full = AttemptService.quotaFrom(times, 5, NOW);
        assertEquals(5, full.used()); assertEquals(0, full.left());
        assertEquals(60, full.resetSeconds(), "the oldest hint leaves the hour in 1 minute");

        var older = new ArrayList<>(times); older.add(NOW.minus(Duration.ofMinutes(61)));          // older than an hour: ignored
        assertEquals(5, AttemptService.quotaFrom(older, 5, NOW).used());
        assertEquals(1, AttemptService.quotaFrom(times.subList(1, 5), 5, NOW).left());
        assertEquals(0, AttemptService.quotaFrom(times.subList(1, 5), 5, NOW).resetSeconds());
        assertEquals(5, AttemptService.quotaFrom(List.of(), 5, NOW).left());
    }

    private static AttemptService service(EventRepo events, AiClient ai, AttemptRepo attempts, ChallengeRepo challenges) {
        return new AttemptService(attempts, challenges, events, null, null, null, ai, new ConceptCatalog());
    }

    private record Fixture(AttemptService service, AttemptRepo attempts, EventRepo events, AiClient ai) {}

    private static Fixture fixture(int hintsInLastHour) {
        AttemptRepo attempts = mock(AttemptRepo.class);
        Attempt a = new Attempt(); a.id = 1L; a.userId = 7L; a.challengeId = 10L;
        when(attempts.findById(1L)).thenReturn(Optional.of(a));
        ChallengeRepo challenges = mock(ChallengeRepo.class);
        Challenge ch = new Challenge(); ch.id = 10L; ch.language = "java"; ch.concept = "polymorphism";
        ch.hintsJson = Json.write(List.of("nudge", "concept", "code"));
        when(challenges.findById(10L)).thenReturn(Optional.of(ch));
        EventRepo events = mock(EventRepo.class);
        List<Instant> times = new ArrayList<>();
        for (int i = 0; i < hintsInLastHour; i++) times.add(Instant.now().minus(Duration.ofMinutes(50 - i)));
        when(events.hintTimesSince(eq(7L), any())).thenReturn(times);
        AiClient ai = mock(AiClient.class);
        when(ai.isConfigured()).thenReturn(true);
        when(ai.hintModel()).thenReturn("m");
        when(ai.chat(anyString(), anyString(), anyString(), anyBoolean())).thenReturn("A short AI hint");
        return new Fixture(service(events, ai, attempts, challenges), attempts, events, ai);
    }

    @Test void theSixthHintInAnHourIsRefusedBeforeAnyAiCall() {
        var f = fixture(5);
        var e = assertThrows(ResponseStatusException.class, () -> f.service().hint(7L, 1L, Map.of(), ""));
        assertEquals(HttpStatus.TOO_MANY_REQUESTS, e.getStatusCode());
        assertTrue(e.getReason().contains("5 hints for this hour"), e.getReason());
        verify(f.ai(), never()).chat(anyString(), anyString(), anyString(), anyBoolean());   // no AI cost
        verify(f.attempts(), never()).save(any());                                            // no hint level used up
        verify(f.events(), never()).save(any());
    }

    @Test void theFifthHintIsAllowedAndReportsTheAllowance() {
        var f = fixture(4);
        var hint = f.service().hint(7L, 1L, Map.of(), "");
        assertEquals("A short AI hint", hint.get("text"));
        assertNotNull(hint.get("quota"));
        var saved = ArgumentCaptor.forClass(EventLog.class);
        verify(f.events()).save(saved.capture());
        assertEquals("hint", saved.getValue().type);
    }

    @Test void theBrowserCannotLogHintEventsToChangeItsOwnCount() {
        AttemptRepo attempts = mock(AttemptRepo.class);
        Attempt a = new Attempt(); a.id = 1L; a.userId = 7L;
        when(attempts.findById(1L)).thenReturn(Optional.of(a));
        EventRepo events = mock(EventRepo.class);
        var s = service(events, null, attempts, null);
        s.telemetry(7L, List.of(new AttemptService.Event(1L, "hint", null), new AttemptService.Event(1L, "run", null), new AttemptService.Event(1L, "churn", null)));
        var saved = ArgumentCaptor.forClass(EventLog.class);
        verify(events, times(1)).save(saved.capture());
        assertEquals("churn", saved.getValue().type);
    }

    // ---------------------------------------------------------------- lessons
    private static final class Lessons {
        final Map<String, Lesson> store = new ConcurrentHashMap<>();
        final LessonRepo repo = mock(LessonRepo.class);
        final AiClient ai = mock(AiClient.class);
        final LessonService service;
        Lessons() {
            when(repo.findByLanguageAndConcept(anyString(), anyString())).thenAnswer(i -> Optional.ofNullable(store.get(i.getArgument(0) + "/" + i.getArgument(1))));
            when(repo.save(any())).thenAnswer(i -> { Lesson l = i.getArgument(0); store.put(l.language + "/" + l.concept, l); return l; });
            when(ai.isConfigured()).thenReturn(true);
            when(ai.model()).thenReturn("m");
            service = new LessonService(repo, ai, new ConceptCatalog());
        }
        void aiReturns(String md, long delayMs) {
            doAnswer(i -> { Thread.sleep(delayMs); return md; }).when(ai).chat(anyString(), anyString(), anyString(), anyBoolean());   // doAnswer: safe to re-stub a throwing mock
        }
    }

    @Test void twoLearnersOpeningANewLessonAtOnceCostOneAiCall() throws Exception {
        var t = new Lessons();
        t.aiReturns("## The problem\n\nGenerated once.", 400);
        var pool = Executors.newFixedThreadPool(2);
        var a = pool.submit(() -> t.service.get("java", "strategy"));
        var b = pool.submit(() -> t.service.get("java", "strategy"));
        assertEquals("## The problem\n\nGenerated once.", a.get(10, TimeUnit.SECONDS).contentMd);
        assertEquals(a.get().contentMd, b.get().contentMd);
        pool.shutdown();
        verify(t.ai, times(1)).chat(anyString(), anyString(), anyString(), anyBoolean());
        verify(t.repo, times(1)).save(any());
    }

    @Test void aStoredLessonIsReusedWithoutAnyAiCall() {
        var t = new Lessons();
        Lesson l = new Lesson(); l.language = "java"; l.concept = "strategy"; l.source = "ai"; l.contentMd = "## Stored";
        t.store.put("java/strategy", l);
        for (int i = 0; i < 5; i++) assertEquals("## Stored", t.service.get("java", "strategy").contentMd);
        verify(t.ai, never()).chat(anyString(), anyString(), anyString(), anyBoolean());
    }

    @Test void anotherLanguageIsTranslatedFromTheExistingLessonNotWrittenFromScratch() {
        var t = new Lessons();
        Lesson l = new Lesson(); l.language = "java"; l.concept = "strategy"; l.source = "ai"; l.contentMd = "## The problem\n\nJava version";
        t.store.put("java/strategy", l);
        t.aiReturns("## The problem\n\nPython version", 0);
        assertEquals("## The problem\n\nPython version", t.service.get("python", "strategy").contentMd);
        var prompt = ArgumentCaptor.forClass(String.class);
        verify(t.ai).chat(anyString(), anyString(), prompt.capture(), anyBoolean());
        assertTrue(prompt.getValue().startsWith("Rewrite this Java lesson for Python"), prompt.getValue());
        assertTrue(prompt.getValue().contains("Java version"));
    }

    @Test void aFailedGenerationIsNotRetriedOnEveryView() {
        var t = new Lessons();
        when(t.ai.chat(anyString(), anyString(), anyString(), anyBoolean())).thenThrow(new IllegalStateException("AI request failed (500)"));
        for (int i = 0; i < 6; i++) {
            var l = t.service.get("java", "strategy");
            assertEquals("fallback", l.source);
            assertTrue(l.contentMd.contains("isn't ready yet"));
        }
        verify(t.ai, times(1)).chat(anyString(), anyString(), anyString(), anyBoolean());     // was one call per view
    }

    @Test void whenTodaysAiAllowanceIsUsedUpNothingIsSavedSoTheRealLessonCanStillBeGenerated() {
        var t = new Lessons();
        when(t.ai.chat(anyString(), anyString(), anyString(), anyBoolean())).thenThrow(new AiClient.QuotaExceeded("Daily AI limit reached"));
        assertTrue(t.service.get("java", "strategy").contentMd.contains("isn't ready yet"));
        verify(t.repo, never()).save(any());
        t.aiReturns("## Real lesson", 0);
        assertEquals("## Real lesson", t.service.get("java", "strategy").contentMd);       // the next learner generates it
        assertEquals("ai", t.store.get("java/strategy").source);
    }
}
