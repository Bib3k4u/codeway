public class Main {
    public static void main(String[] args) {
        Document doc = new Document("Release notes");
        System.out.println(doc.title() + " is " + doc.status());
        step(doc, "submit", doc::submit);
        step(doc, "approve", doc::approve);
        step(doc, "approve", doc::approve);
        step(doc, "unpublish", doc::unpublish);
        step(doc, "reject", doc::reject);
    }

    private static void step(Document doc, String action, Runnable move) {
        try {
            move.run();
            System.out.println(action + ": " + doc.status());
        } catch (IllegalStateException e) {
            System.out.println(action + ": refused (" + e.getMessage() + ")");
        }
    }
}
