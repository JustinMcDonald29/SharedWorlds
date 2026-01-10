package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.fs.WorldSourceType;

//import java.nio.file.Path;
import java.util.List;


public record GlobalSharedWorldConfig(
        int schemaVersion,
        List<SharedWorldEntry> worlds
) {

    public record SharedWorldEntry(
            String id,
            Local local,
            Source source,
            Behaviour behaviour
    ) {}

    public record Local(
            String name
    ) {}

    public record Source(
            WorldSourceType type,
            //Path path,
            String location
    ) {}

    public record Behaviour(
            boolean syncOnLaunch,
            boolean syncOnClose,
            boolean readOnly
    ) {
        public static Behaviour defaults() {
            return new Behaviour(true, true, false);
        }
    }
}
