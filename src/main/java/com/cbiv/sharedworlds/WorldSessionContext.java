package com.cbiv.sharedworlds;

import java.nio.file.Path;

public class WorldSessionContext {
    private static boolean serverStarted = false;
    private static Path worldDirectory;

    public static void setWorldDirectory(Path worldDir) {
        worldDirectory = worldDir;
    }

    public static Path getWorldDirectory() {
        return worldDirectory;
    }

    public static void markServerStarted() {
        serverStarted = true;
    }

    public static boolean isServerStarted() {
        return serverStarted;
    }


    public static void clear() {
        serverStarted = false;
        worldDirectory = null;
    }
}
