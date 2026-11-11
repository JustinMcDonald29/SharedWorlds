package com.cbiv.sharedworlds.ui;

import com.cbiv.sharedworlds.common.SharedWorldsFiles;
import com.cbiv.sharedworlds.fs.WorldSourceType;
import com.cbiv.sharedworlds.lock.Lock;
import com.cbiv.sharedworlds.lock.LockedOut;
import net.minecraft.client.gui.screen.Screen;
import net.minecraft.client.gui.widget.ButtonWidget;
import net.minecraft.client.gui.widget.DirectionalLayoutWidget;
import net.minecraft.client.gui.widget.MultilineTextWidget;
import net.minecraft.client.gui.widget.SimplePositioningWidget;
import net.minecraft.client.gui.widget.TextWidget;
import net.minecraft.screen.ScreenTexts;
import net.minecraft.text.Text;
import net.minecraft.util.Formatting;
import net.minecraft.util.Util;
import org.jspecify.annotations.Nullable;

import java.nio.file.Files;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.ZonedDateTime;
import java.time.format.DateTimeFormatter;
import java.time.format.FormatStyle;

/**
 * Shown instead of silently returning to the world list when someone else is hosting a shared world:
 * who has it, since when, and what to do about a lock left behind by a crash.
 */
public class LockedOutScreen extends Screen {

    private static final int TEXT_WIDTH = 300;

    private final LockedOut lockout;
    private final @Nullable Screen back;
    private DirectionalLayoutWidget layout = DirectionalLayoutWidget.vertical();

    /**
     * @param back the screen to return to (the world list, or the title screen for Quick Play)
     */
    public LockedOutScreen(LockedOut lockout, @Nullable Screen back) {
        super(Text.translatable("sharedworlds.locked.title", lockout.worldName()));
        this.lockout = lockout;
        this.back = back;
    }

    @Override
    protected void init() {
        this.layout = DirectionalLayoutWidget.vertical().spacing(8);
        this.layout.getMainPositioner().alignHorizontalCenter();

        this.layout.add(new TextWidget(this.title, this.textRenderer));

        Lock holder = this.lockout.holder();
        Text body = holder == null
                ? Text.translatable("sharedworlds.locked.unknown")
                : Text.translatable("sharedworlds.locked.holder",
                        holder.getPlayerName() == null ? "?" : holder.getPlayerName(),
                        describeTime(holder.getCreatedAt()));
        this.layout.add(new MultilineTextWidget(body, this.textRenderer).setMaxWidth(TEXT_WIDTH).setCentered(true));

        Path folder = sharedFolder();
        String where = folder != null ? folder.toString() : String.valueOf(this.lockout.source().location());
        this.layout.add(new MultilineTextWidget(
                Text.translatable("sharedworlds.locked.stale", SharedWorldsFiles.WORLD_LOCK, where)
                        .formatted(Formatting.GRAY),
                this.textRenderer).setMaxWidth(TEXT_WIDTH).setCentered(true));

        DirectionalLayoutWidget buttons = DirectionalLayoutWidget.horizontal().spacing(8);
        buttons.add(ButtonWidget.builder(ScreenTexts.BACK, b -> this.close()).width(150).build());
        if (folder != null) {
            buttons.add(ButtonWidget.builder(Text.translatable("sharedworlds.locked.open_folder"),
                    b -> Util.getOperatingSystem().open(folder)).width(150).build());
        }
        this.layout.add(buttons);

        this.layout.forEachChild(child -> this.addDrawableChild(child));
        this.refreshWidgetPositions();
    }

    @Override
    protected void refreshWidgetPositions() {
        this.layout.refreshPositions();
        SimplePositioningWidget.setPos(this.layout, this.getNavigationFocus());
    }

    @Override
    public void close() {
        this.client.setScreen(this.back);
    }

    /** The shared copy's folder, if it's a folder this computer can open. */
    private @Nullable Path sharedFolder() {
        if (this.lockout.source().type() != WorldSourceType.DIRECTORY || this.lockout.source().location() == null) {
            return null;
        }
        try {
            Path folder = Path.of(this.lockout.source().location());
            return Files.isDirectory(folder) ? folder : null;
        } catch (InvalidPathException e) {
            return null;
        }
    }

    /** "8:14 PM, 2 hours ago" (with the date if it wasn't today). */
    private static Text describeTime(@Nullable Instant createdAt) {
        if (createdAt == null) {
            return Text.translatable("sharedworlds.time.unknown");
        }
        ZonedDateTime local = createdAt.atZone(ZoneId.systemDefault());
        DateTimeFormatter format = local.toLocalDate().equals(LocalDate.now())
                ? DateTimeFormatter.ofLocalizedTime(FormatStyle.SHORT)
                : DateTimeFormatter.ofLocalizedDateTime(FormatStyle.SHORT);
        return Text.translatable("sharedworlds.time.at", local.format(format), describeAge(createdAt));
    }

    private static Text describeAge(Instant createdAt) {
        Duration age = Duration.between(createdAt, Instant.now());
        if (age.isNegative() || age.toMinutes() < 1) {
            return Text.translatable("sharedworlds.time.just_now");
        }
        if (age.toHours() < 1) {
            return Text.translatable("sharedworlds.time.minutes", age.toMinutes());
        }
        if (age.toDays() < 1) {
            return Text.translatable("sharedworlds.time.hours", age.toHours());
        }
        return Text.translatable("sharedworlds.time.days", age.toDays());
    }
}
