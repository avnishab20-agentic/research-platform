package com.comeback.researchplatform.agentservice.fixtures;

/** Thrown when fixture-mode replay has no recorded response for a given
 *  request -- loud and immediate, never a silent empty/default response
 *  standing in for a real one. */
public class FixtureMissException extends RuntimeException {
    public FixtureMissException(String filename, String key) {
        super("No fixture recorded in " + filename + " for key " + key
                + " -- run once with fixtures.record-mode=true against the real "
                + "services to capture it, then commit the updated fixture file.");
    }
}
