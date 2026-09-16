package io.github.jdubois.bootui.engine.mongodb;

/** Safe, fixed reason code only. Never retain a driver exception/cause or its message. */
public final class MongoDbReadException extends RuntimeException {
    private final String code;

    public MongoDbReadException(String code) {
        super(code);
        this.code = code;
    }

    public String code() {
        return code;
    }
}
