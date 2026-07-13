# AI Chatbot System Tools And Memory Profile Design

## Goal

Extend `ai-chatbot` with safe system tools, configurable random-reply tool
calling, administrator-managed user memory profiles, and two-layer memory.
The host first releases a semantic-memory SPI; the plugin consumes only that
published contract.

## Scope And Boundaries

The chatbot owns policy, short-term state, profiles, and its management UI.
The host owns the semantic-memory SPI and implementation. The plugin never
imports Wiki, Neo4j, AI-provider, Spring, or other host-internal types. Neo4j
is an optional host backing store, not a required plugin dependency or graph
model.

All profile management is an `/admin/**` capability protected by
`plugin:ai-chatbot:manage`. The feature intentionally has no personal or
cross-user frontend route.

## System Tools

The plugin registers two native `PluginAiTool` implementations on enable:

| Tool | Purpose | Output | Privacy rule |
| --- | --- | --- | --- |
| `ai_chatbot.lookup_user` | Resolve a QQ account and its system roles | System ID, username, nickname, avatar, account status, role names | The requested QQ must be the current sender or a message mention. Email and phone are never returned. |
| `ai_chatbot.lookup_memory_profile` | Retrieve the subject's current profile in this group | Summary, tags, approved facts, profile status | The requested QQ must be the current sender or a message mention; no profile from another connection or group is visible. |

Both descriptors are `READ` risk and require the chatbot use permission. Their
allowed triggers include `MENTION` and `RANDOM`; runtime policy still chooses
whether a group may expose them. No write or destructive tool is registered,
because the current host execution path only permits read tools in a chat
completion.

## Reply And Tool Decision Flow

1. A group policy decides whether a message may receive a reply using the
   existing enablement, quiet-hours, cooldown, and quota controls.
2. Mention replies may call a selected tool when the selected-tool list is
   non-empty.
3. Random replies may call a selected tool only when the new
   `randomToolCallingEnabled` policy flag is true. The same selected-tool list
   is used, so random replies cannot access an administrator-unselected tool.
4. The host additionally enforces the tool descriptor trigger, tool risk, and
   the bound system user's permission. A missing QQ binding yields no tool
   authorization context and cannot be used to query another user.
5. The prompt tells random replies to use tools only when factually necessary;
   tool availability does not make an invocation mandatory.

## Memory Profile Model

Profiles are scoped by `(connectionId, channelId, userId)` and stored as one
document under `memory-profile`. The stable document ID is the same scoped
tuple. Each document contains:

- scope and identity snapshot: connection ID, channel ID, system user ID,
  platform QQ, nickname;
- lifecycle state: enabled, updated timestamp, source-message count;
- editable summary and tags;
- facts with `key`, `value`, `confidence`, `source`, `updatedAt`, and an
  `approved` flag.

Conversation observations are maintained separately from the profile and are
bounded. A profile builder derives candidate facts only from observations in
the same group. Administrators may run a rebuild, edit facts and summary,
disable a profile, or delete it. Human edits are retained as approved facts so
automatic rebuilding cannot silently overwrite an operator correction.

The first implementation uses deterministic candidate extraction from concise
user messages and an administrator-editable structured profile. AI-assisted
summarization remains a future enhancement because it requires a robust
structured-output contract and explicit operating-cost controls.

## Two-Layer Chat Memory

### Short-Term Memory

The chatbot automatically maintains a bounded rolling window per connection
and channel, plus a bounded per-user conversation window for explicit
mentions. It evicts the oldest entries at the configured limit and does not
require an external capability. This remains the complete memory behavior
when semantic memory is unavailable.

### Long-Term Semantic Memory

Every message observed after the chatbot is enabled is submitted to the host
semantic-memory service as a vector record. The namespace contains the plugin
code, connection ID, channel ID, and system user ID; retrieval never crosses a
group or user namespace. Record metadata contains platform QQ, message ID,
timestamp, role, and source type. On a reply, the plugin searches only the
current namespace with the new message and adds the top relevant excerpts to
the prompt.

The host publishes a generic `PluginSemanticMemoryService` with capability
status and supported embedding models, asynchronous indexing from text and
metadata, namespace-constrained search, and record/namespace deletion. Its
normal implementation may use the existing Neo4j vector index but does not
create graph relationships.

The service is always present in `FrameworkServices`. When the semantic-memory
capability, embedding provider, or backend is disabled, it reports unavailable
and indexing/search returns a non-throwing no-op/empty result. Replies,
profiles, QQ lookup, and short-term memory therefore remain usable without
long-term vector memory. The platform exposes no plugin-facing historical
message archive, so only messages received after plugin enablement can be
vectorized.

## Administration Surface

The existing Settings page remains the group policy route. It gains the
`randomToolCallingEnabled` switch alongside the existing provider/model and
tool selection controls, plus long-term-memory enablement, embedding model,
retrieval top-K, and live capability status. Long-term controls remain visible
but disabled with an explanatory unavailable state when the host capability is
off.

A separate `Memory Profiles` administration route provides a server-paginated
`FaTable` with scoped group, QQ, user identity, status, tags, update time, and
row operations. A focused drawer or modal supports profile inspection and
editing. Operations are detail, save edits, enable/disable, rebuild, and
delete; all invoke separate management-protected endpoints.

## Data Safety

- The QQ lookup tool validates subjects against the trusted message execution
  context, not model-provided free-form input alone.
- Unknown, unbound, disabled, or out-of-scope subjects return a neutral tool
  result without disclosing account existence beyond the permitted subject.
- Profile documents are never queried across connection or channel scope by a
  tool.
- Semantic records and searches carry the same connection/channel/user
  namespace; failed capability checks never fall back to a wider namespace.
- Semantic indexing failure is logged and isolated from the reply pipeline; it
  never prevents the current message from being answered.
- Profile management APIs are separate from any future user self-service API;
  management permission does not alter a user-scoped endpoint because none is
  added here.

## Verification

Host contract tests cover unavailable no-op behavior, namespace isolation, and
the vector adapter. Plugin unit tests cover subject validation, safe user
projection, profile scoping, policy serialization/defaults, random-tool
gating, short-term eviction, semantic-memory degradation, and retrieval
namespace construction. Backend tests/package, SPI publication, the plugin
frontend typecheck/build, and final JAR remote-entry inspection verify the
completed feature.
