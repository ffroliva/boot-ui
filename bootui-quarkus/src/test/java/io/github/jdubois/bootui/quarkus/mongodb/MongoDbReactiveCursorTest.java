package io.github.jdubois.bootui.quarkus.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.assertj.core.api.Assertions.catchThrowable;

import io.github.jdubois.bootui.engine.mongodb.MongoDbReadBudget;
import io.github.jdubois.bootui.engine.mongodb.MongoDbReadException;
import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.reactivestreams.Publisher;
import org.reactivestreams.Subscriber;
import org.reactivestreams.Subscription;

class MongoDbReactiveCursorTest {
    @Test
    void requestsOneOnlyWhenPulledAndCancelsAtCapWithoutAnExtraSubscription() {
        List<Long> demand = new ArrayList<>();
        AtomicBoolean cancelled = new AtomicBoolean();
        AtomicReference<Subscriber<? super String>> subscriber = new AtomicReference<>();
        Publisher<String> publisher = target -> {
            assertThat(subscriber.compareAndSet(null, target)).isTrue();
            target.onSubscribe(new Subscription() {
                @Override
                public void request(long count) {
                    demand.add(count);
                    target.onNext("item-" + demand.size());
                }

                @Override
                public void cancel() {
                    cancelled.set(true);
                }
            });
        };
        try (var cursor =
                new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(1000, 500), Function.identity())) {
            assertThat(demand).isEmpty();
            assertThat(cursor.hasNext()).isTrue();
            assertThat(cursor.hasNext()).isTrue();
            assertThat(cursor.next()).isEqualTo("item-1");
            assertThat(demand).containsExactly(1L);
        }
        assertThat(cancelled).isTrue();
        subscriber.get().onNext("late-secret");
        subscriber.get().onError(new IllegalStateException("late-secret"));
        assertThat(demand).containsExactly(1L);
    }

    @Test
    void timedWaitCancelsAndNeverRetainsDriverFailureMessages() {
        AtomicBoolean cancelled = new AtomicBoolean();
        Publisher<String> publisher = target -> target.onSubscribe(new Subscription() {
            @Override
            public void request(long count) {}

            @Override
            public void cancel() {
                cancelled.set(true);
            }
        });
        try (var cursor = new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(100, 10), Function.identity())) {
            assertThatThrownBy(cursor::hasNext)
                    .isInstanceOf(MongoDbReadException.class)
                    .hasMessage("TIMEOUT")
                    .hasNoCause();
        }
        assertThat(cancelled).isTrue();
    }

    @Test
    void preservesLastItemBeforeCompletionAndCancelsOnInterrupt() {
        Publisher<String> publisher = target -> target.onSubscribe(new Subscription() {
            @Override
            public void request(long count) {
                target.onNext("last");
                target.onComplete();
            }

            @Override
            public void cancel() {}
        });
        try (var cursor =
                new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(1000, 500), Function.identity())) {
            assertThat(cursor.next()).isEqualTo("last");
            assertThat(cursor.hasNext()).isFalse();
        }
        try (var cursor =
                new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(1000, 500), Function.identity())) {
            Thread.currentThread().interrupt();
            assertThatThrownBy(cursor::hasNext).hasMessage("CANCELLED");
        } finally {
            Thread.interrupted();
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
        var cursor = new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(10000, 5000), value -> {
            mapping.countDown();
            await(release);
            return value;
        });
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
                new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(10000, 5000), Function.identity())) {
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
                try (var cursor = new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(1000, 500), value -> {
                    if ("decode".equals(phase)) throw primary;
                    return value;
                })) {
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
        var cursor = new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(1000, 500), Function.identity());
        assertThatThrownBy(cursor::close)
                .isInstanceOf(MongoDbReadException.class)
                .hasMessage("CLEANUP_FAILED")
                .hasNoCause();
        cursor.close();
        assertThat(cancellations).hasValue(1);
        try (var timed = new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(100, 10), Function.identity())) {
            Throwable failure = catchThrowable(timed::hasNext);
            assertThat(failure).hasMessage("TIMEOUT").hasNoCause();
            assertCleanup(failure);
        }
        assertThat(cancellations).hasValue(2);
    }

    @Test
    void unsolicitedOverflowAndLateCallbacksCannotGrowTheBufferOrDemand() {
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
                    target.onError(new IllegalStateException("private"));
                }

                public void cancel() {
                    cancellations.incrementAndGet();
                }
            });
        };
        var cursor = new MongoDbReactiveCursor<>(publisher, new MongoDbReadBudget(1000, 500), Function.identity());
        assertThatThrownBy(cursor::next).hasMessage("ITEM_LIMIT").hasNoCause();
        for (int i = 0; i < 1000; i++) receiver.get().onNext(i);
        receiver.get().onError(new IllegalStateException("private"));
        assertThat(cursor.hasNext()).isFalse();
        cursor.close();
        assertThat(requests).hasValue(1);
        assertThat(cancellations).hasValue(1);
    }

    @Test
    void duplicateAndLateSubscriptionsAreCancelledWithoutReplacingTheOwnedSubscription() {
        AtomicInteger cancellations = new AtomicInteger();
        AtomicReference<Subscriber<? super Integer>> receiver = new AtomicReference<>();
        Subscription subscription = new Subscription() {
            public void request(long count) {}

            public void cancel() {
                cancellations.incrementAndGet();
            }
        };
        var cursor = new MongoDbReactiveCursor<Integer, Integer>(
                target -> {
                    receiver.set(target);
                    target.onSubscribe(subscription);
                    target.onSubscribe(subscription);
                },
                new MongoDbReadBudget(1000, 500),
                Function.identity());
        assertThat(cancellations).hasValue(1);
        cursor.close();
        receiver.get().onSubscribe(subscription);
        assertThat(cancellations).hasValue(3);
        receiver.get().onSubscribe(new Subscription() {
            public void request(long count) {}

            public void cancel() {}
        });
        assertThatThrownBy(() -> receiver.get().onSubscribe(new Subscription() {
                    public void request(long count) {}

                    public void cancel() {
                        throw new IllegalStateException("private");
                    }
                }))
                .isInstanceOf(MongoDbReadException.class)
                .hasMessage("CLEANUP_FAILED")
                .hasNoCause();
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
                new MongoDbReadBudget(1000, 500),
                Function.identity());
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

    private static void await(CountDownLatch latch) {
        try {
            assertThat(latch.await(5, TimeUnit.SECONDS)).isTrue();
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt();
            throw new AssertionError(error);
        }
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
