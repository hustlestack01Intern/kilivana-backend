package com.kilivana.media;

/**
 * A request the client got wrong: an empty upload, an unsupported type, a storage key
 * that escapes the root. Deliberately a sibling of {@link MediaStorageException} rather
 * than a subclass, so neither family can be caught and re-labelled as the other — a
 * rejected file is the caller's to fix, a broken volume is ours, and only the second
 * is worth a load balancer backing off.
 */
public class InvalidMediaException extends RuntimeException {

    public InvalidMediaException(String message) {
        super(message);
    }
}
