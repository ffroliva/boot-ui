package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

class MongoDbReactiveCursorTests {
    @Test
    void demandIsOneItemAndClosingCancelsWithoutNewDemand() {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<Subscriber<? super Integer>> receiver = new AtomicReference<>();
        Publisher<Integer> publisher = subscriber -> {
            receiver.set(subscriber);
            subscriber.onSubscribe(new Subscription() {
                public void request(long count) {
                    assertThat(count).isEqualTo(1);
                    subscriber.onNext(requests.incrementAndGet());
                }

                public void cancel() {
                    cancellations.incrementAndGet();
                }
            });
        };
        try (var cursor =
                new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(1000, 100))) {
            assertThat(requests).hasValue(0);
            assertThat(cursor.hasNext()).isTrue();
            assertThat(cursor.hasNext()).isTrue();
            assertThat(requests).hasValue(1);
            assertThat(cursor.next()).isEqualTo(1);
        }
        receiver.get().onNext(99);
        assertThat(requests).hasValue(1);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void waitingTimeoutCancelsAndDoesNotRetainRawFailure() {
        AtomicInteger cancellations = new AtomicInteger();
        Publisher<Integer> publisher = subscriber -> subscriber.onSubscribe(new Subscription() {
            public void request(long count) {}

            public void cancel() {
                cancellations.incrementAndGet();
            }
        });
        try (var cursor = new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(100, 10))) {
            assertThatThrownBy(cursor::hasNext)
                    .isInstanceOf(MongoDbReadException.class)
                    .hasMessage("TIMEOUT");
        }
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void interruptedConsumerCancelsAndKeepsInterruptFlag() {
        AtomicInteger cancellations = new AtomicInteger();
        Publisher<Integer> publisher = subscriber -> subscriber.onSubscribe(new Subscription() {
            public void request(long count) {}

            public void cancel() {
                cancellations.incrementAndGet();
            }
        });
        try (var cursor =
                new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(1000, 100))) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(cursor::hasNext).hasMessage("CANCELLED");
            assertThat(Thread.currentThread().isInterrupted()).isTrue();
        } finally {
            Thread.interrupted();
        }
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void completionAfterLastItemIsNotLost() {
        Publisher<Integer> publisher = subscriber -> subscriber.onSubscribe(new Subscription() {
            public void request(long count) {
                subscriber.onNext(7);
                subscriber.onComplete();
            }

            public void cancel() {}
        });
        try (var cursor =
                new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(1000, 100))) {
            assertThat(cursor.next()).isEqualTo(7);
            assertThat(cursor.hasNext()).isFalse();
        }
    }

    @Test
    void slowMapperDoesNotBlockCallbacksOrCancellation() throws Exception {
        var workers = Executors.newFixedThreadPool(3);
        CountDownLatch mapping = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        AtomicReference<Subscriber<? super Integer>> receiver = new AtomicReference<>();
        AtomicInteger cancellations = new AtomicInteger();
        Publisher<Integer> publisher = target -> {
            receiver.set(target);
            target.onSubscribe(new Subscription() {
                public void request(long count) {
                    target.onNext(1);
                }

                public void cancel() {
                    cancellations.incrementAndGet();
                }
            });
        };
        var cursor = new MongoDbReactiveCursor<>(
                publisher,
                value -> {
                    mapping.countDown();
                    try {
                        assertThat(release.await(5, TimeUnit.SECONDS)).isTrue();
                    } catch (InterruptedException error) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(error);
                    }
                    return value;
                },
                new MongoDbReadBudget(10000, 5000));
        try {
            var consumer = workers.submit(() -> catchThrowable(cursor::next));
            assertThat(mapping.await(5, TimeUnit.SECONDS)).isTrue();
            workers.submit(() -> {
                        receiver.get().onError(new IllegalStateException("private"));
                        receiver.get().onNext(2);
                        receiver.get().onComplete();
                    })
                    .get(1, TimeUnit.SECONDS);
            workers.submit(cursor::close).get(1, TimeUnit.SECONDS);
            assertThat(cancellations).hasValue(1);
            release.countDown();
            assertThat(consumer.get(5, TimeUnit.SECONDS))
                    .isInstanceOf(MongoDbReadException.class)
                    .hasMessage("CANCELLED");
            assertThat(cursor.hasNext()).isFalse();
        } finally {
            release.countDown();
            cursor.close();
            workers.shutdownNow();
            assertThat(workers.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void requestAndCancelCanWaitForCallbacksOnAnotherThread() throws Exception {
        var callbacks = Executors.newSingleThreadExecutor();
        Publisher<Integer> publisher = target -> target.onSubscribe(new Subscription() {
            public void request(long count) {
                awaitCallback(() -> target.onNext(1));
            }

            public void cancel() {
                awaitCallback(target::onComplete);
            }

            private void awaitCallback(Runnable callback) {
                try {
                    callbacks.submit(callback).get(1, TimeUnit.SECONDS);
                } catch (Exception error) {
                    throw new IllegalStateException("Callback blocked", error);
                }
            }
        });
        try (var cursor =
                new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(10000, 5000))) {
            assertThat(cursor.next()).isEqualTo(1);
        } finally {
            callbacks.shutdownNow();
            assertThat(callbacks.awaitTermination(5, TimeUnit.SECONDS)).isTrue();
        }
    }

    @Test
    void primarySubscribeRequestDecodeAndTerminalFailuresSurviveCancelFailure() {
        for (String phase : List.of("subscribe", "request", "decode", "terminal")) {
            MongoDbReadException primary = new MongoDbReadException("TIMEOUT");
            AtomicInteger cancellations = new AtomicInteger();
            Publisher<Integer> publisher = target -> {
                target.onSubscribe(new Subscription() {
                    public void request(long count) {
                        if ("request".equals(phase)) throw primary;
                        if ("terminal".equals(phase)) target.onError(primary);
                        else target.onNext(1);
                    }

                    public void cancel() {
                        cancellations.incrementAndGet();
                        throw new IllegalStateException("private-cancel");
                    }
                });
                if ("subscribe".equals(phase)) throw primary;
            };
            Throwable actual = catchThrowable(() -> {
                try (var cursor = new MongoDbReactiveCursor<>(
                        publisher,
                        value -> {
                            if ("decode".equals(phase)) throw primary;
                            return value;
                        },
                        new MongoDbReadBudget(1000, 500))) {
                    cursor.next();
                }
            });
            assertThat(actual).isSameAs(primary).hasNoCause();
            assertCleanup(actual);
            assertThat(cancellations).hasValue(1);
        }
    }

    @Test
    void standaloneAndTimedOutCancellationFailuresAreSanitizedAndAttemptedOnce() {
        AtomicInteger cancellations = new AtomicInteger();
        Publisher<Integer> publisher = target -> target.onSubscribe(new Subscription() {
            public void request(long count) {}

            public void cancel() {
                cancellations.incrementAndGet();
                throw new IllegalStateException("private-cancel");
            }
        });
        var cursor = new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(1000, 500));
        assertThatThrownBy(cursor::close)
                .isInstanceOf(MongoDbReadException.class)
                .hasMessage("CLEANUP_FAILED")
                .hasNoCause();
        cursor.close();
        assertThat(cancellations).hasValue(1);
        try (var timed = new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(100, 10))) {
            Throwable failure = catchThrowable(timed::hasNext);
            assertThat(failure).hasMessage("TIMEOUT").hasNoCause();
            assertCleanup(failure);
        }
        assertThat(cancellations).hasValue(2);
    }

    @Test
    void overflowAndLateSignalsDoNotGrowTheBufferOrDemand() {
        AtomicInteger requests = new AtomicInteger();
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<Subscriber<? super Integer>> receiver = new AtomicReference<>();
        Publisher<Integer> publisher = target -> {
            receiver.set(target);
            target.onSubscribe(new Subscription() {
                public void request(long count) {
                    requests.incrementAndGet();
                    for (int i = 0; i < 1000; i++) target.onNext(i);
                    target.onComplete();
                }

                public void cancel() {
                    cancellations.incrementAndGet();
                }
            });
        };
        var cursor = new MongoDbReactiveCursor<>(publisher, Function.identity(), new MongoDbReadBudget(1000, 500));
        assertThatThrownBy(cursor::next).hasMessage("ITEM_LIMIT");
        receiver.get().onNext(2);
        receiver.get().onError(new IllegalStateException("private"));
        assertThat(cursor.hasNext()).isFalse();
        cursor.close();
        assertThat(requests).hasValue(1);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void rejectedSubscriptionCleanupFailureCannotBeLostBehindABufferedItem() {
        AtomicReference<Subscriber<? super Integer>> receiver = new AtomicReference<>();
        var cursor = new MongoDbReactiveCursor<Integer, Integer>(
                target -> {
                    receiver.set(target);
                    target.onSubscribe(new Subscription() {
                        public void request(long count) {
                            target.onNext(1);
                        }

                        public void cancel() {}
                    });
                },
                Function.identity(),
                new MongoDbReadBudget(1000, 500));
        assertThat(cursor.hasNext()).isTrue();
        receiver.get().onSubscribe(new Subscription() {
            public void request(long count) {}

            public void cancel() {
                throw new IllegalStateException("private");
            }
        });
        assertThat(cursor.next()).isEqualTo(1);
        assertThatThrownBy(cursor::close)
                .isInstanceOf(MongoDbReadException.class)
                .hasMessage("CLEANUP_FAILED")
                .hasNoCause();
        cursor.close();
    }

    private static void assertCleanup(Throwable primary) {
        assertThat(primary.getSuppressed())
                .singleElement()
                .satisfies(cleanup -> assertThat(cleanup)
                        .isInstanceOf(MongoDbReadException.class)
                        .hasMessage("CLEANUP_FAILED")
                        .hasNoCause());
    }
}
