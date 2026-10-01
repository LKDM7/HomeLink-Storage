package fr.lkdm.homelink.storage.verification;

import fr.lkdm.homelink.storage.client.recipe.RecipeViewerBridge;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.compat.ViewerInfo;
import fr.lkdm.homelink.storage.registry.StorageRegistries;
import net.neoforged.fml.ModList;

/** Real client: the Terminal only talks to JEI or REI through the bridge their plugins install. */
public final class RecipeViewerChecks {
    public static void run(StorageScreen screen) {
        boolean jei = ModList.get().isLoaded("jei");
        boolean rei = ModList.get().isLoaded("roughlyenoughitems");
        check(RecipeViewerBridge.available() == (jei || rei), "bridge availability does not match the recipe viewer");
        if (jei || rei) {
            String before = RecipeViewerBridge.viewerSearch();
            RecipeViewerBridge.setSynchronizedSearch(true);
            screen.setSearchForTest("#minecraft:logs");
            check(RecipeViewerBridge.viewerSearch().equals("#minecraft:logs"), "Terminal search not mirrored into the viewer");
            RecipeViewerBridge.setSynchronizedSearch(false);
            screen.setSearchForTest("minecraft:diamond");
            check(RecipeViewerBridge.viewerSearch().equals("#minecraft:logs"), "Viewer search changed while sync was off");
            RecipeViewerBridge.pushSearch(before);
        }
        var pages = ViewerInfo.pages();
        check(pages.containsKey(StorageRegistries.CONTROLLER.get().asItem()) && pages.size() == 7, "information pages missing: " + pages.size());
        com.mojang.logging.LogUtils.getLogger().info("STORAGE_RECIPE_VIEWER_CHECKS_OK jei={} rei={}", jei, rei);
    }
    private static void check(boolean value, String reason) { if (!value) throw new IllegalStateException(reason); }
    private RecipeViewerChecks() { }
}
