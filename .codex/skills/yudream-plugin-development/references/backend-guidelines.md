# Backend Plugin Guidelines

## Dependency Boundary

- Depend on `online.yudream.base:yudream-plugin-spi` as the only host compile-time contract.
- Do not depend on host `domain`, `application`, `infrastructure`, `interfaces`, or bootstrap modules.
- Access host capabilities through stable SPI ports such as `FrameworkServices`, user services, security services, storage, or other published ports.
- Keep plugin-owned Thymeleaf image templates in `src/main/resources/templates` inside that plugin JAR. Use `PluginContext.templateRenderer()` with a logical template name and optional selector; do not read host templates or another plugin's resources.
- If the SPI lacks a required capability, change and release the host contract before consuming it here.

## Package Responsibilities

Scale the following layout to plugin complexity:

```text
online.yudream.base.plugin.{code}
  domain/
    aggregate/ enumerate/ repo/ service/ valobj/
  application/
    assembler/ cmd/ dto/ query/ service/
  infrastructure/
    dataobj/ mapper/ repository/ service/ support/
  interfaces/
    assembler/ controller/ http/ request/ res/
  migration/
  bootstrap/
```

- Domain owns business meaning, invariants, aggregates, value objects, and repository contracts.
- Application owns use cases, orchestration, commands, queries, DTOs, transactions, and application assemblers.
- Infrastructure owns persistence implementations, data objects, mappers, external adapters, and technical support.
- Interfaces owns plugin HTTP annotations, request/response models, web assemblers, validation, and boundary parsing.
- Bootstrap owns the `YuDreamPlugin` entry, object composition, registration, lifecycle, and cleanup.

Small plugins may omit unused folders, but must not collapse unrelated responsibilities into the entry class.

## Entry And Registration

- Implement `YuDreamPlugin` in the bootstrap package.
- Prefer annotations for static plugin metadata, frontend declarations, routes, menus, permissions, HTTP endpoints, migrations, and capabilities.
- Use `PluginContext.registerXxx(...)` for dynamic/conditional registration or established runtime wiring.
- Register every contribution through `PluginContext` so unload can remove it.
- Keep `onEnable` focused on constructing services, registering controllers/contributions, and starting only required resources.
- Release executors, connections, handlers, and other plugin-owned resources in disable/unload paths.

## HTTP Boundary

- Put annotated endpoint methods in `interfaces/controller`.
- Keep controllers thin and delegate behavior to an HTTP facade or application service.
- Convert `request -> cmd` and `DTO -> res` in interface assemblers.
- Do not put business invariants, persistence queries, large JSON parsing blocks, or response mapping in controllers.
- Mount normal plugin APIs under the plugin runtime namespace and use relative endpoint paths in `@PluginHttpEndpoint`.
- Declare a permission on protected endpoints and keep frontend route/menu permissions aligned.
- For managed collections that can exceed 5 records, expose a real paged query accepting `page`, `size`, and relevant filters and returning records plus a total count. Do not force the frontend to download an unbounded dataset for normal administration.
- Expose the domain-appropriate removal operation for manageable records: hard delete, soft delete, disable, archive, or revoke. Name the UI action truthfully and enforce its permission at the endpoint.
- Make delete/disable operations deterministic for missing, protected, referenced, or non-removable records and return a useful business error instead of silently succeeding.
- Read `access-boundaries.md` for every authenticated endpoint. Separate user and admin controllers/endpoints, derive user-side ownership from `request.principal()`, and never widen a user endpoint because the principal also has a management permission.
- Do not accept client-supplied owner/user IDs on personal create/update/query/delete operations. Ignore is insufficient when it hides misuse; prefer request types that do not contain the field, or reject conflicting ownership input explicitly.

## Contracts And Serialization

- Use serialization-friendly records or simple DTOs at plugin boundaries.
- Do not expose domain aggregates, repository objects, persistence data objects, Spring beans, or host request/res classes.
- Serialize Java `Long` identifiers as strings at HTTP/plugin boundaries and parse them inside application services when needed.
- Keep request, response, application DTO, and persistence types separate when their responsibilities differ.

## Persistence And Migrations

- Put repository interfaces in domain and implementations in infrastructure.
- Keep data objects out of application and interfaces.
- Put schema/data migrations in the plugin migration package and declare them through the plugin contract.
- Make migrations deterministic and compatible with plugin enable/upgrade behavior.

## Backend Validation

Run targeted validation first:

```powershell
mvn -pl yudream-plugins/yudream-plugin-{code} -am test
mvn -pl yudream-plugins/yudream-plugin-{code} -am package -DskipTests
```

Also scan changed controllers for inline construction/mapping and verify that the JAR contains the expected plugin metadata and frontend assets for full-stack changes.

For managed collections, verify page boundaries, filter totals, delete authorization, protected-record behavior, and the result after deleting the last record on a page.

For user/admin features, run the authorization matrix in `access-boundaries.md`, including an administrator calling user endpoints with another user's ID. That request must not return or mutate the other user's data.
