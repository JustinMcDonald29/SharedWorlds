package com.cbiv.sharedworlds.fs;

import java.nio.file.Path;
import java.io.IOException;

public interface WorldMaterialization extends AutoCloseable {

    /**
     * Directory containing a full Minecraft world layout.
     */
    Path worldDirectory();

    boolean isReusable();

    /**
     * Cleanup temp files, unlock handles, etc.
     */
    @Override
    void close() throws IOException;
}
