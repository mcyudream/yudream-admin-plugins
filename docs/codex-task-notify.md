# Codex Task Notify Plugin

The plugin exposes `POST /api/plugins/codex-task-notify/notify`. Host API Key authentication validates the key and exposes its user ID and permissions to the plugin dispatcher. The plugin sends a QQ private message only to that authenticated user through the published messaging SPI.

## Setup

1. Install and enable the `codex-task-notify` plugin.
2. Enable API Key authentication in the host system.
3. Configure exactly one enabled Milky connection and bind a QQ account to the API Key owner.
4. Create an API Key with `plugin:codex-task-notify:send` after the plugin has registered its permission.

## Request

```http
POST /api/plugins/codex-task-notify/notify
X-API-Key: yda_...
Content-Type: application/json

{
  "type": "COMPLETED",
  "taskId": "codex-thread-123",
  "title": "Plugin build completed",
  "message": "The target module passed package validation.",
  "taskUrl": "https://example.invalid/tasks/codex-thread-123"
}
```

Allowed notification types are `COMPLETED`, `ACTION_REQUIRED`, and `INTERRUPTED`. For `COMPLETED`, the plugin renders `message` below a `完成总结` heading, so provide a concise changed-components, validation, and remaining-limitations summary. The JSON request must not contain a user ID, QQ number, or message-platform connection ID. Do not include API Keys, tokens, passwords, or other secrets in notification text.

## Heartbeat Watchdog

Use the task session endpoints when Codex can terminate unexpectedly. The plugin persists sessions in its own document store and checks them every 15 seconds.

```http
POST /api/plugins/codex-task-notify/tasks/start
X-API-Key: yda_...
Content-Type: application/json

{"taskId":"codex-thread-123","title":"Plugin build","timeoutSeconds":60}
```

Call `POST /api/plugins/codex-task-notify/tasks/heartbeat` with `{"taskId":"codex-thread-123"}` at least once per timeout interval. Use the same API Key for all calls.

On normal state transitions, call one of the following endpoints with `taskId`, `title`, `message`, and optional `taskUrl`:

- `/tasks/complete`: sends `COMPLETED` and closes the session.
- `/tasks/action-required`: sends `ACTION_REQUIRED` and keeps the session active; continue heartbeats while waiting.
- `/tasks/interrupt`: sends `INTERRUPTED` and closes the session.

When an active session reaches its timeout, the watchdog sends one `INTERRUPTED` message and closes it. The watchdog requires the same enabled Milky connection and QQ binding as direct notifications.
