package com.cbiv.sharedworlds.config;

import com.cbiv.sharedworlds.fs.WorldSourceType;
import com.google.gson.*;

import java.lang.reflect.Type;

public class WorldSourceTypeAdapter
        implements JsonSerializer<WorldSourceType>, JsonDeserializer<WorldSourceType> {

    @Override
    public JsonElement serialize(
            WorldSourceType src,
            Type typeOfSrc,
            JsonSerializationContext context
    ) {
        return new JsonPrimitive(src.id());
    }

    @Override
    public WorldSourceType deserialize(
            JsonElement json,
            Type typeOfT,
            JsonDeserializationContext context
    ) throws JsonParseException {
        return WorldSourceType.fromId(json.getAsString());
    }
}
