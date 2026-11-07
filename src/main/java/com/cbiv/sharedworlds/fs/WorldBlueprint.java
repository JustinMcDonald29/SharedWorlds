package com.cbiv.sharedworlds.fs;

import java.util.List;

/*
Early idea for a system to ensure compatibility with other mods.  For security reasons world folders will be built from
specific parts, rather than trusting a given source and downloading an entire directory.  i.e. For a vanilla
world, we would atomically request (or some other method, we could download the whole directory and then
trim out parts that aren't expected for a minecraft world): advancements\, data\, DIM1\, DIM-1\, entities\, playerdata\,
poi\, region\, stats\, icon.png, level.dat, level.dat_old, session.lock(part of this mod),
sharedworld.meta.json(part of this mod).  Then the world would be materialized from those parts.  This is to prevent
the downloading of malicious files (although minecraft shouldn't be "running" these files anyway it's better to be safe
than sorry (or zip-bombed))

Should some other mod make some changes to the structure of a world folder there needs to be a system to ensure
compatibility with this mod such that it doesn't break playability.  For example a mod that adds another dimension,
thus (likely) requiring another DIM\.
 */

public class WorldBlueprint {

    /**
     * Top-level entries of a world folder that are synced, both when pulling and pushing (decision log, "What to
     * copy on pull"). Never synced: session.lock, level.dat_old, lock.json, and anything not listed.
     * sharedworld.meta.json is handled separately: it's written last on push.
     *
     * TODO extend with mod rules (decision 7)
     */
    public static final List<String> WORLD_ENTRIES = List.of(
            "level.dat",
            "region",
            "entities",
            "DIM-1",
            "DIM1",
            "dimensions",
            "data",
            "datapacks",
            "poi",
            "playerdata",
            "advancements",
            "stats"
    );

    private WorldBlueprint() {}
}
