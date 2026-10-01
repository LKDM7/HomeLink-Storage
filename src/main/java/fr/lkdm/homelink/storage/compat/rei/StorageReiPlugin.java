package fr.lkdm.homelink.storage.compat.rei;

import dev.architectury.event.CompoundEventResult;
import fr.lkdm.homelink.storage.client.recipe.RecipeViewerBridge;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.compat.ViewerInfo;
import me.shedaniel.rei.api.client.REIRuntime;
import me.shedaniel.rei.api.client.plugins.REIClientPlugin;
import me.shedaniel.rei.api.client.registry.display.DisplayRegistry;
import me.shedaniel.rei.api.client.registry.screen.ScreenRegistry;
import me.shedaniel.rei.api.client.registry.transfer.TransferHandlerRegistry;
import me.shedaniel.rei.api.common.util.EntryStacks;
import me.shedaniel.rei.forge.REIPluginClient;
import me.shedaniel.rei.plugin.common.displays.DefaultInformationDisplay;
import net.minecraft.world.item.ItemStack;

/**
 * Optional REI integration, loaded by REI only: the same services as the JEI plugin — information
 * pages, R/U on the Terminal grid, search synchronisation and ingredient fetching.
 */
@REIPluginClient
public final class StorageReiPlugin implements REIClientPlugin {
    @Override public void registerDisplays(DisplayRegistry registry) {
        ViewerInfo.pages().forEach((item, lines) -> registry.add(DefaultInformationDisplay
                .createFromEntry(EntryStacks.of(item), new ItemStack(item).getHoverName()).lines(lines)));
    }

    @Override public void registerScreens(ScreenRegistry registry) {
        registry.registerFocusedStack((screen, mouse) -> {
            if (!(screen instanceof StorageScreen storage)) return CompoundEventResult.pass();
            StorageScreen.Hovered hovered = storage.hovered(mouse.x, mouse.y);
            return hovered == null ? CompoundEventResult.pass() : CompoundEventResult.interruptTrue(EntryStacks.of(hovered.stack()));
        });
        // The search field exists once REI's overlay is built; read it lazily at each use.
        RecipeViewerBridge.install(new RecipeViewerBridge.Search() {
            @Override public String get() {
                var field = REIRuntime.getInstance().getSearchTextField();
                return field == null ? "" : field.getText();
            }
            @Override public void set(String text) {
                var field = REIRuntime.getInstance().getSearchTextField();
                if (field != null) field.setText(text);
            }
        });
    }

    @Override public void registerTransferHandlers(TransferHandlerRegistry registry) {
        registry.register(new StorageReiTransferHandler());
    }
}
