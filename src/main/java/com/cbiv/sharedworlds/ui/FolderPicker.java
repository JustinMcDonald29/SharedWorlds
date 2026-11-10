package com.cbiv.sharedworlds.ui;

import org.lwjgl.util.tinyfd.TinyFileDialogs;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.io.File;
import java.nio.file.InvalidPathException;
import java.nio.file.Path;
import java.util.Optional;

/**
 * The operating system's own "choose a folder" dialog, through LWJGL's TinyFileDialogs (bundled with
 * Minecraft, so no extra dependency). Must be called on the render thread; the game waits while it's open.
 */
final class FolderPicker {

    private static final Logger LOGGER = LoggerFactory.getLogger("SharedWorlds FolderPicker");

    // Where the next dialog opens: the last folder picked this session
    private static String lastFolder = System.getProperty("user.home");

    private FolderPicker() {}

    /** @return the folder picked, or empty if the player cancelled */
    static Optional<Path> pick(String title) {
        String start = lastFolder.endsWith(File.separator) ? lastFolder : lastFolder + File.separator;
        String result = TinyFileDialogs.tinyfd_selectFolderDialog(title, start);
        if (result == null || result.isBlank()) {
            return Optional.empty();
        }
        try {
            Path folder = Path.of(result);
            lastFolder = folder.toString();
            return Optional.of(folder);
        } catch (InvalidPathException e) {
            LOGGER.warn("The folder dialog returned an invalid path: {}", result, e);
            return Optional.empty();
        }
    }
}
