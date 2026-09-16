package io.github.jdubois.bootui.cli;

import io.github.jdubois.bootui.client.JsonValue;
import java.io.IOException;
import java.io.InputStream;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Objects;

/**
 * The command tree the CLI was built with, read from the generated {@code bootui-tools.json}.
 *
 * <p>It exists so {@code bootui --help} works without a running application. It is <em>not</em> the authority
 * on what a given instance exposes: the three stacks advertise different tool sets and panels can be disabled,
 * so what a specific application will actually answer comes from {@code GET /bootui/api/cli} at runtime.
 *
 * <p>The file is generated from {@code McpToolCatalog} and checked in, with a test that fails when the two
 * disagree. That is what makes a new MCP tool impossible to forget: it cannot reach the registry without also
 * reaching this manifest and, through it, the command tree.
 */
public final class ToolManifest {

    private static final String RESOURCE = "/bootui-tools.json";

    private final List<Tool> tools;

    ToolManifest(List<Tool> tools) {
        this.tools = List.copyOf(tools);
    }

    /** Reads the manifest bundled with this CLI. */
    public static ToolManifest bundled() {
        try (InputStream resource = ToolManifest.class.getResourceAsStream(RESOURCE)) {
            if (resource == null) {
                throw new IllegalStateException("Missing " + RESOURCE + " on the classpath");
            }
            return parse(new String(resource.readAllBytes(), StandardCharsets.UTF_8));
        } catch (IOException failure) {
            throw new UncheckedIOException("Cannot read " + RESOURCE, failure);
        }
    }

    /** Parses a manifest document. */
    public static ToolManifest parse(String json) {
        List<Tool> tools = new ArrayList<>();
        for (JsonValue tool : JsonValue.parse(json).get("tools").values()) {
            tools.add(new Tool(
                    tool.get("name").asString(""),
                    tool.get("command").asString(""),
                    tool.get("schema").asString("NONE"),
                    tool.get("panel").asString(""),
                    tool.get("action").asBoolean(false),
                    tool.get("stacks").values().stream()
                            .map(stack -> stack.asString(""))
                            .toList(),
                    tool.get("summary").asString(""),
                    tool.get("arguments").isMissing()
                            ? null
                            : tool.get("arguments").values().stream()
                                    .map(value -> value.asString(""))
                                    .toList(),
                    tool.get("required").isMissing()
                            ? null
                            : tool.get("required").values().stream()
                                    .map(value -> value.asString(""))
                                    .toList()));
        }
        return new ToolManifest(tools);
    }

    /** Every tool, ordered by command path. */
    public List<Tool> tools() {
        return tools;
    }

    /** The entry for one MCP tool name, or {@code null}. */
    public Tool byName(String name) {
        return tools.stream()
                .filter(tool -> tool.name().equals(name))
                .findFirst()
                .orElse(null);
    }

    /**
     * One command.
     *
     * @param name the MCP tool name it invokes
     * @param command the space-separated command path, e.g. {@code memory heap analyze}
     * @param schema the argument schema: {@code NONE}, {@code LIMIT}, {@code QUERY_LIMIT}, {@code ID},
     *     or {@code RULE_VIOLATIONS}
     * @param panel the panel backing it
     * @param action whether it changes state, and is therefore refused on a read-only panel
     * @param stacks the stacks that advertise it, so help can say when a command is stack-specific
     * @param summary a one-line description for help output
     */
    public record Tool(
            String name,
            String command,
            String schema,
            String panel,
            boolean action,
            List<String> stacks,
            String summary,
            List<String> arguments,
            List<String> required) {

        public Tool {
            Objects.requireNonNull(name, "name");
            Objects.requireNonNull(command, "command");
            stacks = stacks == null ? List.of() : List.copyOf(stacks);
            // Compatibility with pre-argument-metadata manifests. Newly generated manifests always
            // carry these lists directly from McpToolSchema, never from a CLI-owned Mongo registry.
            arguments = arguments == null ? legacyArguments(schema) : List.copyOf(arguments);
            required = required == null ? legacyRequired(schema) : List.copyOf(required);
        }

        public Tool(
                String name,
                String command,
                String schema,
                String panel,
                boolean action,
                List<String> stacks,
                String summary) {
            this(name, command, schema, panel, action, stacks, summary, null, null);
        }

        /** The command path split into its words. */
        public List<String> path() {
            return List.of(command.split(" "));
        }

        /** Whether this tool takes a {@code query} filter. */
        public boolean takesQuery() {
            return arguments.contains("query");
        }

        /** Whether this tool takes a {@code limit}. */
        public boolean takesLimit() {
            return arguments.contains("limit");
        }

        /** Whether this tool requires an {@code id} positional. */
        public boolean takesId() {
            return arguments.contains("id");
        }

        /** Whether this tool requires a completed advisor snapshot identifier. */
        public boolean takesScanId() {
            return arguments.contains("scanId");
        }

        /** Whether this tool accepts an offset into retained advisor details. */
        public boolean takesOffset() {
            return arguments.contains("offset");
        }

        public List<String> selectionArguments() {
            return arguments.stream()
                    .filter(name ->
                            !List.of("id", "query", "limit", "scanId", "offset").contains(name))
                    .toList();
        }

        private static List<String> legacyArguments(String schema) {
            return switch (schema) {
                case "ID" -> List.of("id");
                case "LIMIT" -> List.of("limit");
                case "QUERY_LIMIT" -> List.of("query", "limit");
                case "RULE_VIOLATIONS" -> List.of("id", "scanId", "offset", "limit");
                default -> List.of();
            };
        }

        private static List<String> legacyRequired(String schema) {
            return switch (schema) {
                case "ID" -> List.of("id");
                case "RULE_VIOLATIONS" -> List.of("id", "scanId");
                default -> List.of();
            };
        }

        /** Whether every stack advertises this tool. */
        public boolean onEveryStack() {
            return stacks.size() == 3;
        }
    }
}
