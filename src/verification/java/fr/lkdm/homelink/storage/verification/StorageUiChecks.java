package fr.lkdm.homelink.storage.verification;

import com.mojang.logging.LogUtils;
import fr.lkdm.homelink.storage.client.screen.StorageScreen;
import fr.lkdm.homelink.storage.menu.StorageMenu;
import net.minecraft.client.Minecraft;
import net.minecraft.client.Screenshot;
import net.minecraft.client.gui.components.AbstractWidget;
import net.minecraft.client.gui.components.Button;
import net.minecraft.client.gui.components.EditBox;
import net.minecraft.network.chat.Component;
import org.lwjgl.glfw.GLFW;

/** Real controls and item hitboxes at two GUI viewports; runs inside the existing client smoke. */
final class StorageUiChecks {
    private static int phase, ticks, previousWidth, previousHeight, previousScale;

    private StorageUiChecks() { }

    static boolean run(Minecraft client, String view) {
        if (client.getOverlay() != null || !(client.screen instanceof StorageScreen screen)) return false;
        if (phase == 0) {
            client.getToasts().clear();
            previousWidth = client.getWindow().getWidth();
            previousHeight = client.getWindow().getHeight();
            previousScale = client.options.guiScale().get();
            resize(client, 1280, 720, 2);
            phase = 1;
            ticks = 0;
            return false;
        }
        if (++ticks == 2) {
            client.getToasts().clear();
            GLFW.glfwSetCursorPos(client.getWindow().getWindow(), 0, 0);
            controls(screen);
            if (!screen.manualOpen()) clickRow(screen);
        }
        if (ticks < 10) return false;
        if (phase <= 4) {
            boolean small = phase >= 3;
            check(screen.width == (small ? 320 : 640) && screen.height == (small ? 240 : 360), "Unexpected GUI viewport");
            String capture = "storage-ui-" + view + (screen.manualOpen() ? "-manual" : "") + (small ? "-small" : "-large") + ".png";
            Screenshot.grab(client.gameDirectory, capture, client.getMainRenderTarget(),
                    message -> LogUtils.getLogger().info("STORAGE_UI_SCREENSHOT {} {}", capture, message.getString()));
            if (phase == 1 || phase == 3) {
                click(screen, "manual");
                check(screen.manualOpen(), "Help control did not open the manual");
            } else {
                click(screen, "manual_back");
                check(!screen.manualOpen(), "Back control did not close the manual");
                if (phase == 2) resize(client, 640, 480, 2);
                else resize(client, previousWidth, previousHeight, previousScale);
            }
            phase++;
            ticks = 0;
            return false;
        }
        LogUtils.getLogger().info("STORAGE_UI_RESPONSIVE_OK view={} large=640x360 small=320x240 controls=true focus=true clicks=true manual=true", view);
        phase = 0;
        ticks = 0;
        return true;
    }

    private static void resize(Minecraft client, int width, int height, int scale) {
        client.options.guiScale().set(scale);
        GLFW.glfwSetWindowSize(client.getWindow().getWindow(), width, height);
        client.resizeDisplay();
    }

    private static void controls(StorageScreen screen) {
        var widgets = screen.children().stream().filter(AbstractWidget.class::isInstance)
                .map(AbstractWidget.class::cast).filter(widget -> widget.visible).toList();
        for (int index = 0; index < widgets.size(); index++) {
            AbstractWidget widget = widgets.get(index);
            check(widget.getWidth() > 0 && widget.getHeight() > 0 && widget.getX() >= 0 && widget.getY() >= 0
                            && widget.getX() + widget.getWidth() <= screen.width
                            && widget.getY() + widget.getHeight() <= screen.height,
                    "Control outside viewport: " + widget.getMessage().getString());
            for (int other = index + 1; other < widgets.size(); other++) {
                AbstractWidget next = widgets.get(other);
                check(widget.getX() >= next.getX() + next.getWidth() || next.getX() >= widget.getX() + widget.getWidth()
                                || widget.getY() >= next.getY() + next.getHeight() || next.getY() >= widget.getY() + widget.getHeight(),
                        "Controls overlap: " + widget.getMessage().getString() + " / " + next.getMessage().getString());
            }
        }
        screen.setFocused(null);
        var reached = new java.util.HashSet<AbstractWidget>();
        long enabled = widgets.stream().filter(widget -> widget.active).count();
        for (int index = 0; index < enabled; index++) {
            screen.keyPressed(GLFW.GLFW_KEY_TAB, 0, 0);
            check(screen.getFocused() instanceof AbstractWidget widget && widget.active && widget.visible && widget.isFocused(),
                    "Tab did not focus a visible enabled control");
            check(reached.add((AbstractWidget) screen.getFocused()), "Tab revisited a control before traversing the whole screen");
        }
        check(reached.size() == enabled, "Tab did not reach every enabled control");
    }

    private static void clickRow(StorageScreen screen) {
        StorageMenu menu = screen.getMenu();
        if (!menu.managesNetwork()) {
            check(screen.displayedRowCount() > 0, "Terminal fixture has no visible item rows");
            var hovered = screen.hovered(screen.getGuiLeft() + 14, screen.getGuiTop() + 86);
            check(hovered != null && !hovered.stack().isEmpty(), "Recipe viewer could not resolve the compact row");
            check(hovered.x() >= 0 && hovered.y() >= 0 && hovered.x() + hovered.width() <= screen.width
                    && hovered.y() + hovered.height() <= screen.height, "Recipe viewer item bounds are outside the viewport");
            check(screen.mouseClicked(hovered.x() + 2, hovered.y() + 2, 0), "Item row click was not consumed");
        } else {
            EditBox name = screen.children().stream().filter(EditBox.class::isInstance).map(EditBox.class::cast)
                    .filter(field -> field.getMessage().getString().equals(Component.translatable("screen.homelink_storage.name").getString()))
                    .findFirst().orElseThrow();
            String draft = name.getValue();
            boolean selected = false;
            for (int row = 0; row < Math.min(6, menu.clientLocations().size()); row++) {
                name.setValue("UI selection probe");
                check(screen.mouseClicked(screen.getGuiLeft() + 14, screen.getGuiTop() + 87 + row * 17, 0), "Inventory row click was not consumed");
                if (menu.clientLocations().stream().anyMatch(location -> !location.name().isEmpty() && location.name().equals(name.getValue()))) {
                    selected = true;
                    break;
                }
            }
            check(selected, "Controller row click did not update the inventory name field");
            name.setValue(draft);
        }
    }

    private static void click(StorageScreen screen, String key) {
        String label = Component.translatable("screen.homelink_storage." + key).getString();
        Button button = screen.children().stream().filter(Button.class::isInstance).map(Button.class::cast)
                .filter(widget -> widget.getMessage().getString().equals(label)).findFirst().orElseThrow();
        check(screen.mouseClicked(button.getX() + button.getWidth() / 2.0, button.getY() + button.getHeight() / 2.0, 0),
                "Control click was not consumed: " + key);
    }

    private static void check(boolean condition, String message) {
        if (!condition) throw new IllegalStateException(message);
    }
}
