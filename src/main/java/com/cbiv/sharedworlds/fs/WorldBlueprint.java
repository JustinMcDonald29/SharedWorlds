package com.cbiv.sharedworlds.fs;

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
}
