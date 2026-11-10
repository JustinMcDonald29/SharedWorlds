package com.cbiv.sharedworlds.ui;

import com.cbiv.sharedworlds.SharedWorldsClient;
import com.cbiv.sharedworlds.config.GlobalSharedWorldConfig;
import com.cbiv.sharedworlds.service.SharedWorldsException;
import com.cbiv.sharedworlds.service.SharedWorldsService;
import net.minecraft.client.gui.screen.ConfirmScreen;
import net.minecraft.client.gui.screen.MessageScreen;
import net.minecraft.client.gui.screen.NoticeScreen;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.screen.world.WorldListWidget;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.GridWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.client.gui.widget.ThreePartsLayoutWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.world.level.storage.LevelSummary;
import org.jspecify.annotations.Nullable;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.nio.file.Path;
import java.util.Optional;

/**
 * The "Shared Worlds" screen opened from the world list: pick one of your worlds to share, or add one someone
 * else shared. All the work is done by SharedWorldsService; this screen only asks, confirms and reports.
 *
 * The list is vanilla's own world list in its "pick a world" mode (the one Realms uses to choose a world to
 * upload): no Play overlay, only worlds that can be opened, and double-clicking a world shares it.
 */
public class SharedWorldsScreen extends Screen {

    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorldsScreen");
    private static final int HEADER_HEIGHT = 8 + 9 + 4 + 9 + 8;
    private static final int FOOTER_HEIGHT = 60;

    private final Screen parent;
    private @Nullable LevelSummary selected;
    private @Nullable WorldListWidget worldList;
    private @Nullable TextWidget status;
    private @Nullable ButtonWidget shareButton;
    private ThreePartsLayoutWidget layout = new ThreePartsLayoutWidget(this);

    /**
     * @param parent   the world selection screen, returned to (and refreshed) when done
     * @param selected the world selected there, or null; it starts selected here too
     */
    public SharedWorldsScreen(Screen parent, @Nullable LevelSummary selected) {
        super(Text.translatable("sharedworlds.screen.title"));
        this.parent = parent;
        this.selected = selected;
    }

    private static SharedWorldsService service() {
        return SharedWorldsClient.service();
    }

    /** Builds the whole screen. Also runs on every resize (Screen's default), keeping the selection. */
    @Override
    protected void init() {
        this.layout = new ThreePartsLayoutWidget(this, HEADER_HEIGHT, FOOTER_HEIGHT);

        DirectionalLayoutWidget header = this.layout.addHeader(DirectionalLayoutWidget.vertical().spacing(4));
        header.getMainPositioner().alignHorizontalCenter();
        header.add(new TextWidget(this.title, this.textRenderer));
        this.status = header.add(new TextWidget(Text.empty(), this.textRenderer).setMaxWidth(this.width - 40));

        // Reuse the levels already loaded by the world list (or by this screen before a resize)
        WorldListWidget predecessor = this.worldList != null
                ? this.worldList
                : SelectWorldScreenHook.worldList(this.parent).orElse(null);
        this.worldList = this.layout.addBody(new WorldListWidget.Builder(this.client, this)
                .width(this.width)
                .height(this.layout.getContentHeight())
                .predecessor(predecessor)
                .selectionCallback(this::onSelected)
                .confirmationCallback(entry -> {
                    onSelected(entry.getLevel());
                    if (this.shareButton != null && this.shareButton.active) {
                        startShare();
                    }
                })
                .uploadWorld()
                .toWidget());

        GridWidget footer = this.layout.addFooter(new GridWidget().setColumnSpacing(8).setRowSpacing(4));
        footer.getMainPositioner().alignHorizontalCenter();
        GridWidget.Adder adder = footer.createAdder(2);
        this.shareButton = adder.add(ButtonWidget.builder(Text.empty(), b -> startShare()).width(150).build());
        adder.add(ButtonWidget.builder(Text.translatable("sharedworlds.add.button"), b -> startAdd())
                .width(150).build());
        adder.add(ButtonWidget.builder(ScreenTexts.DONE, b -> this.close()).width(150).build(), 2);

        this.layout.forEachChild(child -> this.addDrawableChild(child));
        this.worldList.position(this.width, this.layout);
        this.layout.refreshPositions();

        reselect();
    }

    /** Selects the remembered world in the (new) list, if it's in it. */
    private void reselect() {
        String name = this.selected == null ? null : this.selected.getName();
        WorldListWidget.WorldEntry match = null;
        if (name != null && this.worldList != null) {
            for (WorldListWidget.Entry entry : this.worldList.children()) {
                if (entry instanceof WorldListWidget.WorldEntry world && world.getLevel().getName().equals(name)) {
                    match = world;
                    break;
                }
            }
        }
        if (match != null) {
            this.worldList.setSelected(match); // calls onSelected
        } else {
            onSelected(null);
        }
    }

    /** Updates the Share button and the status line for the selected world. */
    private void onSelected(@Nullable LevelSummary level) {
        this.selected = level;
        if (this.shareButton == null || this.status == null) {
            return;
        }
        Text status;
        boolean canShare = false;
        if (level == null) {
            status = Text.translatable("sharedworlds.share.none_selected");
        } else {
            Optional<GlobalSharedWorldConfig.SharedWorldEntry> shared = service().findShared(level.getName());
            if (shared.isPresent()) {
                String location = shared.get().source() == null ? "?" : shared.get().source().location();
                status = Text.translatable("sharedworlds.share.already", level.getDisplayName(), location);
            } else if (level.isUnavailable()) {
                status = Text.translatable("sharedworlds.share.unavailable", level.getDisplayName());
            } else {
                status = Text.translatable("sharedworlds.share.not_shared", level.getDisplayName());
                canShare = true;
            }
        }
        this.status.setMessage(status.copy().formatted(Formatting.GRAY));
        this.shareButton.setMessage(level == null
                ? Text.translatable("sharedworlds.share.button.none")
                : Text.translatable("sharedworlds.share.button", level.getDisplayName()));
        this.shareButton.active = canShare;
        this.layout.refreshPositions();
    }

    /** Back to the world list. It's always reloaded: vanilla closes its icons when the player leaves it. */
    @Override
    public void close() {
        returnToWorldList();
    }

    @Override
    public void removed() {
        if (this.worldList != null) {
            this.worldList.children().forEach(WorldListWidget.Entry::close);
        }
    }

    /* ------------------------------------------------------------
       Share
       ------------------------------------------------------------ */

    private void startShare() {
        if (this.selected == null) {
            return;
        }
        String localName = this.selected.getName();
        String displayName = this.selected.getDisplayName();
        Optional<Path> folder = FolderPicker.pick(Text.translatable("sharedworlds.pick.share").getString());
        if (folder.isEmpty()) {
            return;
        }

        SharedWorldsService.SharePlan plan;
        try {
            plan = service().planShare(localName, folder.get());
        } catch (SharedWorldsException e) {
            showError(Text.translatable("sharedworlds.share.failed"), e.getMessage());
            return;
        }

        this.client.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (!confirmed) {
                        this.client.setScreen(this);
                        return;
                    }
                    runInBackground(Text.translatable("sharedworlds.share.progress", displayName),
                            () -> service().share(plan),
                            Text.translatable("sharedworlds.share.failed"),
                            () -> this.client.setScreen(new NoticeScreen(this::returnToWorldList,
                                    Text.translatable("sharedworlds.share.done.title"),
                                    Text.translatable("sharedworlds.share.done.message", displayName,
                                            plan.target().toString()))));
                },
                Text.translatable("sharedworlds.share.confirm.title", displayName),
                Text.translatable("sharedworlds.share.confirm.message", plan.target().toString()),
                Text.translatable("sharedworlds.share.confirm.yes"),
                ScreenTexts.CANCEL));
    }

    /* ------------------------------------------------------------
       Add
       ------------------------------------------------------------ */

    private void startAdd() {
        Optional<Path> folder = FolderPicker.pick(Text.translatable("sharedworlds.pick.add").getString());
        if (folder.isEmpty()) {
            return;
        }

        SharedWorldsService.AddPlan plan;
        try {
            plan = service().planAdd(folder.get());
        } catch (SharedWorldsException e) {
            showError(Text.translatable("sharedworlds.add.failed"), e.getMessage());
            return;
        }

        String source = plan.sourceDir().toString();
        Text message = plan.linkExisting()
                ? Text.translatable("sharedworlds.add.confirm.link", plan.localName(), source)
                : Text.translatable("sharedworlds.add.confirm.copy", source, plan.localName());

        this.client.setScreen(new ConfirmScreen(
                confirmed -> {
                    if (!confirmed) {
                        this.client.setScreen(this);
                        return;
                    }
                    runInBackground(Text.translatable("sharedworlds.add.progress"),
                            () -> service().add(plan),
                            Text.translatable("sharedworlds.add.failed"),
                            () -> this.client.setScreen(new NoticeScreen(this::returnToWorldList,
                                    Text.translatable("sharedworlds.add.done.title"),
                                    Text.translatable("sharedworlds.add.done.message", plan.localName()))));
                },
                Text.translatable("sharedworlds.add.confirm.title"),
                message,
                Text.translatable("sharedworlds.add.confirm.yes"),
                ScreenTexts.CANCEL));
    }

    /* ------------------------------------------------------------
       Helpers
       ------------------------------------------------------------ */

    @FunctionalInterface
    private interface Action {
        void run() throws SharedWorldsException;
    }

    /**
     * Shows a "please wait" screen and runs a slow file action off the render thread, so the game doesn't
     * freeze while a world is copied. The result is handled back on the render thread.
     */
    private void runInBackground(Text waitMessage, Action action, Text failureTitle, Runnable onSuccess) {
        this.client.setScreen(new MessageScreen(waitMessage));
        Thread worker = new Thread(() -> {
            String error = null;
            try {
                action.run();
            } catch (SharedWorldsException e) {
                error = e.getMessage();
            } catch (RuntimeException e) {
                LOGGER.error("Shared world action failed", e);
                error = Text.translatable("sharedworlds.error.unexpected", String.valueOf(e.getMessage())).getString();
            }
            String finalError = error;
            this.client.execute(() -> {
                if (finalError == null) {
                    onSuccess.run();
                } else {
                    showError(failureTitle, finalError);
                }
            });
        }, "SharedWorlds action");
        worker.start();
    }

    private void showError(Text title, String message) {
        this.client.setScreen(new NoticeScreen(() -> this.client.setScreen(this), title, Text.literal(message)));
    }

    /** Back to the world list, reloading it so added worlds appear. */
    private void returnToWorldList() {
        SelectWorldScreenHook.worldList(this.parent).ifPresentOrElse(
                list -> list.refresh(),           // reloads the worlds, then shows the world list again
                () -> this.client.setScreen(this.parent));
    }
}
