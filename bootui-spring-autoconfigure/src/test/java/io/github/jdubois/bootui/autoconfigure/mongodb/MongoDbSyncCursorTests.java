package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;
import static org.mockito.Mockito.*;

import com.mongodb.client.MongoCursor;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import java.util.List;
import java.util.function.Function;
import org.junit.jupiter.api.Test;

class MongoDbSyncCursorTests {
    @Test
    @SuppressWarnings("unchecked")
    void primaryReadAndDecodeFailuresSurviveCleanupFailure() {
        for (String phase : List.of("hasNext", "next", "decode")) {
            MongoCursor<Integer> source = mock(MongoCursor.class);
            MongoDbReadException primary = new MongoDbReadException("TIMEOUT");
            doThrow(new IllegalStateException("private-close")).when(source).close();
            when(source.hasNext()).thenReturn(true);
            when(source.next()).thenReturn(1);
            if ("hasNext".equals(phase)) when(source.hasNext()).thenThrow(primary);
            if ("next".equals(phase)) when(source.next()).thenThrow(primary);
            Throwable actual = catchThrowable(() -> {
                try (var cursor = SpringSyncMongoDbClientAccess.cursor(
                        source,
                        value -> {
                            if ("decode".equals(phase)) throw primary;
                            return value;
                        },
                        new MongoDbReadBudget(1000, 500))) {
                    cursor.hasNext();
                    cursor.next();
                }
            });
            assertThat(actual).isSameAs(primary).hasNoCause();
            assertThat(actual.getSuppressed())
                    .singleElement()
                    .satisfies(cleanup -> assertThat(cleanup)
                            .isInstanceOf(MongoDbReadException.class)
                            .hasMessage("CLEANUP_FAILED")
                            .hasNoCause());
            verify(source).close();
        }
    }

    @Test
    @SuppressWarnings("unchecked")
    void standaloneCloseFailureIsSanitizedAndIdempotent() {
        MongoCursor<Integer> source = mock(MongoCursor.class);
        doThrow(new IllegalStateException("private-close")).when(source).close();
        var cursor =
                SpringSyncMongoDbClientAccess.cursor(source, Function.identity(), new MongoDbReadBudget(1000, 500));
        assertThatThrownBy(cursor::close)
                .isInstanceOf(MongoDbReadException.class)
                .hasMessage("CLEANUP_FAILED")
                .hasNoCause();
        cursor.close();
        assertThat(cursor.hasNext()).isFalse();
        verify(source).close();
        verify(source, never()).hasNext();
    }
}
