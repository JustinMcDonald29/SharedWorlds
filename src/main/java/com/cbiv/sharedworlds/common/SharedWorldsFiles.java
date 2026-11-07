package com.cbiv.sharedworlds.common;

public final class SharedWorldsFiles {

    private SharedWorldsFiles() {}

    public static final String WORLD_METADATA = "sharedworld.meta.json";
    // Lives only in the source (decision 5): never pulled, never overwritten by a push
    public static final String WORLD_LOCK = "lock.json";
    public static final String GLOBAL_CONFIG = "sharedworlds.json";
}
