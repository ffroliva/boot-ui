package io.github.jdubois.bootui.engine.mongodb;

/** A safe client-facing refusal, shared by all transports. */
public final class MongoDbRequestException extends RuntimeException {
    private final int status;

    public MongoDbRequestException(int status, String message) {
        super(message);
        this.status = status;
    }

    public int status() {
        return status;
    }
}
