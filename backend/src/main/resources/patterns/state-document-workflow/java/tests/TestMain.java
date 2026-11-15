import java.util.ArrayList;
import java.util.List;

public class TestMain {
    /** A brand-new status, defined here and not in the learner's code (used by the step 4 tests). */
    static final class ArchivedStatus implements DocumentStatus {
        @Override
        public String name() {
            return "archived";
        }

        @Override
        public DocumentStatus submit() {
            throw new IllegalStateException("Cannot submit an archived document");
        }

        @Override
        public DocumentStatus approve() {
            throw new IllegalStateException("Cannot approve an archived document");
        }

        @Override
        public DocumentStatus reject() {
            throw new IllegalStateException("Cannot reject an archived document");
        }

        @Override
        public DocumentStatus unpublish() {
            return new DraftStatus();   // restoring an archived document makes it a draft again
        }
    }

    /** A recording status: logs each call and moves to {@code next}, or refuses when there is none (used by the step 3 tests). */
    static final class Probe implements DocumentStatus {
        final String name;
        final List<String> log;
        final DocumentStatus next;

        Probe(String name, List<String> log, DocumentStatus next) {
            this.name = name;
            this.log = log;
            this.next = next;
        }

        @Override
        public String name() {
            return name;
        }

        private DocumentStatus move(String action) {
            log.add(name + ":" + action);
            if (next == null) {
                throw new IllegalStateException("blocked by " + name);
            }
            return next;
        }

        @Override
        public DocumentStatus submit() {
            return move("submit");
        }

        @Override
        public DocumentStatus approve() {
            return move("approve");
        }

        @Override
        public DocumentStatus reject() {
            return move("reject");
        }

        @Override
        public DocumentStatus unpublish() {
            return move("unpublish");
        }
    }

    /** Runs a move and returns the refusal message, or a marker text when nothing was thrown. */
    static String refusal(Runnable move) {
        try {
            move.run();
        } catch (IllegalStateException e) {
            return e.getMessage();
        }
        return "(no exception)";
    }

    public static void main(String[] args) {
        // ---- Guard: the old behaviour, through new Document(...) and its operations ----
        Check.run("Guard: a new document is a draft and moves draft -> review -> published", () -> {
            Document doc = new Document("Spec");
            Check.eq("Spec", doc.title());
            Check.eq("draft", doc.status());
            doc.submit();
            Check.eq("review", doc.status());
            doc.approve();
            Check.eq("published", doc.status());
        });
        Check.run("Guard: reject sends a document in review back to draft, unpublish sends a published one back to draft", () -> {
            Document doc = new Document("Spec");
            doc.submit();
            doc.reject();
            Check.eq("draft", doc.status());
            doc.submit();
            doc.approve();
            doc.unpublish();
            Check.eq("draft", doc.status());
        });
        Check.run("Guard: a move that is not allowed throws IllegalStateException and keeps the status", () -> {
            Document doc = new Document("Spec");
            Check.eq("Cannot approve a draft document", refusal(doc::approve));
            Check.eq("draft", doc.status());
            doc.submit();
            Check.eq("Cannot submit a review document", refusal(doc::submit));
            Check.eq("review", doc.status());
            doc.approve();
            Check.eq("Cannot reject a published document", refusal(doc::reject));
            Check.eq("published", doc.status());
        });

        // ---- Step 1: DocumentStatus and one class per status, each deciding the next status ----
        Check.run("S1: DraftStatus is named draft and submit leads to review", () -> {
            DocumentStatus draft = new DraftStatus();
            Check.eq("draft", draft.name());
            Check.eq("review", draft.submit().name());
        });
        Check.run("S1: ReviewStatus is named review, approve leads to published and reject leads to draft", () -> {
            DocumentStatus review = new ReviewStatus();
            Check.eq("review", review.name());
            Check.eq("published", review.approve().name());
            Check.eq("draft", review.reject().name());
        });
        Check.run("S1: PublishedStatus is named published and unpublish leads to draft", () -> {
            DocumentStatus published = new PublishedStatus();
            Check.eq("published", published.name());
            Check.eq("draft", published.unpublish().name());
        });

        // ---- Step 2: every status refuses the moves it does not allow ----
        Check.run("S2: DraftStatus refuses approve, reject and unpublish", () -> {
            DocumentStatus s = new DraftStatus();
            Check.eq("Cannot approve a draft document", refusal(s::approve));
            Check.eq("Cannot reject a draft document", refusal(s::reject));
            Check.eq("Cannot unpublish a draft document", refusal(s::unpublish));
        });
        Check.run("S2: ReviewStatus refuses submit and unpublish", () -> {
            DocumentStatus s = new ReviewStatus();
            Check.eq("Cannot submit a review document", refusal(s::submit));
            Check.eq("Cannot unpublish a review document", refusal(s::unpublish));
        });
        Check.run("S2: PublishedStatus refuses submit, approve and reject", () -> {
            DocumentStatus s = new PublishedStatus();
            Check.eq("Cannot submit a published document", refusal(s::submit));
            Check.eq("Cannot approve a published document", refusal(s::approve));
            Check.eq("Cannot reject a published document", refusal(s::reject));
        });

        // ---- Step 3: the document delegates to its current status object ----
        Check.run("S3: each operation is passed to the current status object and the document adopts the status it returns", () -> {
            List<String> log = new ArrayList<>();
            DocumentStatus c = new Probe("c", log, new Probe("d", log, null));
            DocumentStatus b = new Probe("b", log, c);
            DocumentStatus a = new Probe("a", log, b);
            Document doc = new Document("Spec", a);
            Check.eq("a", doc.status());
            doc.submit();
            Check.eq("b", doc.status());
            doc.reject();
            Check.eq("c", doc.status());
            doc.unpublish();
            Check.eq("d", doc.status());
            Check.eq(List.of("a:submit", "b:reject", "c:unpublish"), log);
        });
        Check.run("S3: when the current status refuses, the exception reaches the caller and the status stays", () -> {
            List<String> log = new ArrayList<>();
            Document doc = new Document("Spec", new Probe("stuck", log, null));
            Check.eq("blocked by stuck", refusal(doc::approve));
            Check.eq("stuck", doc.status());
            Check.eq(List.of("stuck:approve"), log);
        });
        Check.run("S3: a document can start in any status object and carries on from there", () -> {
            Document doc = new Document("Spec", new PublishedStatus());
            Check.eq("published", doc.status());
            doc.unpublish();
            Check.eq("draft", doc.status());
            doc.submit();
            doc.approve();
            Check.eq("published", doc.status());
        });

        // ---- Step 4: a brand-new status, defined right here, with no change to existing classes ----
        Check.run("S4: a published document can be moved into a status defined in the test and then behaves like it", () -> {
            Document doc = new Document("Spec");
            doc.submit();
            doc.approve();
            doc.moveTo(new ArchivedStatus());
            Check.eq("archived", doc.status());
            Check.eq("Cannot submit an archived document", refusal(doc::submit));
            Check.eq("Cannot approve an archived document", refusal(doc::approve));
            Check.eq("Cannot reject an archived document", refusal(doc::reject));
            Check.eq("archived", doc.status());
        });
        Check.run("S4: the new status can lead back into the existing ones", () -> {
            Document doc = new Document("Spec");
            doc.moveTo(new ArchivedStatus());
            doc.unpublish();
            Check.eq("draft", doc.status());
            doc.submit();
            Check.eq("review", doc.status());
        });
        Check.run("S4: moveTo puts the document into any status object, and the normal moves continue from there", () -> {
            Document doc = new Document("Spec");
            doc.moveTo(new ReviewStatus());
            Check.eq("review", doc.status());
            doc.approve();
            Check.eq("published", doc.status());
        });
    }
}
