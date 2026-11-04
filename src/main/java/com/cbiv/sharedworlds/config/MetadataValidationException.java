package com.cbiv.sharedworlds.config;

public class MetadataValidationException extends Exception {
    public MetadataValidationException(String message) {
        super(message);
    }

    public MetadataValidationException(String message, Throwable cause){
        super(message, cause);
    }
}

class SchemaMismatchException extends MetadataValidationException {
    public SchemaMismatchException(String message) {
        super(message);
    }
    public SchemaMismatchException(String message, Throwable cause){
        super(message, cause);
    }

}

class CorruptMetadataException extends MetadataValidationException {
    public CorruptMetadataException(String message) {
        super(message);
    }
    public CorruptMetadataException(String message, Throwable cause){
        super(message, cause);
    }
}

class UnsafeWorldException extends MetadataValidationException {
    public UnsafeWorldException(String message) {
        super(message);
    }
    public UnsafeWorldException(String message, Throwable cause){
        super(message, cause);
    }
}
