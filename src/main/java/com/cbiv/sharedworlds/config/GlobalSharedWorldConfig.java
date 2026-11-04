package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.fs.WorldSourceType;

//import java.nio.file.Path;
import java.util.List;
import java.util.Optional;


public record GlobalSharedWorldConfig(
        int schemaVersion,
        List<SharedWorldEntry> worlds
) {

    /**
     * A config with no shared worlds, used when the config file is missing or failed to load.
     */
    public static GlobalSharedWorldConfig empty() {
        return new GlobalSharedWorldConfig(0, List.of());
    }

    /**
     * Finds the entry for a world by its folder name in saves/.
     */
    public Optional<SharedWorldEntry> findByLocalName(String localName) {
        if (worlds == null || localName == null) {
            return Optional.empty();
        }
        return worlds.stream()
                .filter(entry -> entry.local() != null && localName.equals(entry.local().name()))
                .findFirst();
    }

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
