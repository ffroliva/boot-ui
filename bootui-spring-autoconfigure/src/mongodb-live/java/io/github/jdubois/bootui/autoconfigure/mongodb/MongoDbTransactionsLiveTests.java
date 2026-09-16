package io.github.jdubois.bootui.autoconfigure.mongodb;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.mongodb.ConnectionString;
import com.mongodb.MongoClientSettings;
import com.mongodb.MongoCredential;
import com.mongodb.TransactionOptions;
import com.mongodb.WriteConcern;
import com.mongodb.client.MongoClient;
import com.mongodb.client.MongoClients;
import io.github.jdubois.bootui.autoconfigure.transactions.BootUiTransactionExecutionListener;
import io.github.jdubois.bootui.autoconfigure.transactions.BootUiTransactionManagerListenerRegistrar;
import io.github.jdubois.bootui.core.dto.TransactionEntryDto;
import io.github.jdubois.bootui.engine.transactions.TransactionRecorder;
import java.nio.charset.StandardCharsets;
import java.sql.Connection;
import java.time.Duration;
import java.util.IdentityHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.bson.Document;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;
import org.springframework.beans.factory.support.DefaultListableBeanFactory;
import org.springframework.data.mongodb.MongoTransactionManager;
import org.springframework.data.mongodb.ReactiveMongoTransactionManager;
import org.springframework.data.mongodb.core.MongoTemplate;
import org.springframework.data.mongodb.core.ReactiveMongoTemplate;
import org.springframework.data.mongodb.core.SimpleMongoClientDatabaseFactory;
import org.springframework.data.mongodb.core.SimpleReactiveMongoDatabaseFactory;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DataSourceTransactionManager;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.transaction.ConfigurableTransactionManager;
import org.springframework.transaction.TransactionExecution;
import org.springframework.transaction.TransactionExecutionListener;
import org.springframework.transaction.TransactionSystemException;
import org.springframework.transaction.reactive.TransactionalOperator;
import org.springframework.transaction.support.DefaultTransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.testcontainers.containers.GenericContainer;
import org.testcontainers.containers.wait.strategy.Wait;
import org.testcontainers.images.builder.Transferable;
import org.testcontainers.utility.DockerImageName;
import reactor.core.publisher.Flux;
import reactor.core.publisher.Mono;
import reactor.core.scheduler.Schedulers;

/** Opt-in mongodb-live lane: all clients, credentials, writes and the replica set belong to this test. */
class MongoDbTransactionsLiveTests {
    private static final String DATABASE = "bootui_transactions";
    private static final String COLLECTION = "orders";
    private static final Duration TIMEOUT = Duration.ofSeconds(30);
    private static GenericContainer<?> container;
    private static MongoClient admin;
    private static MongoClient sync;
    private static com.mongodb.reactivestreams.client.MongoClient reactive;
    private static String password;

    @BeforeAll
    static void startReplicaSet() throws Exception {
        password = UUID.randomUUID().toString();
        String key = UUID.randomUUID().toString().replace("-", "").repeat(4);
        container = new GenericContainer<>(DockerImageName.parse("mongo:8.0.19"))
                .withEnv("MONGO_INITDB_ROOT_USERNAME", "fixtureAdmin")
                .withEnv("MONGO_INITDB_ROOT_PASSWORD", password)
                .withCopyToContainer(
                        Transferable.of(key.getBytes(StandardCharsets.US_ASCII), 0400), "/data/configdb/keyfile")
                .withExposedPorts(27017)
                .withCommand(
                        "mongod",
                        "--bind_ip_all",
                        "--auth",
                        "--replSet",
                        "bootui-rs",
                        "--keyFile",
                        "/data/configdb/keyfile",
                        "--setParameter",
                        "enableTestCommands=1")
                .waitingFor(Wait.forLogMessage("(?s).*MongoDB init process complete; ready for start up.*", 1)
                        .withStartupTimeout(Duration.ofMinutes(2)));
        try {
            container.start();
            admin = MongoClients.create(settings("fixtureAdmin", "admin"));
            admin.getDatabase("admin")
                    .runCommand(new Document(
                            "replSetInitiate",
                            new Document("_id", "bootui-rs")
                                    .append(
                                            "members",
                                            List.of(new Document("_id", 0).append("host", "localhost:27017")))));
            long deadline = System.nanoTime() + TIMEOUT.toNanos();
            while (!Boolean.TRUE.equals(admin.getDatabase("admin")
                    .runCommand(new Document("hello", 1))
                    .getBoolean("isWritablePrimary"))) {
                if (System.nanoTime() >= deadline) {
                    throw new IllegalStateException("Owned MongoDB replica set did not elect a primary");
                }
                Thread.sleep(100);
            }
            var database = admin.getDatabase(DATABASE);
            database.createCollection(COLLECTION);
            database.runCommand(new Document("createUser", "application")
                    .append("pwd", password)
                    .append("roles", List.of(new Document("role", "readWrite").append("db", DATABASE))));
            sync = MongoClients.create(settings("application", DATABASE));
            reactive = com.mongodb.reactivestreams.client.MongoClients.create(settings("application", DATABASE));
            sync.getDatabase(DATABASE).runCommand(new Document("ping", 1));
            Mono.from(reactive.getDatabase(DATABASE).runCommand(new Document("ping", 1)))
                    .block(TIMEOUT);
        } catch (Exception | Error failure) {
            stopReplicaSet();
            throw failure;
        }
    }

    @AfterAll
    static void stopReplicaSet() {
        if (reactive != null) {
            reactive.close();
            reactive = null;
        }
        if (sync != null) {
            sync.close();
            sync = null;
        }
        if (admin != null) {
            admin.close();
            admin = null;
        }
        if (container != null) {
            container.close();
            container = null;
        }
    }

    @Test
    void imperativeCommitAndRollbackAreRealAndDoNotBorrowCoPresentJdbcEvidence() {
        var factory = new SimpleMongoClientDatabaseFactory(sync, DATABASE);
        var mongo = new MongoTransactionManager(factory);
        var dataSource = new DriverManagerDataSource("jdbc:h2:mem:tx-" + UUID.randomUUID(), "sa", "");
        var jdbc = new DataSourceTransactionManager(dataSource);
        var recorder = recorder();
        register(recorder, mongo, jdbc);
        var template = new MongoTemplate(factory);
        var outer = new TransactionTemplate(jdbc);
        outer.setName("jdbc");
        outer.setIsolationLevel(Connection.TRANSACTION_SERIALIZABLE);
        String committed = "sync-commit-" + UUID.randomUUID();
        String rolledBack = "sync-rollback-" + UUID.randomUUID();
        try {
            MDC.put("traceId", "jdbc-only");
            outer.executeWithoutResult(status -> {
                assertThat(new JdbcTemplate(dataSource).queryForObject("select 1", Integer.class))
                        .isEqualTo(1);
                assertThat(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel())
                        .isEqualTo(Connection.TRANSACTION_SERIALIZABLE);
                TransactionTemplate transaction = new TransactionTemplate(mongo);
                transaction.setName("mongo-commit");
                transaction.executeWithoutResult(inner -> template.insert(new Document("_id", committed), COLLECTION));
                transaction.setName("mongo-rollback");
                transaction.executeWithoutResult(inner -> {
                    template.insert(new Document("_id", rolledBack), COLLECTION);
                    inner.setRollbackOnly();
                });
            });
        } finally {
            MDC.clear();
        }
        assertThat(sync.getDatabase(DATABASE).getCollection(COLLECTION).countDocuments(new Document("_id", committed)))
                .isEqualTo(1);
        assertThat(sync.getDatabase(DATABASE).getCollection(COLLECTION).countDocuments(new Document("_id", rolledBack)))
                .isZero();
        assertThat(recorder.recent()).hasSize(3);
        assertThat(entry(recorder, "mongo-commit").status()).isEqualTo("COMMITTED");
        assertThat(entry(recorder, "mongo-rollback").status()).isEqualTo("ROLLED_BACK");
        assertMongo(entry(recorder, "mongo-commit"), "IMPERATIVE");
        assertMongo(entry(recorder, "mongo-rollback"), "IMPERATIVE");
        assertThat(entry(recorder, "jdbc").isolation()).isEqualTo("SERIALIZABLE");
        assertThat(entry(recorder, "jdbc").traceId()).isEqualTo("jdbc-only");
    }

    @Test
    void concurrentReactiveTransactionsCommitAndRollbackAfterRealThreadHops() {
        var factory = new SimpleReactiveMongoDatabaseFactory(reactive, DATABASE);
        var manager = new ReactiveMongoTransactionManager(factory);
        var callbacks = new CallbackThreads();
        manager.addListener(callbacks);
        var recorder = recorder();
        register(recorder, manager);
        var template = new ReactiveMongoTemplate(factory);
        var hop = Schedulers.newSingle("owned-mongo-transaction-hop");
        var subscription = Schedulers.newSingle("owned-mongo-transaction-begin");
        String prefix = "reactive-" + UUID.randomUUID() + "-";
        try {
            Flux.range(0, 8)
                    .flatMap(
                            index -> {
                                var definition = new DefaultTransactionDefinition();
                                definition.setName(prefix + index);
                                var operator = TransactionalOperator.create(manager, definition);
                                return operator.execute(status -> Mono.just(index)
                                                .publishOn(hop)
                                                .flatMap(value -> template.insert(
                                                        new Document("_id", prefix + value), COLLECTION))
                                                .doOnNext(ignored -> {
                                                    if (index % 2 != 0) {
                                                        status.setRollbackOnly();
                                                    }
                                                }))
                                        .then()
                                        .subscribeOn(subscription);
                            },
                            4)
                    .blockLast(TIMEOUT);
        } finally {
            hop.dispose();
            subscription.dispose();
        }
        assertThat(recorder.recent()).hasSize(8);
        for (int index = 0; index < 8; index++) {
            TransactionEntryDto entry = entry(recorder, prefix + index);
            assertThat(entry.status()).isEqualTo(index % 2 == 0 ? "COMMITTED" : "ROLLED_BACK");
            assertMongo(entry, "REACTIVE");
            assertThat(sync.getDatabase(DATABASE)
                            .getCollection(COLLECTION)
                            .countDocuments(new Document("_id", prefix + index)))
                    .isEqualTo(index % 2 == 0 ? 1 : 0);
        }
        assertThat(callbacks.threadHop).isTrue();
        assertThat(callbacks.pending).isEmpty();
        assertThat(manager.getTransactionExecutionListeners())
                .contains(callbacks)
                .hasSize(2);
    }

    @Test
    void bothManagersReportRealBeginFailureWithoutRunningTheTransactionBody() {
        TransactionOptions invalid = TransactionOptions.builder()
                .writeConcern(WriteConcern.UNACKNOWLEDGED)
                .build();
        var imperative = new MongoTransactionManager(new SimpleMongoClientDatabaseFactory(sync, DATABASE), invalid);
        var reactiveManager = new ReactiveMongoTransactionManager(
                new SimpleReactiveMongoDatabaseFactory(reactive, DATABASE), invalid);
        var recorder = recorder();
        register(recorder, imperative, reactiveManager);
        var bodyRan = new AtomicBoolean();
        var transaction = new TransactionTemplate(imperative);
        transaction.setName("imperative-begin-failure");
        assertThatThrownBy(() -> transaction.executeWithoutResult(status -> bodyRan.set(true)))
                .isInstanceOf(TransactionSystemException.class);
        var definition = new DefaultTransactionDefinition();
        definition.setName("reactive-begin-failure");
        assertThatThrownBy(() -> TransactionalOperator.create(reactiveManager, definition)
                        .execute(status -> {
                            bodyRan.set(true);
                            return Mono.just(1);
                        })
                        .then()
                        .block(TIMEOUT))
                .isInstanceOf(TransactionSystemException.class);
        assertThat(bodyRan).isFalse();
        assertThat(recorder.recent()).hasSize(2).allSatisfy(entry -> {
            assertThat(entry.status()).isEqualTo("UNKNOWN");
            assertThat(entry.propagation()).isEqualTo("UNKNOWN");
            assertThat(entry.errorMessage()).isEqualTo("Transaction begin failed; the outcome is unknown.");
            assertThat(entry.correlationStatus()).isEqualTo("NOT_APPLICABLE");
        });
    }

    @Test
    void driverSuppressedAbortFailureDoesNotBecomeIndependentServerEvidence() {
        var factory = new SimpleMongoClientDatabaseFactory(sync, DATABASE);
        var manager = new MongoTransactionManager(factory);
        var recorder = recorder();
        register(recorder, manager);
        var transaction = new TransactionTemplate(manager);
        transaction.setName("failed-rollback");
        try {
            admin.getDatabase("admin")
                    .runCommand(new Document("configureFailPoint", "failCommand")
                            .append("mode", new Document("times", 1))
                            .append(
                                    "data",
                                    new Document("failCommands", List.of("abortTransaction"))
                                            .append("errorCode", 123)));
            transaction.executeWithoutResult(status -> {
                new MongoTemplate(factory)
                        .insert(new Document("_id", "rollback-failure-" + UUID.randomUUID()), COLLECTION);
                status.setRollbackOnly();
            });
        } finally {
            admin.getDatabase("admin")
                    .runCommand(new Document("configureFailPoint", "failCommand").append("mode", "off"));
        }
        assertThat(entry(recorder, "failed-rollback").status()).isEqualTo("ROLLED_BACK");
        assertThat(entry(recorder, "failed-rollback").errorMessage()).isNull();
        assertThat(entry(recorder, "failed-rollback").limitations())
                .contains("Outcomes describe manager callbacks, not independent server verification.");
    }

    private static MongoClientSettings settings(String user, String authenticationDatabase) {
        return MongoClientSettings.builder()
                .applyConnectionString(new ConnectionString("mongodb://" + container.getHost() + ":"
                        + container.getMappedPort(27017) + "/?directConnection=true"))
                .credential(MongoCredential.createCredential(user, authenticationDatabase, password.toCharArray()))
                .timeout(10, TimeUnit.SECONDS)
                .build();
    }

    private static TransactionRecorder recorder() {
        return new TransactionRecorder(true, true, 32, 1, 1, null);
    }

    private static void register(TransactionRecorder recorder, ConfigurableTransactionManager... managers) {
        var beans = new DefaultListableBeanFactory();
        beans.registerSingleton("listener", new BootUiTransactionExecutionListener(recorder));
        for (int index = 0; index < managers.length; index++) {
            beans.registerSingleton("manager" + index, managers[index]);
        }
        var registrar = new BootUiTransactionManagerListenerRegistrar(
                beans.getBeanProvider(BootUiTransactionExecutionListener.class));
        registrar.setBeanFactory(beans);
        registrar.afterSingletonsInstantiated();
        registrar.afterSingletonsInstantiated();
    }

    private static TransactionEntryDto entry(TransactionRecorder recorder, String name) {
        return recorder.recent().stream()
                .filter(entry -> entry.methodName().equals(name))
                .findFirst()
                .orElseThrow();
    }

    private static void assertMongo(TransactionEntryDto entry, String executionKind) {
        assertThat(entry.managerType())
                .isEqualTo(
                        executionKind.equals("REACTIVE")
                                ? ReactiveMongoTransactionManager.class.getName()
                                : MongoTransactionManager.class.getName());
        assertThat(entry.executionKind()).isEqualTo(executionKind);
        assertThat(entry.correlationStatus()).isEqualTo("NOT_APPLICABLE");
        assertThat(entry.parentId()).isNull();
        assertThat(entry.traceId()).isNull();
        assertThat(entry.isolation()).isEqualTo("UNKNOWN");
        assertThat(entry.sqlStatementCount()).isZero();
        assertThat(entry.connectionCount()).isZero();
        assertThat(entry.connectionHeld()).isFalse();
        assertThat(entry.limitations()).isNotEmpty();
    }

    private static final class CallbackThreads implements TransactionExecutionListener {
        private final Map<TransactionExecution, Long> pending = new IdentityHashMap<>();
        private boolean threadHop;

        @Override
        public synchronized void afterBegin(TransactionExecution execution, Throwable failure) {
            pending.put(execution, Thread.currentThread().getId());
        }

        @Override
        public synchronized void afterCommit(TransactionExecution execution, Throwable failure) {
            completed(execution);
        }

        @Override
        public synchronized void afterRollback(TransactionExecution execution, Throwable failure) {
            completed(execution);
        }

        private void completed(TransactionExecution execution) {
            Long begin = pending.remove(execution);
            assertThat(begin).isNotNull();
            threadHop |= begin != Thread.currentThread().getId();
        }
    }
}
