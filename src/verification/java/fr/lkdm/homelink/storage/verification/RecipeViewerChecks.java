package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.client.recipe.RecipeViewerBridge;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import net.neoforged.fml.ModList;

/** Real client: the Terminal only talks to JEI through the bridge its plugin installs. */
public final class RecipeViewerChecks {
    public static void run(StorageScreen screen) {
        boolean jei = ModList.get().isLoaded("jei");
        check(RecipeViewerBridge.available() == jei, "bridge availability does not match JEI presence");
        if (jei) {
            String before = RecipeViewerBridge.viewerSearch();
            RecipeViewerBridge.setSynchronizedSearch(true);
            screen.setSearchForTest("#minecraft:logs");
            check(RecipeViewerBridge.viewerSearch().equals("#minecraft:logs"), "Terminal search not mirrored into JEI");
            RecipeViewerBridge.setSynchronizedSearch(false);
            screen.setSearchForTest("minecraft:diamond");
            check(RecipeViewerBridge.viewerSearch().equals("#minecraft:logs"), "JEI search changed while sync was off");
            RecipeViewerBridge.pushSearch(before);
        }
        com.mojang.logging.LogUtils.getLogger().info("STORAGE_RECIPE_VIEWER_CHECKS_OK jei={}", jei);
    }
    private static void check(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private RecipeViewerChecks() { }
}
