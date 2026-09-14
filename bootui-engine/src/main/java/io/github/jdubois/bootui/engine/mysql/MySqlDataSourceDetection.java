package io.github.jdubois.bootui.engine.mysql;

import java.lang.reflect.Method;
import java.util.Locale;
import javax.sql.DataSource;

/** Declaration-only detection. Unknown declarations are candidates, never proof of a supported server. */
public final class MySqlDataSourceDetection {
    private MySqlDataSourceDetection() {}

    public static boolean isMySqlJdbcUrl(String value) {
        if (value == null) {
            return false;
        }
        String url = value.strip().toLowerCase(Locale.ROOT);
        if (!url.startsWith("jdbc:")) {
            return false;
        }
        // Stop at the address: a database name or URL parameter cannot declare the driver.
        String protocol = url.split("//", 2)[0];
        for (String segment : protocol.split(":")) {
            if (segment.equals("mariadb")) {
                return false;
            }
        }
        for (String segment : protocol.split(":")) {
            if (segment.equals("mysql")) {
                return true;
            }
        }
        return false;
    }

    public static boolean isMySqlDbKind(String value) {
        return value != null && value.strip().equalsIgnoreCase("mysql");
    }

    public static String jdbcUrlOf(DataSource source) {
        if (source == null) {
            return null;
        }
        for (String name : new String[] {"getJdbcUrl", "getUrl"}) {
            try {
                Method method = source.getClass().getMethod(name);
                if (method.getReturnType() != String.class) {
                    continue;
                }
                method.trySetAccessible();
                Object value = method.invoke(source);
                if (value instanceof String url && !url.isBlank()) {
                    return url;
                }
            } catch (ReflectiveOperationException | RuntimeException | LinkageError ignored) {
                // Unknown is deliberately not a negative declaration.
            }
        }
        return null;
    }
}
