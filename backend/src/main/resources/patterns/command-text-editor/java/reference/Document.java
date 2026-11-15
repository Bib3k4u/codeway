/** A tiny text document. It only knows how to change its own text. */
public class Document {
    private final StringBuilder text = new StringBuilder();

    public void insert(String added) {
        text.append(added);
    }

    /** Removes up to {@code count} characters from the end and returns exactly what was removed. */
    public String deleteLast(int count) {
        int cut = Math.max(0, text.length() - Math.max(0, count));
        String removed = text.substring(cut);
        text.setLength(cut);
        return removed;
    }

    /** Replaces the whole text. */
    public void setText(String newText) {
        text.setLength(0);
        text.append(newText);
    }

    public String text() {
        return text.toString();
    }
}
