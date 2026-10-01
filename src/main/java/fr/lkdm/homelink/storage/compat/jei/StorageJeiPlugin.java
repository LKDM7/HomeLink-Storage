package fr.lkdm.homelink.storage.compat.jei;

import fr.lkdm.homelink.storage.HomeLinkStorage;
import fr.lkdm.homelink.storage.client.recipe.RecipeViewerBridge;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.compat.ViewerInfo;
import java.util.Optional;
import mezz.jei.api.IModPlugin;
import mezz.jei.api.JeiPlugin;
import mezz.jei.api.gui.builder.IClickableIngredientFactory;
import mezz.jei.api.gui.handlers.IGuiContainerHandler;
import mezz.jei.api.registration.IGuiHandlerRegistration;
import mezz.jei.api.registration.IRecipeRegistration;
import mezz.jei.api.registration.IRecipeTransferRegistration;
import mezz.jei.api.runtime.IClickableIngredient;
import mezz.jei.api.runtime.IJeiRuntime;
import net.minecraft.network.chat.Component;
import net.minecraft.resources.ResourceLocation;
import net.minecraft.world.item.ItemStack;

/** Optional JEI integration, loaded by JEI only: information pages, search sync, R/U on the grid and ingredient fetching. */
@JeiPlugin
public final class StorageJeiPlugin implements IModPlugin {
    private static final ResourceLocation ID = ResourceLocation.fromNamespaceAndPath(HomeLinkStorage.MOD_ID, "jei");

    @Override public ResourceLocation getPluginUid() { return ID; }

    @Override public void registerRecipes(IRecipeRegistration registration) {
        ViewerInfo.pages().forEach((item, lines) -> registration.addItemStackInfo(new ItemStack(item), lines.toArray(Component[]::new)));
    }

    @Override public void registerGuiHandlers(IGuiHandlerRegistration registration) {
        registration.addGuiContainerHandler(StorageScreen.class, new IGuiContainerHandler<StorageScreen>() {
            @Override public Optional<? extends IClickableIngredient<?>> getClickableIngredientUnderMouse(
                    IClickableIngredientFactory factory, StorageScreen screen, double mouseX, double mouseY) {
                StorageScreen.Hovered hovered = screen.hovered(mouseX, mouseY);
                if (hovered == null) return Optional.empty();
                return factory.createBuilder(hovered.stack()).buildWithArea(hovered.x(), hovered.y(), hovered.width(), hovered.height());
            }
        });
    }

    @Override public void registerRecipeTransferHandlers(IRecipeTransferRegistration registration) {
        registration.addUniversalRecipeTransferHandler(new StorageTransferHandler(registration.getTransferHelper()));
    }

    @Override public void onRuntimeAvailable(IJeiRuntime runtime) {
        RecipeViewerBridge.install(new RecipeViewerBridge.Search() {
            @Override public String get() { return runtime.getIngredientFilter().getFilterText(); }
            @Override public void set(String text) { runtime.getIngredientFilter().setFilterText(text); }
        });
    }

    @Override public void onRuntimeUnavailable() { RecipeViewerBridge.clear(); }
}
