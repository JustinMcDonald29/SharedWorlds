package com.cbiv.sharedworlds.config;

/**
 * What to do with a shared world at launch, after comparing the local copy with its source.
 */
public enum WorldDecision {
    /** Same world, same version, not played here since: launch the local copy. */
    UP_TO_DATE,
    /** Same world and the source is newer: inject the source, then launch. */
    PULL,
    /** Played here since the last pull/push but not pushed (e.g. the push failed): launch it, keep the lock. */
    LOCAL_AHEAD,
    /** Both copies changed since they last matched: a person has to choose. Launch the local copy unshared. */
    DIVERGED,
    /** The two copies are different worlds (IDs or seeds differ): never inject. Launch the local copy unshared. */
    DIFFERENT_WORLD,
    /** Not enough information to compare (e.g. level.dat unreadable): launch the local copy unshared. */
    CANNOT_COMPARE
}
