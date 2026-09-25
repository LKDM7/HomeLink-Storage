package fr.lkdm.homelink.storage.client.recipe;

/**
 * Optional link to a recipe viewer's search field. The viewer plugin installs it when its
 * runtime starts, so the Terminal never references a viewer class directly.
 */
public final class RecipeViewerBridge {
    public interface Search {
        String get();
        void set(String text);
    }

    private static volatile Search search;
    /** Session preference: mirror the Terminal search into the viewer. */
    private static boolean synchronizedSearch;

    public static void install(Search value) { search = value; }
    public static void clear() { search = null; }
    public static boolean available() { return search != null; }
    public static boolean synchronizedSearch() { return synchronizedSearch && available(); }
    public static void setSynchronizedSearch(boolean value) { synchronizedSearch = value; }

    public static String viewerSearch() {
        Search current = search;
        return current == null ? "" : fromViewer(current.get());
    }

    public static void pushSearch(String terminalQuery) {
        Search current = search;
        if (current != null) current.set(toViewer(terminalQuery));
    }

    /** Terminal "#tag" filters map to the viewer's "$tag" prefix, and back. */
    static String toViewer(String query) { return query.startsWith("#") ? "$" + query.substring(1) : query; }
    static String fromViewer(String query) { return query.startsWith("$") ? "#" + query.substring(1) : query; }

    private RecipeViewerBridge() { }
}
