package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.fs.WorldSourceType;

import java.nio.file.Path;
import java.time.Instant;

public record SharedWorldMetadata(
        int schemaVersion,
        Association association,
        State state
) {

    public static final int CURRENT_SCHEMA = 1;

    public static SharedWorldMetadata initial(
            String sourceId,
            WorldSourceType sourceType,
            String sourceLocation
            //Path sourcePath
    ) {
        return new SharedWorldMetadata(
                CURRENT_SCHEMA,
                new Association(
                        sourceId,
                        sourceType.id(),
                        sourceLocation
                        //sourcePath
                ),
                State.initial()
        );
    }

    public record Association(
            String sourceId,
            String sourceType,
            String sourceLocation
            //Path sourcePath
    ) {}

    public record State(
            String worldVersion,
            String lastPulled,
            String lastPushed
    ) {
        public static State initial() {
            String now = Instant.now().toString();
            return new State(
                    "unknown",
                    now,
                    now
            );
        }
    }
}

