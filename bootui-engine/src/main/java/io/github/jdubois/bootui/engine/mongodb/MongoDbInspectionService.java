package io.github.jdubois.bootui.engine.mongodb;

import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.action.ActionOperations;
import io.github.jdubois.bootui.engine.action.SingleFlightAction;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import io.github.jdubois.bootui.spi.MongoDbClientAccess;
import io.github.jdubois.bootui.spi.MongoDbCursor;
import io.github.jdubois.bootui.spi.MongoDbProvider;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.util.*;

/**
 * Single-flight, explicit catalog inspection. Owns one bounded sanitized snapshot; passive reads
 * only inspect existing local declarations and page retained evidence, never a Mongo cursor.
 */
public final class MongoDbInspectionService {
    private static final String DISCLAIMER =
            "Local driver topology is not a health/authentication check. Explicit metadata"
                    + " reads use existing clients, operation timeouts and a cooperative total budget. Retained caps do not bound"
                    + " initial server response decoding or cleanup. Catalog observations are not atomic, exhaustive topology,"
                    + " document data, statistics or index-performance advice.";
    private final MongoDbProvider provider;
    private final ExposurePolicy exposure;
    private final Clock clock;
    private final MongoDbSettings settings;
    private final SingleFlightAction admission = new SingleFlightAction();
    private MongoDbValues.Policy policy;
    private long generation;
    private final String instanceSalt = UUID.randomUUID().toString();
    private Map<String, MongoDbProvider.Client> clients = Map.of();
    private Map<String, ClientState> clientStates = Map.of();
    private final Map<String, Target> declaredTargets = new LinkedHashMap<>();
    private MongoDbInventoryDto inventory;
    private Snapshot cached;
    private String message = "No inspection has run. Inspect explicitly to contact the selected existing client.";

    public static MongoDbInspectionService using(
            MongoDbProvider provider, ExposurePolicy exposure, Clock clock, MongoDbSettings settings) {
        return new MongoDbInspectionService(provider, exposure, clock, settings);
    }

    private MongoDbInspectionService(
            MongoDbProvider provider, ExposurePolicy exposure, Clock clock, MongoDbSettings settings) {
        this.provider = provider;
        this.exposure = exposure;
        this.clock = Objects.requireNonNull(clock);
        this.settings = Objects.requireNonNull(settings);
        this.policy = MongoDbValues.Policy.of(exposure);
        inventory = new MongoDbInventoryDto(clock.millis(), List.of(), true, false, List.of());
    }

    public MongoDbReport report() {
        return report(null, null, null, null, null, null, null);
    }

    public synchronized MongoDbReport report(
            String snapshotId,
            String section,
            String databaseId,
            String collectionId,
            String query,
            Integer offset,
            Integer limit) {
        refresh();
        return project(snapshotId, section, databaseId, collectionId, query, offset, limit);
    }

    public MongoDbReport inspect(MongoDbInspectRequest raw) {
        MongoDbInspectRequest request = MongoDbRequests.validate(raw);
        final MongoDbProvider.Client client;
        final List<Target> selected;
        final long startedGeneration;
        final MongoDbValues.Policy startedPolicy;
        final boolean configuredScopeOmitted;
        synchronized (this) {
            refresh();
            if (!settings.inspectEnabled()) throw new MongoDbRequestException(403, "MongoDB inspection is disabled");
            client = clients.get(request.clientId());
            if (client == null) throw new MongoDbRequestException(404, "MongoDB client not found");
            if (client.access() == null || !"INITIALIZED".equals(client.lifecycle())) {
                throw new MongoDbRequestException(409, "MongoDB client is not initialized or is inactive");
            }
            if ("AUTHORIZED_NAMES".equals(request.scope()) && !settings.authorizedDatabaseEnumerationEnabled()) {
                throw new MongoDbRequestException(403, "Authorized database enumeration is disabled");
            }
            if ("SELECTED".equals(request.scope())) {
                Target database = target(request.databaseId(), request.snapshotId());
                if (!database.clientId.equals(request.clientId()) || database.collection != null) {
                    throw MongoDbRequests.invalid("Database does not belong to the selected client");
                }
                if (request.collectionId() != null) {
                    Target collection = target(request.collectionId(), request.snapshotId());
                    if (!collection.clientId.equals(database.clientId)
                            || !collection.database.equals(database.database)
                            || collection.collection == null) {
                        throw MongoDbRequests.invalid("Collection does not belong to the selected database");
                    }
                    selected = List.of(collection);
                } else selected = List.of(database);
            } else {
                selected = declaredTargets.values().stream()
                        .filter(target -> target.clientId.equals(request.clientId()))
                        .limit(settings.maxDatabases())
                        .toList();
            }
            if ("CONFIGURED".equals(request.scope()) && selected.isEmpty()) {
                throw new MongoDbRequestException(
                        409, "No configured database scope; configure this client's databases");
            }
            startedGeneration = generation;
            startedPolicy = policy;
            configuredScopeOmitted = "CONFIGURED".equals(request.scope())
                    && selected.size() < client.databases().size();
        }
        return admission.run(ActionOperations.MONGODB_INSPECT, () -> {
            Snapshot result = collect(request, client.access(), selected, startedPolicy, configuredScopeOmitted);
            synchronized (this) {
                refresh();
                if (startedGeneration != generation
                        || !startedPolicy.equals(policy)
                        || clients.get(request.clientId()) == null
                        || clients.get(request.clientId()).identity() != client.identity()
                        || !clients.get(request.clientId()).databases().equals(client.databases())) {
                    cached = null;
                    message = "Exposure policy or client changed during inspection; result discarded without retrying.";
                } else {
                    cached = result;
                    message = null;
                    rebudgetCached();
                }
                return project(null, null, null, null, null, null, null);
            }
        });
    }

    private Target target(String id, String snapshotId) {
        Target target = declaredTargets.get(id);
        if (target != null && snapshotId == null) return target;
        if (snapshotId == null) {
            if (cached != null && cached.targets.containsKey(id)) {
                throw MongoDbRequests.invalid("snapshotId is required for an observed MongoDB target");
            }
            throw new MongoDbRequestException(404, "MongoDB target not found");
        }
        requireSnapshot(snapshotId);
        target = cached.targets.get(id);
        if (target == null) throw new MongoDbRequestException(404, "MongoDB target not found");
        return target;
    }

    private void requireSnapshot(String id) {
        if (id == null || cached == null || !id.equals(cached.inspection.snapshotId())) {
            throw new MongoDbRequestException(
                    409, "MongoDB snapshot changed; reload retained metadata before selecting");
        }
    }

    private void refresh() {
        MongoDbValues.Policy now = MongoDbValues.Policy.of(exposure);
        if (!now.equals(policy)) {
            generation++;
            cached = null;
            clientStates = Map.of();
            policy = now;
            message = "Exposure policy changed; retained MongoDB metadata was invalidated without database I/O.";
        }
        MongoDbProvider.Discovery discovery;
        try {
            discovery = provider == null
                    ? new MongoDbProvider.Discovery(List.of(), List.of(), false)
                    : provider.discover(settings.maxClients());
            if (discovery == null) throw new IllegalStateException();
        } catch (RuntimeException ex) {
            discovery = new MongoDbProvider.Discovery(
                    List.of(), List.of("Client discovery failed; details withheld."), false);
        }
        MongoDbValues values = new MongoDbValues(policy, settings.maxTextLength());
        Map<String, MongoDbProvider.Client> observed = new LinkedHashMap<>();
        Map<String, ClientState> states = new LinkedHashMap<>();
        List<MongoDbClientDto> rows = new ArrayList<>();
        declaredTargets.clear();
        MongoDbRetentionBudget budget = new MongoDbRetentionBudget(settings);
        boolean truncated = discovery.truncated() || discovery.clients().size() > settings.maxClients();
        Set<Object> identities = Collections.newSetFromMap(new IdentityHashMap<>());
        for (MongoDbProvider.Client client :
                discovery.clients().stream().limit(settings.maxClients()).toList()) {
            if (client.identity() != null && !identities.add(client.identity())) continue;
            ClientState previousState = clientStates.get(client.key());
            String clientId = previousState != null && previousState.matches(client)
                    ? previousState.id
                    : UUID.randomUUID().toString();
            MongoDbValues clientValues = new MongoDbValues(policy, settings.maxTextLength());
            List<MongoDbDatabaseDto> databases = new ArrayList<>();
            Map<String, Target> targets = new LinkedHashMap<>();
            List<String> clientLimitations = new ArrayList<>(clientValues.limitations(client.limitations()));
            if (client.databases().size() > settings.maxDatabases()) {
                truncated = true;
                clientLimitations.add(
                        "Configured database scope exceeds the database limit; additional targets were omitted.");
            }
            for (String name :
                    client.databases().stream().limit(settings.maxDatabases()).toList()) {
                if (!MongoDbValues.selectable(name, settings.maxTextLength())) {
                    truncated = true;
                    continue;
                }
                String databaseId = id(clientId, name);
                databases.add(database(databaseId, clientId, name, "CONFIGURED", clientValues));
                targets.put(databaseId, new Target(clientId, name, null));
            }
            MongoDbClientDto row = new MongoDbClientDto(
                    clientId,
                    clientValues.exposed("mongodb.client", client.name()),
                    MongoDbValues.symbol(client.driverStyle(), "SYNC", "REACTIVE"),
                    clientValues.text(client.driverVersion()),
                    MongoDbValues.symbol(
                            client.lifecycle(), "INITIALIZED", "NOT_INITIALIZED", "INACTIVE", "UNRESOLVED"),
                    client.access() != null && "INITIALIZED".equals(client.lifecycle()),
                    databases,
                    clientValues.settings(client.settings()),
                    clientValues.topology(client.topology()),
                    clientLimitations);
            if (clientValues.truncated()) {
                truncated = true;
                clientValues.omissions().stream()
                        .map(MongoDbMessages::limitation)
                        .filter(limitation -> !clientLimitations.contains(limitation))
                        .forEach(clientLimitations::add);
                row = new MongoDbClientDto(
                        row.id(),
                        row.name(),
                        row.driverStyle(),
                        row.driverVersion(),
                        row.lifecycle(),
                        row.inspectable(),
                        row.configuredDatabases(),
                        row.settings(),
                        row.topology(),
                        clientLimitations);
            }
            if (!budget.retain(List.of(row, targetNames(targets)))) {
                truncated = true;
                break;
            }
            observed.put(clientId, client);
            states.put(
                    client.key(),
                    new ClientState(
                            clientId, client.identity(), client.databases(), client.driverStyle(), client.lifecycle()));
            rows.add(row);
            declaredTargets.putAll(targets);
        }
        List<String> limitations = new ArrayList<>(values.limitations(discovery.limitations()));
        truncated |= values.truncated();
        if (truncated) limitations.add("Local declaration inventory reached a retained bound.");
        while (true) {
            inventory = new MongoDbInventoryDto(
                    clock.millis(), rows, limitations.isEmpty() && !truncated, truncated, limitations);
            if (new MongoDbRetentionBudget(settings).retain(List.of(inventory, targetNames(declaredTargets)))) break;
            truncated = true;
            if (!rows.isEmpty()) {
                MongoDbClientDto removed = rows.remove(rows.size() - 1);
                MongoDbProvider.Client declaration = observed.remove(removed.id());
                states.remove(declaration.key());
                declaredTargets.values().removeIf(target -> target.clientId.equals(removed.id()));
            } else if (!limitations.isEmpty()) {
                limitations.remove(limitations.size() - 1);
            } else {
                throw new IllegalStateException("MongoDB inventory envelope exceeds its configured budget");
            }
        }
        if (cached != null) {
            MongoDbProvider.Client previous = clients.get(cached.inspection.clientId());
            MongoDbProvider.Client current = observed.get(cached.inspection.clientId());
            if (previous == null
                    || current == null
                    || previous.identity() != current.identity()
                    || !previous.databases().equals(current.databases())) {
                cached = null;
                message = "MongoDB client or configured scope changed; retained metadata was invalidated.";
            }
        }
        clients = observed;
        clientStates = states;
        rebudgetCached();
    }

    private static List<String> targetNames(Map<String, Target> targets) {
        return targets.values().stream()
                .map(target -> target.collection == null ? target.database : target.collection)
                .toList();
    }

    /** Current inventory and retained external evidence share one budget, including routing identities. */
    private void rebudgetCached() {
        if (cached == null) return;
        MongoDbRetentionBudget budget = new MongoDbRetentionBudget(settings);
        boolean fits = budget.retain(List.of(
                inventory,
                targetNames(declaredTargets),
                cached.databases,
                cached.collections,
                cached.indexes,
                cached.diagnostics,
                cached.inspection.serverInformation(),
                cached.inspection.capabilities(),
                targetNames(cached.targets)));
        if (!fits) {
            cached = null;
            message = "Local inventory changed and the combined metadata no longer fits the retained limit;"
                    + " the snapshot was invalidated without contacting MongoDB.";
            return;
        }
        MongoDbInspectionDto old = cached.inspection;
        MongoDbInspectionDto accounted = new MongoDbInspectionDto(
                old.snapshotId(),
                old.clientId(),
                old.scope(),
                old.status(),
                old.startedAt(),
                old.completedAt(),
                old.databaseId(),
                old.collectionId(),
                old.serverInformation(),
                old.capabilities(),
                old.databasesRetained(),
                old.collectionsRetained(),
                old.indexesRetained(),
                budget.items(),
                budget.bytes(),
                old.truncated(),
                old.limitations());
        cached = new Snapshot(
                accounted, cached.databases, cached.collections, cached.indexes, cached.diagnostics, cached.targets);
    }

    private Snapshot collect(
            MongoDbInspectRequest request,
            MongoDbClientAccess access,
            List<Target> selected,
            MongoDbValues.Policy startedPolicy,
            boolean configuredScopeOmitted) {
        MongoDbValues values = new MongoDbValues(startedPolicy, settings.maxTextLength());
        MongoDbReadBudget deadline = new MongoDbReadBudget(settings.timeoutMillis(), settings.operationTimeoutMillis());
        Work work = new Work(values);
        if (configuredScopeOmitted) {
            work.limit("ITEM_LIMIT");
            work.limitations.add("Additional configured database targets were omitted; this inspection covers only"
                    + " the retained configured scope.");
        }
        long started = clock.millis();
        try {
            List<MongoDbSettingDto> info = values.settings(
                    access.serverInformation(selected.isEmpty() ? null : selected.get(0).database, deadline));
            if (work.retain(info)) {
                work.server.addAll(info);
                if (!info.isEmpty()) work.usableExternal = true;
            }
            work.addCapability(capability("server", "AVAILABLE", null));
        } catch (RuntimeException ex) {
            work.failure(request.clientId(), "server", ex);
        }
        if ("AUTHORIZED_NAMES".equals(request.scope())) {
            work.limitations.add("Authorized names only; listDatabases has one unpaged server response."
                    + " Retained caps do not bound its initial decoding. Select a database for a separate inspection.");
            try (MongoDbCursor<String> cursor = access.databaseNames(deadline)) {
                int count = 0;
                while (deadline.remainingMillis() > 0 && cursor.hasNext()) {
                    if (count++ >= settings.maxDatabases()) {
                        work.limit("ITEM_LIMIT");
                        break;
                    }
                    String name = cursor.next();
                    if (!MongoDbValues.selectable(name, settings.maxTextLength())) {
                        work.limit("ITEM_LIMIT");
                        continue;
                    }
                    String databaseId = id(request.clientId(), name);
                    MongoDbDatabaseDto row =
                            database(databaseId, request.clientId(), name, "AUTHORIZED_VISIBLE", values);
                    if (!work.retain(List.of(row, name))) break;
                    work.databases.add(row);
                    work.usableExternal = true;
                    work.targets.put(databaseId, new Target(request.clientId(), name, null));
                }
                work.usableExternal = true;
                work.addCapability(capability("databases", "AVAILABLE", null));
            } catch (RuntimeException ex) {
                work.failure(request.clientId(), "databases", ex);
            }
        } else {
            for (Target target : selected) {
                if (work.retention.exhausted() != null) break;
                String databaseId = id(target.clientId, target.database);
                MongoDbDatabaseDto database =
                        database(databaseId, target.clientId, target.database, "SELECTED_SCOPE", values);
                if (!work.retain(List.of(database, target.database))) break;
                work.databases.add(database);
                work.targets.put(databaseId, new Target(target.clientId, target.database, null));
                try {
                    deadline.remainingMillis();
                    if (target.collection != null) {
                        inspectCollection(access, target, databaseId, work, deadline, false);
                    } else {
                        try (MongoDbCursor<String> names = access.collectionNames(target.database, deadline)) {
                            int count = 0;
                            while (deadline.remainingMillis() > 0 && names.hasNext()) {
                                if (count++ >= settings.maxCollectionsPerDatabase()) {
                                    work.limit("ITEM_LIMIT");
                                    break;
                                }
                                String name = names.next();
                                if (name != null && name.startsWith("system.")) continue;
                                if (!MongoDbValues.selectable(name, settings.maxTextLength())) {
                                    work.limit("ITEM_LIMIT");
                                    continue;
                                }
                                inspectCollection(
                                        access,
                                        new Target(target.clientId, target.database, name),
                                        databaseId,
                                        work,
                                        deadline,
                                        true);
                                if (work.retention.exhausted() != null) break;
                            }
                            work.usableExternal = true;
                        }
                    }
                } catch (RuntimeException ex) {
                    work.failure(databaseId, "collections", ex);
                }
            }
        }
        values.omissions().forEach(work::limit);
        boolean partial = work.failed || !work.diagnostics.isEmpty() || work.truncated;
        String status = !work.usableExternal ? "ERROR" : partial ? "PARTIAL" : "READ";
        MongoDbInspectionDto inspection = new MongoDbInspectionDto(
                UUID.randomUUID().toString(),
                request.clientId(),
                request.scope(),
                status,
                started,
                clock.millis(),
                request.databaseId(),
                request.collectionId(),
                work.server,
                work.capabilities,
                work.databases.size(),
                work.collections.size(),
                work.indexes.size(),
                work.retention.items(),
                work.retention.bytes(),
                work.truncated,
                work.limitations);
        return new Snapshot(
                inspection,
                List.copyOf(work.databases),
                List.copyOf(work.collections),
                List.copyOf(work.indexes),
                List.copyOf(work.diagnostics),
                Map.copyOf(work.targets));
    }

    private void inspectCollection(
            MongoDbClientAccess access,
            Target target,
            String databaseId,
            Work work,
            MongoDbReadBudget deadline,
            boolean nameObserved) {
        String collectionId = id(databaseId, target.collection);
        MongoDbCollectionDto raw;
        boolean optionsRead = true;
        try {
            raw = access.collection(target.database, target.collection, deadline);
        } catch (RuntimeException ex) {
            optionsRead = false;
            work.failure(collectionId, "collection-options", ex);
            raw = new MongoDbCollectionDto(
                    null,
                    null,
                    null,
                    target.collection,
                    "unknown",
                    null,
                    null,
                    null,
                    false,
                    false,
                    false,
                    List.of(capability("options", "FAILED", code(ex))),
                    List.of());
        }
        MongoDbCollectionDto row = work.values.collection(raw, collectionId, databaseId, target.clientId);
        if (!work.retain(List.of(row, target.collection))) return;
        work.collections.add(row);
        if (nameObserved || optionsRead) {
            work.usableExternal = true;
        }
        work.targets.put(collectionId, target);
        if ("view".equals(row.type())) {
            work.addDiagnostic(new MongoDbDiagnosticDto(
                    collectionId,
                    "indexes",
                    "UNSUPPORTED",
                    "Views have no conventional indexes; the view pipeline is not read."));
            return;
        }
        try (MongoDbCursor<MongoDbIndexDto> indexes = access.indexes(target.database, target.collection, deadline)) {
            int count = 0;
            while (deadline.remainingMillis() > 0 && indexes.hasNext()) {
                if (count++ >= settings.maxIndexesPerCollection()) {
                    work.limit("ITEM_LIMIT");
                    break;
                }
                MongoDbIndexDto rawIndex = indexes.next();
                MongoDbIndexDto index = work.values.index(
                        rawIndex, id(collectionId, String.valueOf(count)), collectionId, databaseId, target.clientId);
                if (!work.retain(index)) break;
                work.indexes.add(index);
                work.usableExternal = true;
            }
            work.usableExternal = true;
        } catch (RuntimeException ex) {
            work.failure(collectionId, "indexes", ex);
        }
    }

    private static MongoDbDatabaseDto database(
            String id, String clientId, String name, String provenance, MongoDbValues values) {
        return new MongoDbDatabaseDto(id, clientId, values.exposed("mongodb.database", name), provenance, List.of());
    }

    private String id(String parent, String name) {
        return UUID.nameUUIDFromBytes(
                        (instanceSalt + ":" + generation + ":" + parent + ":" + name).getBytes(StandardCharsets.UTF_8))
                .toString();
    }

    private MongoDbReport project(
            String snapshotId,
            String section,
            String databaseId,
            String collectionId,
            String query,
            Integer offset,
            Integer limit) {
        MongoDbRequests.identifier(snapshotId);
        MongoDbRequests.identifier(databaseId);
        MongoDbRequests.identifier(collectionId);
        if (snapshotId != null) requireSnapshot(snapshotId);
        if ((offset != null && offset > 0 || databaseId != null || collectionId != null) && snapshotId == null) {
            throw MongoDbRequests.invalid("Retained page selectors require snapshotId");
        }
        String kind = section == null ? "DATABASES" : section;
        if (!Set.of("DATABASES", "COLLECTIONS", "INDEXES").contains(kind))
            throw MongoDbRequests.invalid("Invalid section");
        if (collectionId != null && !"INDEXES".equals(kind))
            throw MongoDbRequests.invalid("collectionId requires INDEXES");
        int start = offset == null ? 0 : offset;
        int size = limit == null ? 50 : Math.min(200, limit);
        if (start < 0 || size < 1 || query != null && query.length() > 256) {
            throw MongoDbRequests.invalid("Invalid MongoDB page bounds");
        }
        String search = query == null ? "" : query.toLowerCase(Locale.ROOT);
        List<MongoDbDatabaseDto> databases = List.of();
        List<MongoDbCollectionDto> collections = List.of();
        List<MongoDbIndexDto> indexes = List.of();
        List<?> all = List.of(), matched = List.of();
        if (cached != null) {
            if (databaseId != null && cached.databases.stream().noneMatch(row -> databaseId.equals(row.id())))
                throw new MongoDbRequestException(404, "MongoDB database not found in retained snapshot");
            if (collectionId != null
                    && cached.collections.stream()
                            .noneMatch(row -> collectionId.equals(row.id())
                                    && (databaseId == null || databaseId.equals(row.databaseId()))))
                throw new MongoDbRequestException(404, "MongoDB collection not found in retained snapshot");
            if ("DATABASES".equals(kind)) {
                all = cached.databases;
                List<MongoDbDatabaseDto> filtered = cached.databases.stream()
                        .filter(row -> databaseId == null || databaseId.equals(row.id()))
                        .filter(row -> contains(row.name(), search))
                        .toList();
                matched = filtered;
                databases = page(filtered, start, size);
            } else if ("COLLECTIONS".equals(kind)) {
                all = cached.collections;
                List<MongoDbCollectionDto> filtered = cached.collections.stream()
                        .filter(row -> databaseId == null || databaseId.equals(row.databaseId()))
                        .filter(row -> contains(row.name(), search))
                        .toList();
                matched = filtered;
                collections = page(filtered, start, size);
            } else {
                all = cached.indexes;
                List<MongoDbIndexDto> filtered = cached.indexes.stream()
                        .filter(row -> databaseId == null || databaseId.equals(row.databaseId()))
                        .filter(row -> collectionId == null || collectionId.equals(row.collectionId()))
                        .filter(row -> contains(row.name(), search))
                        .toList();
                matched = filtered;
                indexes = page(filtered, start, size);
            }
        }
        int returned = databases.size() + collections.size() + indexes.size();
        MongoDbCatalogPageDto catalog = new MongoDbCatalogPageDto(
                kind,
                new PageMetadata(
                        all.size(), matched.size(), start, size, returned, (long) start + returned < matched.size()),
                databases,
                collections,
                indexes);
        boolean available = !inventory.clients().isEmpty();
        MongoDbReport result = new MongoDbReport(
                true,
                available,
                available
                        ? null
                        : inventory.complete()
                                ? "No supported managed MongoDB client declaration."
                                : "Local client inventory is incomplete; review its limits and discovery guidance.",
                cached == null ? available ? "NOT_READ" : "DISABLED" : cached.inspection.status(),
                message,
                DISCLAIMER,
                policy.mode().name(),
                inventory,
                cached == null ? null : cached.inspection,
                catalog,
                settings.dto(),
                cached == null ? List.of() : cached.diagnostics);
        if (MongoDbRetentionBudget.bytes(result) > settings.maxMetadataBytes()) {
            throw new MongoDbRequestException(
                    413, "MongoDB report exceeds the metadata byte limit; reduce the retained page size");
        }
        return result;
    }

    private static boolean contains(String text, String query) {
        return text != null && text.toLowerCase(Locale.ROOT).contains(query);
    }

    private static <T> List<T> page(List<T> list, int start, int size) {
        return list.subList(Math.min(start, list.size()), (int) Math.min(list.size(), (long) start + size));
    }

    private static MongoDbCapabilityDto capability(String name, String state, String code) {
        return new MongoDbCapabilityDto(
                name, state, code, code == null ? null : reason(code), "MongoDB driver metadata");
    }

    private static String code(RuntimeException ex) {
        if (!(ex instanceof MongoDbReadException failure)) return "FAILED";
        return Set.of(
                                "DENIED",
                                "TIMEOUT",
                                "CANCELLED",
                                "UNSUPPORTED",
                                "NAMESPACE_DISAPPEARED",
                                "UNAVAILABLE",
                                "AUTHENTICATION_FAILED",
                                "CLEANUP_FAILED")
                        .contains(failure.code())
                ? failure.code()
                : "FAILED";
    }

    private static String reason(String code) {
        return MongoDbMessages.failure(code);
    }

    private final class Work {
        final MongoDbValues values;
        final MongoDbRetentionBudget retention = new MongoDbRetentionBudget(settings);
        final List<MongoDbDatabaseDto> databases = new ArrayList<>();
        final List<MongoDbCollectionDto> collections = new ArrayList<>();
        final List<MongoDbIndexDto> indexes = new ArrayList<>();
        final List<MongoDbSettingDto> server = new ArrayList<>();
        final List<MongoDbCapabilityDto> capabilities = new ArrayList<>();
        final List<MongoDbDiagnosticDto> diagnostics = new ArrayList<>();
        final List<String> limitations = new ArrayList<>();
        final Map<String, Target> targets = new LinkedHashMap<>();
        boolean usableExternal;
        boolean failed;
        boolean truncated;

        Work(MongoDbValues values) {
            this.values = values;
            synchronized (MongoDbInspectionService.this) {
                if (!retention.retain(List.of(inventory, targetNames(declaredTargets)))) {
                    limit(retention.exhausted());
                }
            }
        }

        boolean retain(Object row) {
            if (retention.retain(row)) return true;
            limit(retention.exhausted());
            return false;
        }

        void limit(String code) {
            truncated = true;
            String text = MongoDbMessages.limitation(code);
            if (!limitations.contains(text)) limitations.add(text);
        }

        void failure(String target, String section, RuntimeException error) {
            failed = true;
            addDiagnostic(new MongoDbDiagnosticDto(target, section, code(error), reason(code(error))));
            addCapability(capability(section, "FAILED", code(error)));
            for (Throwable suppressed : error.getSuppressed()) {
                if (suppressed instanceof MongoDbReadException cleanup && "CLEANUP_FAILED".equals(cleanup.code())) {
                    addDiagnostic(
                            new MongoDbDiagnosticDto(target, "cleanup", "CLEANUP_FAILED", reason("CLEANUP_FAILED")));
                }
            }
        }

        void addDiagnostic(MongoDbDiagnosticDto diagnostic) {
            if (diagnostics.size() >= 32) limit("ITEM_LIMIT");
            else if (retain(diagnostic)) diagnostics.add(diagnostic);
        }

        void addCapability(MongoDbCapabilityDto capability) {
            if (capabilities.size() >= 32) limit("ITEM_LIMIT");
            else if (retain(capability)) capabilities.add(capability);
        }
    }

    private record Target(String clientId, String database, String collection) {}

    private record ClientState(
            String id, Object identity, List<String> databases, String driverStyle, String lifecycle) {
        boolean matches(MongoDbProvider.Client client) {
            return identity == client.identity()
                    && databases.equals(client.databases())
                    && driverStyle.equals(client.driverStyle())
                    && lifecycle.equals(client.lifecycle());
        }
    }

    private record Snapshot(
            MongoDbInspectionDto inspection,
            List<MongoDbDatabaseDto> databases,
            List<MongoDbCollectionDto> collections,
            List<MongoDbIndexDto> indexes,
            List<MongoDbDiagnosticDto> diagnostics,
            Map<String, Target> targets) {}
}
