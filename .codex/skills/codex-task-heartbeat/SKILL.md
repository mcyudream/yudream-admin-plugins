---
name: codex-task-heartbeat
description: Report Codex task lifecycle events to the YuDream Codex Task Notify plugin and keep its watchdog heartbeat alive. Use for Codex tasks in a configured YuDream Admin workspace when YUDREAM_CODEX_NOTIFY_BASE_URL and YUDREAM_CODEX_API_KEY are available.
---

# Codex Task Heartbeat

Use this workflow only when both environment variables are present. Do not print either value, put the API Key in a payload, or include secrets in notification text.

Set `$base` to `YUDREAM_CODEX_NOTIFY_BASE_URL`, whose value ends with `/api/plugins/codex-task-notify`. Use the same API Key and task ID for every request in one task.

## Start

At task start, generate a UUID task ID and register a 60-second session. Retain the generated ID in task context. Terminal calls may use separate PowerShell processes, so reassign `$taskId` to that recorded literal before every later request.

```powershell
$base = $env:YUDREAM_CODEX_NOTIFY_BASE_URL.TrimEnd('/')
$headers = @{ 'X-API-Key' = $env:YUDREAM_CODEX_API_KEY }
$taskId = [guid]::NewGuid().ToString()
$start = @{ taskId = $taskId; title = 'Short task title'; timeoutSeconds = 60 } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/tasks/start" -Headers $headers -ContentType 'application/json' -Body $start
```

## Heartbeat

Send a heartbeat at least every 30 seconds while the task remains active. Send one before and after a long command. Continue heartbeats while waiting for user confirmation.

```powershell
$heartbeat = @{ taskId = $taskId } | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/tasks/heartbeat" -Headers $headers -ContentType 'application/json' -Body $heartbeat
```

## State Changes

Post concise, user-safe text to the endpoint that matches the state. Use `complete` only after all requested work and verification finish. For `complete`, make `message` a completion summary: changed components, validation result, and any remaining limitation. Use `action-required` before asking a blocking question, then keep heartbeats active. Use `interrupt` only for a recoverable stop.

```powershell
$event = @{
  taskId = $taskId
  title = 'Short state summary'
  message = "Changed: ...`nValidated: ...`nRemaining: none"
  taskUrl = ''
} | ConvertTo-Json
Invoke-RestMethod -Method Post -Uri "$base/tasks/complete" -Headers $headers -ContentType 'application/json' -Body $event
```

Replace `complete` with `action-required` or `interrupt` as appropriate. Do not use the generic `/notify` endpoint for tracked tasks, because it does not close or preserve the heartbeat session.

If a notification request fails, do not expose credentials or alter the primary task result. State that task notification failed and continue with the original completion or blocker.
