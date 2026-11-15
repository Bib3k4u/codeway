public class Main {
    public static void main(String[] args) {
        EditorToolbar toolbar = new EditorToolbar();
        toolbar.onTypeHello();
        System.out.println("\"" + toolbar.text() + "\"");
        toolbar.onTypeWorld();
        System.out.println("\"" + toolbar.text() + "\"");
        toolbar.onDeleteWord();
        System.out.println("\"" + toolbar.text() + "\"");
        toolbar.onTypeHello();
        System.out.println("\"" + toolbar.text() + "\"");
        toolbar.onDeleteWord();
        System.out.println("\"" + toolbar.text() + "\"");
        toolbar.onDeleteWord();
        System.out.println("\"" + toolbar.text() + "\"");
    }
}
