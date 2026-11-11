package com.cbiv.sharedworlds.lock;

import com.cbiv.sharedworlds.config.GlobalSharedWorldConfig;
import org.jspecify.annotations.Nullable;

/**
 * Why a launch was blocked: someone else holds the world's lock.
 *
 * @param worldName folder name of the world in saves/
 * @param holder    the lock's contents, or null if the lock file exists but couldn't be read
 * @param source    where the shared copy (and its lock file) lives
 */
public record LockedOut(String worldName, @Nullable Lock holder, GlobalSharedWorldConfig.Source source) {}
