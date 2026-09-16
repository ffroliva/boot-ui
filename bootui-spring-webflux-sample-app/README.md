# BootUI WebFlux sample

This reference app serves BootUI at <http://localhost:8081/bootui/> on Spring Boot's reactive Netty stack.
Its ordinary `dev` build remains Docker-free, with H2/JDBC (off the event loop), Flyway, Liquibase, and local caching.
It does not use JPA.

## Optional reactive MongoDB diagnostics

Maven profile `mongodb-diagnostics` adds only `spring-boot-starter-data-mongodb-reactive` and the sources in
`src/mongodb/java`. It does **not** add the synchronous Mongo driver or JPA. The matching Spring profile requires
`BOOTUI_SAMPLE_MONGODB_URL`; there is no implicit server, Docker startup, document scan, index creation, or scheduled
Mongo workload.

Provision the authenticated MongoDB 8.0.19 standalone fixture using the
[MVC sample's shared-fixture instructions](../bootui-spring-sample-app/README.md#disposable-authentication-and-shared-fixture).
The URL must point at its dynamic loopback address, select `bootui_sample`, and use the non-root `bootui` account
with `authSource=bootui_sample` (not the initialization administrator). Supply the URL in your environment, never in
source control. Start the application from the repository root:

```bash
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" -Pmongodb-diagnostics \
  -pl bootui-spring-webflux-sample-app -am -DskipTests clean install
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" -Pmongodb-diagnostics \
  -pl bootui-spring-webflux-sample-app spring-boot:run -Dspring-boot.run.profiles=mongodb-diagnostics
```

Open <http://localhost:8081/bootui/#/mongodb>. Both the manifest and initial report are local metadata only;
`GET /bootui/api/mongodb` starts at `NOT_READ`. Choose **Inspect** to read the configured database. REST callers
must get `inventory.clients[].id` from that report and POST
`{"clientId":"<id>","scope":"CONFIGURED"}` to `/bootui/api/mongodb/inspect`. Restricted permissions may make
some metadata partial; the console must not fabricate cluster privileges.

The application repository has its own explicit, bounded POST workload: two fixed document upserts followed
by a top-three derived repository query. It is fully reactive (no `block()`/`subscribe()` in application code),
allows only one in-flight workload, uses two-second driver operation limits, and has an eight-second request budget.
It leaves existing MVC fixture IDs and H2 relational data untouched.

To exercise it from the browser developer console on the same application origin:

```javascript
const csrf = await fetch('/api/sample/mongodb/csrf').then(response => response.json())
const result = await fetch('/api/sample/mongodb/workload', {
  method: 'POST',
  headers: {[csrf.headerName]: csrf.token}
}).then(response => response.json())
console.log(result)
```

`GET /api/sample/mongodb` reports local availability without using the driver. All sample routes are absent
when the Spring profile is off. Even when the optional Maven sources are compiled, their inactive-profile
configuration prevents an automatic Mongo client in ordinary `dev` tests. The optional profile uses `target-mongodb`,
separate from ordinary `target`, so compiled opt-in classes cannot leak when switching back.

## Focused tests

With this worktree's upstream artifacts installed:

```bash
./mvnw -B -ntp -Dmaven.repo.local="$PWD/.m2" -pl bootui-spring-webflux-sample-app \
  -Pmongodb-diagnostics test -Dtest=MongoDiagnosticsProfileTests,MongoDiagnosticsDevIsolationTests
```

These tests check reactive-only classpath, profile settings, lazy bounded workload composition, and
no clients in `dev`. They do not contact Mongo or claim a real-container/browser result. Live adapter and
browser coverage is separate from these sample tests. Stop the application to close its managed client;
remove only the shared disposable fixture using the MVC README's cleanup command.
