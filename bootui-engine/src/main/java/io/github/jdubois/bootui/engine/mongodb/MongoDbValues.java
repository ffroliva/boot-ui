package io.github.jdubois.bootui.engine.mongodb;

import io.github.jdubois.bootui.core.SecretMasker;
import io.github.jdubois.bootui.core.ValueExposure;
import io.github.jdubois.bootui.core.dto.*;
import io.github.jdubois.bootui.engine.support.CredentialRedaction;
import io.github.jdubois.bootui.spi.ExposurePolicy;
import java.math.BigInteger;
import java.util.List;
import java.util.Set;

final class MongoDbValues {
    private static final SecretMasker MASKER = new SecretMasker();
    private static final Set<String> SETTINGS = Set.of(
            "operationTimeoutMillis",
            "version",
            "isWritablePrimary",
            "secondary",
            "msg",
            "maxWireVersion",
            "minWireVersion",
            "readPreference",
            "readConcern",
            "retryReads",
            "retryWrites",
            "tls",
            "minPoolSize",
            "maxPoolSize",
            "serverSelectionTimeoutMillis",
            "logicalSessionTimeoutMinutes",
            "serverVersion",
            "writablePrimary");
    private final Policy policy;
    private final int maxText;
    private final Set<String> omissions = new java.util.LinkedHashSet<>();

    MongoDbValues(Policy policy, int maxText) {
        this.policy = policy;
        this.maxText = maxText;
    }

    String text(String text) {
        if (text == null) return null;
        return safeText("mongodb.metadata", text);
    }

    String exposed(String key, String value) {
        if (value == null) return null;
        if (policy.mode() == ValueExposure.METADATA_ONLY) return SecretMasker.MASKED_VALUE;
        // Mongo's forbidden-data boundary remains unconditional, even in FULL / mask-secrets=false.
        // Inspect the original shape: shortening a JWT first would defeat its anchored detector.
        return safeText(key, value);
    }

    private String safeText(String key, String original) {
        String masked = String.valueOf(MASKER.mask(key, original));
        String safe = CredentialRedaction.redact(masked).replaceAll("[\\p{Cntrl}]", " ");
        if (safe.length() <= maxText || SecretMasker.MASKED_VALUE.equals(safe)) return safe;
        omissions.add("TEXT_LIMIT");
        return safe.substring(0, maxText - 1) + "…";
    }

    boolean truncated() {
        return !omissions.isEmpty();
    }

    Set<String> omissions() {
        return Set.copyOf(omissions);
    }

    private <T> List<T> take(List<T> values, int maximum) {
        if (values.size() > maximum) omissions.add("ITEM_LIMIT");
        return values.subList(0, Math.min(values.size(), maximum));
    }

    static boolean selectable(String value, int max) {
        return value != null
                && !value.isBlank()
                && value.length() <= max
                && value.chars().noneMatch(Character::isISOControl);
    }

    static String symbol(String value, String... allowed) {
        return value != null && List.of(allowed).contains(value) ? value : "UNKNOWN";
    }

    static String integer(String value) {
        if (value == null || value.length() > 40) return null;
        try {
            BigInteger number = new BigInteger(value);
            return number.signum() < 0 ? null : number.toString();
        } catch (NumberFormatException e) {
            return null;
        }
    }

    List<MongoDbSettingDto> settings(List<MongoDbSettingDto> values) {
        return take(
                        values.stream()
                                .filter(value -> value.name() != null && SETTINGS.contains(value.name()))
                                .limit(21)
                                .toList(),
                        20)
                .stream()
                .map(value -> new MongoDbSettingDto(
                        value.name(),
                        exposed(value.name(), value.value()),
                        symbol(value.provenance(), "CONFIGURED", "OBSERVED"),
                        text(value.source())))
                .toList();
    }

    MongoDbTopologyDto topology(MongoDbTopologyDto value) {
        if (value == null) return null;
        return new MongoDbTopologyDto(
                value.observedAt(),
                symbol(value.type(), "UNKNOWN", "STANDALONE", "REPLICA_SET", "SHARDED", "LOAD_BALANCED"),
                symbol(value.mode(), "SINGLE", "MULTIPLE", "LOAD_BALANCED"),
                take(value.servers(), 32).stream()
                        .map(server -> new MongoDbServerDto(
                                exposed("mongodb.endpoint", server.endpoint()),
                                symbol(
                                        server.type(),
                                        "UNKNOWN",
                                        "STANDALONE",
                                        "REPLICA_SET_PRIMARY",
                                        "REPLICA_SET_SECONDARY",
                                        "REPLICA_SET_ARBITER",
                                        "REPLICA_SET_OTHER",
                                        "REPLICA_SET_GHOST",
                                        "SHARD_ROUTER",
                                        "LOAD_BALANCER"),
                                symbol(server.state(), "CONNECTING", "CONNECTED")))
                        .toList(),
                limitations(value.limitations()));
    }

    List<String> limitations(List<String> values) {
        values.stream().filter(MongoDbMessages::truncated).forEach(omissions::add);
        return take(values, 8).stream()
                .map(MongoDbMessages::limitation)
                .map(this::text)
                .toList();
    }

    MongoDbCollectionDto collection(MongoDbCollectionDto value, String id, String databaseId, String clientId) {
        return new MongoDbCollectionDto(
                id,
                databaseId,
                clientId,
                exposed("mongodb.collection", value.name()),
                Set.of("collection", "view", "timeseries").contains(value.type() == null ? "" : value.type())
                        ? value.type()
                        : "unknown",
                value.capped(),
                integer(value.cappedSizeBytes()),
                integer(value.cappedMaxDocuments()),
                value.validatorPresent(),
                value.collationPresent(),
                value.encryptedFieldsPresent(),
                take(value.capabilities(), 8).stream()
                        .map(capability -> new MongoDbCapabilityDto(
                                text(capability.name()),
                                text(capability.state()),
                                text(capability.code()),
                                text(capability.reason()),
                                text(capability.source())))
                        .toList(),
                limitations(value.limitations()));
    }

    MongoDbIndexDto index(MongoDbIndexDto value, String id, String collectionId, String databaseId, String clientId) {
        return new MongoDbIndexDto(
                id,
                collectionId,
                databaseId,
                clientId,
                exposed("mongodb.index", value.name()),
                take(value.keys(), 32).stream()
                        .map(key -> new MongoDbIndexKeyDto(
                                exposed("mongodb.field", key.field()),
                                MongoDbMetadataRules.indexKind(key.field(), key.kind())))
                        .toList(),
                value.unique(),
                value.sparse(),
                value.hidden(),
                integer(value.expireAfterSeconds()),
                value.partialFilterPresent(),
                value.collationPresent(),
                value.wildcardProjectionPresent(),
                value.hasUnsupportedOptions() || value.keys().size() > 32,
                limitations(value.limitations()));
    }

    record Policy(ValueExposure mode, boolean maskSecrets) {
        static Policy of(ExposurePolicy policy) {
            ValueExposure mode = policy == null ? null : policy.valueExposure();
            return new Policy(mode == null ? ValueExposure.MASKED : mode, policy == null || policy.maskSecrets());
        }
    }
}
