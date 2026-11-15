package com.codeway.service;

import com.codeway.domain.Challenge;
import org.junit.jupiter.api.Test;

import java.util.Map;

import static org.junit.jupiter.api.Assertions.*;

/** A generated challenge may only rely on what the learner has been taught. */
class TeachingTest {
    private final ConceptCatalog catalog = new ConceptCatalog();

    private Challenge java(String concept, String description, String starter, String reference) {
        Challenge c = new Challenge();
        c.language = "java"; c.concept = concept; c.descriptionMd = description;
        c.starterFilesJson = Json.write(Map.of("Animal.java", starter));
        c.referenceSolutionJson = Json.write(Map.of("Animal.java", reference));
        return c;
    }

    private static final String PLAIN = "public class Animal { public String sound() { return \"...\"; } }";
    private static final String ABSTRACT = "public abstract class Animal { public abstract String greet(); }";
    private static final String LESSON = "## Polymorphism\nA Dog extends Animal and @Override its sound. super is the parent.";

    @Test void anAbstractClassIsRejectedBeforeTheAbstractionLesson() {
        String problem = Teaching.untaught(java("polymorphism", "d", ABSTRACT, ABSTRACT), catalog, LESSON);
        assertNotNull(problem);
        assertTrue(problem.contains("`abstract`") && problem.contains("Abstraction"), problem);
    }

    @Test void anAbstractClassIsFineOnceAbstractionIsTaught() {
        assertNull(Teaching.untaught(java("interfaces", "d", ABSTRACT, ABSTRACT), catalog, "abstract classes"));
    }

    @Test void aPlainBaseClassPasses() {
        assertNull(Teaching.untaught(java("polymorphism", "d", PLAIN, PLAIN), catalog, LESSON));
    }

    @Test void aLibraryCallThatNobodyExplainsIsRejected() {
        String src = "public class Animal { public String who() { return getClass().getSimpleName(); } }";
        String problem = Teaching.untaught(java("polymorphism", "Prints the class name.", src, src), catalog, LESSON);
        assertNotNull(problem);
        assertTrue(problem.contains("getSimpleName") && problem.contains("getClass"), problem);
    }

    @Test void thatSameCallPassesWhenTheDescriptionExplainsIt() {
        String src = "public class Animal { public String who() { return getClass().getSimpleName(); } }";
        String desc = "## New in this challenge\n`getClass().getSimpleName()` returns the name of the object's class, for example Dog.";
        assertNull(Teaching.untaught(java("polymorphism", desc, src, src), catalog, LESSON));
    }

    @Test void wordsInCommentsAndPrintedTextAreNotFeatures() {
        String src = "public class Animal {\n // abstract idea, interface later\n public String s() { return \"abstract interface instanceof\"; } }";
        assertNull(Teaching.untaught(java("polymorphism", "d", src, src), catalog, LESSON));
    }

    @Test void withoutALessonOnlyTheLaterConceptsAreChecked() {
        assertNull(Teaching.untaught(java("polymorphism", "d", PLAIN, PLAIN), catalog, null));
        assertNotNull(Teaching.untaught(java("polymorphism", "d", ABSTRACT, ABSTRACT), catalog, null));
    }

    @Test void everyConceptWithPrerequisitesListsOnlyEarlierOnes() {
        for (var c : catalog.list("java"))
            for (var r : catalog.requires(c.id()))
                assertTrue(catalog.position(r.id()) < catalog.position(c.id()), c.id() + " requires " + r.id() + " which comes later");
    }
}
