package com.cbiv.sharedworlds.service;

/**
 * A share/add action that can't go ahead. The message is written for the player and is shown as is.
 */
public class SharedWorldsException extends Exception {

    public SharedWorldsException(String message) {
        super(message);
    }

    public SharedWorldsException(String message, Throwable cause) {
        super(message, cause);
    }
}
