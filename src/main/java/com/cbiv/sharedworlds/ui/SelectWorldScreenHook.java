package com.cbiv.sharedworlds.ui;

import net.fabricmc.fabric.api.client.screen.v1.ScreenEvents;
import net.fabricmc.fabric.api.client.screen.v1.Screens;
import net.minecraft.client.MinecraftClient;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.SelectWorldScreen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.tooltip.Tooltip;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.TextFieldWidget;
import net.minecraft.text.Text;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;

import java.util.Map;
import java.util.Optional;
import java.util.WeakHashMap;

/**
 * Adds the "Shared Worlds..." button to the world selection screen, to the right of the search box.
 * Uses Fabric's screen events, so no mixin.
 *
 * Fabric fires AFTER_INIT on every resize too, but SelectWorldScreen only repositions its own widgets on a
 * resize (it doesn't rebuild), so the button added last time is still there: it's removed and added again at
 * the new position rather than stacking a second one.
 */
public final class SelectWorldScreenHook {

    private static final int GAP = 4;
    private static final int MAX_WIDTH = 100;
    private static final int MIN_WIDTH = 20;

    // The button currently on each world selection screen (weak: screens are dropped when closed)
    private static final Map<Screen, ButtonWidget> BUTTONS = new WeakHashMap<>();

    private SelectWorldScreenHook() {}

    public static void register() {
        ScreenEvents.AFTER_INIT.register((client, screen, width, height) -> {
            if (screen instanceof SelectWorldScreen selectScreen) {
                addButton(client, selectScreen, width);
            }
        });
    }

    private static void addButton(MinecraftClient client, SelectWorldScreen screen, int screenWidth) {
        ButtonWidget previous = BUTTONS.remove(screen);
        if (previous != null) {
            Screens.getButtons(screen).remove(previous);
        }

        Optional<TextFieldWidget> search = screen.children().stream()
                .filter(TextFieldWidget.class::isInstance)
                .map(TextFieldWidget.class::cast)
                .findFirst();
        if (search.isEmpty()) {
            return; // the screen's layout changed in a Minecraft update; fail quietly rather than crash
        }

        int x = search.get().getRight() + GAP;
        int width = Math.min(MAX_WIDTH, screenWidth - x - GAP);
        if (width < MIN_WIDTH) {
            return;
        }
        Text full = Text.translatable("sharedworlds.button.open");
        Text label = client.textRenderer.getWidth(full) + 8 <= width ? full : Text.translatable("sharedworlds.button.open.short");

        ButtonWidget button = ButtonWidget.builder(label,
                        b -> client.setScreen(new SharedWorldsScreen(screen, selectedWorld(screen))))
                .dimensions(x, search.get().getY(), width, search.get().getHeight())
                .tooltip(Tooltip.of(Text.translatable("sharedworlds.button.open.tooltip")))
                .build();
        Screens.getButtons(screen).add(button);
        BUTTONS.put(screen, button);
    }

    /** The world selected in the list right now, or null. */
    private static @Nullable LevelSummary selectedWorld(SelectWorldScreen screen) {
        return worldList(screen)
                .flatMap(WorldListWidget::getSelectedAsOptional)
                .map(WorldListWidget.WorldEntry::getLevel)
                .orElse(null);
    }

    static Optional<WorldListWidget> worldList(Screen screen) {
        return screen.children().stream()
                .filter(WorldListWidget.class::isInstance)
                .map(WorldListWidget.class::cast)
                .findFirst();
    }
}
