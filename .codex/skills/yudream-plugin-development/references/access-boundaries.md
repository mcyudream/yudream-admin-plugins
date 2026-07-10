# User And Admin Access Boundaries

Strictly separate public, user, and administration capabilities. Authorization has two independent dimensions:

1. Permission determines whether the caller may enter a surface or invoke a use case.
2. Data scope determines which records that surface may access.

Having an admin permission does not change the data scope of a user-side request.

## Surface Classification

Classify every route, component, endpoint, and use case before implementation:

- Public: deliberately unauthenticated or broadly visible data with no private fields.
- User: authenticated self-service behavior scoped to the current principal and resources they own or are explicitly related to.
- Admin: cross-user or system-wide management behavior protected by a dedicated management permission.

Do not combine these modes in one endpoint with `if (hasManagePermission)`. Do not make data scope depend on whether the same account happens to hold additional roles.

## Mandatory Separation

For a plugin with both self-service and administration, define separate:

- permissions, normally `USER_PERMISSION` and `MANAGE_PERMISSION`;
- frontend routes and menu entries;
- route components/pages, even if they reuse presentational components;
- API wrapper methods;
- HTTP paths and annotated endpoints;
- controller methods, preferably user/admin controller classes;
- application queries/commands or explicit use-case methods;
- authorization and ownership tests.

Use the repository's skin plugin pattern as a structural reference: `YuDreamSkinUserController` serves `/me/**`; `YuDreamSkinAdminController` serves `/admin/**`.

## User-Side Rules

- Use `/me`, `/me/**`, or another unambiguously personal namespace for self-service endpoints.
- Derive the subject/owner ID only from `request.principal().userId()` or an equally trusted authenticated identity.
- Design personal request DTOs without `userId`, `ownerId`, `memberId`, or similar subject-selection fields unless the field represents someone other than the resource owner and is independently authorized.
- Scope repository queries and mutations by both resource ID and current owner where possible, for example `findByIdAndOwnerId(id, principalId)` and `deleteByIdAndOwnerId(...)`.
- If an object is addressed by name, hash, or another natural key, still verify ownership before returning or mutating it.
- Return only the principal's records and aggregate counts. Do not leak existence, totals, identifiers, names, status, or metadata belonging to unrelated users.
- An administrator entering a user route or calling a user endpoint is still scoped to their own principal identity.
- Never allow query/body/path parameters to select another user on a user endpoint. Prefer removing such parameters; reject conflicts explicitly if compatibility requires accepting them.
- Do not expose admin controls, cross-user search, user selectors, or global statistics in user pages.

Forbidden pattern:

```java
if (request.principal().hasPermission(MANAGE_PERMISSION)) {
    userId = request.query("userId");
} else {
    userId = String.valueOf(request.principal().userId());
}
```

Required pattern:

```java
String userId = requirePrincipalUserId(request);
return PluginHttpResponse.ok(appService.listMyRecords(userId, page(request), size(request)));
```

## Admin-Side Rules

- Use `/admin/**` for cross-user or system-wide management endpoints.
- Protect every admin endpoint with `MANAGE_PERMISSION` or a narrower dedicated permission.
- Accept explicit target user/resource identifiers only on admin request/query types.
- Provide a distinct admin management route and page, normally under an admin/system parent menu.
- Use the management-page standard for lists, pagination, filters, tables, and row actions.
- Support the domain-relevant management lifecycle: list and detail plus create, edit, enable/disable, delete/archive, assign, approve, or other applicable operations.
- Do not require administrators to impersonate a user or visit the user page to manage user-owned records.
- Apply domain invariants and audit information to admin mutations; management permission is not permission to bypass business rules.

## Corresponding Management Capability

When a plugin lets users create, update, or own persistent records, determine the operational administration requirement in the same feature:

- Provide an admin list with cross-user search/filter and pagination when records can exceed 5.
- Provide detail and mutation operations needed for support, moderation, correction, lifecycle control, and deletion/archive.
- Keep admin response DTOs capable of identifying the owner, while personal response DTOs should not expose unrelated ownership data.
- If a domain intentionally forbids administrator mutation of a field, document and enforce the restriction; still provide the necessary read/audit/support capability when required.
- Do not mark the feature complete when user self-service exists but the required management loop is absent.

## Frontend Routing And State

- Give user and admin routes distinct paths, names, titles, components, and permission metadata.
- A user component may reuse typed form/table primitives but must not receive an `isAdmin` flag that changes its data scope.
- An admin component may reuse presentation-only components, but must call admin API methods and maintain its own filters, selected user, pagination, and actions.
- Do not conditionally reveal other users' data in a user page based on `hasPermission(MANAGE_PERMISSION)`.
- Do not share a store/composable whose cache mixes personal and admin datasets. Use separate state or scope cache keys by surface and principal.
- On account change, impersonation change, logout, or plugin unload, clear personal cached data.

## Backend Use Cases And Repositories

- Prefer explicit names such as `getMyProfile(principalId)`, `listMyOrders(principalId, query)`, `adminPageProfiles(query)`, and `adminUpdateProfile(targetUserId, cmd)`.
- Avoid ambiguous methods such as `list(userId, isAdmin)` or `save(cmd, manage)` that can accidentally widen scope.
- Enforce ownership in the backend even when the frontend hides the capability.
- Pass the trusted principal identity into application use cases; never let an interface assembler copy an untrusted personal `userId` into a command.
- Keep admin and user query objects separate when their filters or returned fields differ.
- Consider returning not-found for another user's resource when revealing its existence would leak information; otherwise return the project's standard forbidden response. Be consistent within the plugin.

## Authorization Test Matrix

Test at least these cases for every user-owned resource:

| Caller | Surface | Target | Expected |
| --- | --- | --- | --- |
| ordinary user | user | own record | allowed according to user permission and domain rules |
| ordinary user | user | another user's record or supplied owner ID | denied/not found; no data leak or mutation |
| ordinary user | admin | any record | denied |
| administrator | user | own record | allowed only as ordinary self-service |
| administrator | user | another user's record or supplied owner ID | denied/not found; admin role gives no bypass |
| administrator | admin | permitted target | allowed according to management permission and domain rules |
| unauthenticated caller | user/admin | any record | denied |

Also verify list totals, pagination, downloads, exports, dashboard cards, autocomplete/options, batch operations, SSE/events, and file endpoints. These secondary paths must obey the same surface and ownership boundary.

## Review Scans

Inspect user-side code for suspicious privilege branching and selectable owner IDs:

```powershell
rg -n "hasPermission\(.*MANAGE|userId|ownerId|memberId" yudream-plugins/yudream-plugin-{code}/src/main/java -g "*.java"
rg -n "isAdmin|canManage|userId|ownerId|admin" yudream-frontend/packages/plugin-{code}/src -g "*.ts" -g "*.vue"
```

Every match is not automatically wrong, but any management-permission check inside a user endpoint/facade or any owner selector in a personal page requires correction or a documented non-ownership meaning.

## Completion Checklist

- Are public, user, and admin surfaces classified and separate?
- Do `/me/**` operations always derive ownership from the principal?
- Does an administrator on the user surface remain limited to their own data?
- Are cross-user operations available only through `/admin/**` and management permissions?
- Is there a corresponding admin management page and backend lifecycle for user-owned persistent data?
- Are frontend routes, API wrappers, controllers, use cases, DTOs, and state separated by surface?
- Does the authorization matrix pass for read, write, delete, list, export/download, and secondary endpoints?
