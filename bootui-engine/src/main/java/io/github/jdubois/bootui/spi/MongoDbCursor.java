package io.github.jdubois.bootui.spi;

/** A BootUI-owned metadata cursor; close on every path. No cursor survives its explicit action. */
public interface MongoDbCursor<T> extends AutoCloseable {
    boolean hasNext();

    T next();

    @Override
    void close();
}
