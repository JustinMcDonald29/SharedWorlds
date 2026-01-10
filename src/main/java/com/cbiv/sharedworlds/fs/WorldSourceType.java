package com.cbiv.sharedworlds.fs;

public enum WorldSourceType {

    DIRECTORY("directory"),

    // Possible future implementations
    GOOGLE_DRIVE("google_drive"),
    GIT("git"),
    SYNCTHING("syncthing");

    private final String id;

    WorldSourceType(String id) {
        this.id = id;
    }

    public String id() {
        return id;
    }

    public static WorldSourceType fromId(String id) {
        for (WorldSourceType type : values()) {
            if(type.id.equalsIgnoreCase(id)) {
                return type;
            }
        }
        throw new IllegalArgumentException("Unknown WorldSourceType: " + id);
    }
}
