# YAP v1 端点冒烟验证（ymcl-adapter 启用后运行）
# 用法：
#   .\ci\ymcl-smoke.ps1 -Origin http://localhost:8080 -Username admin -Password <密码>
#   （Password 省略时仅验证匿名端点）
param(
    [string]$Origin = "http://localhost:8080",
    [string]$Username = "",
    [string]$Password = ""
)

$ErrorActionPreference = "Stop"
$base = "$Origin/api/plugins/ymcl-adapter"
$pass = 0
$fail = 0

function Check($name, $condition, $detail = "") {
    if ($condition) {
        Write-Host "  PASS $name" -ForegroundColor Green
        $script:pass++
    } else {
        Write-Host "  FAIL $name $detail" -ForegroundColor Red
        $script:fail++
    }
}

Write-Host "=== YAP v1 冒烟验证：$base ==="

# 1. capabilities（匿名）
# 先做启用状态探测：非 2xx 时从响应流读取宿主信封（PS 5.1 需手动读流）。
$probeBody = ""
try {
    Invoke-WebRequest -UseBasicParsing -Uri "$base/v1/capabilities" -TimeoutSec 10 | Out-Null
} catch {
    try {
        $stream = $_.Exception.Response.GetResponseStream()
        if ($stream) {
            $reader = New-Object System.IO.StreamReader($stream)
            $probeBody = $reader.ReadToEnd()
        }
    } catch { $probeBody = "" }
    if ($probeBody -like '*插件未启用*') {
        Write-Host "  FAIL capabilities：插件未启用" -ForegroundColor Red
        Write-Host ""
        Write-Host "  解决：yda 后台 → 插件管理 → 刷新 → 启用 ymcl-adapter（管理员操作）。" -ForegroundColor Yellow
        exit 1
    }
    Check "capabilities 可达" $false ($_.Exception.Message + " " + $probeBody)
    Write-Host "`n无法连接 yda（$Origin）。请确认后端正在运行且地址正确。" -ForegroundColor Yellow
    exit 1
}

try {
    $caps = Invoke-RestMethod -Method GET -Uri "$base/v1/capabilities" -TimeoutSec 10
    Check "capabilities 可达且匿名" ($null -ne $caps.protocol_version)
    Check "  protocol_version = 1" ($caps.protocol_version -eq 1)
    Check "  domain.name 存在" (-not [string]::IsNullOrEmpty($caps.domain.name))
    Check "  auth.methods 至少一种" ($caps.auth.methods.Count -ge 1)
} catch {
    Check "capabilities 可达" $false $_.Exception.Message
    Write-Host "`n无法连接 yda（$Origin）。请确认后端正在运行且地址正确。" -ForegroundColor Yellow
    exit 1
}

# 2. manifest（匿名公开版）
try {
    $manifest = Invoke-RestMethod -Method GET -Uri "$base/v1/manifest" -TimeoutSec 10
    Check "manifest 可达" ($null -ne $manifest.navigation)
    Check "  navigation 非空" ($manifest.navigation.Count -ge 1)
    Check "  actions.allow 含 client:launch-server" (
        $manifest.actions.allow -contains "client:launch-server")
} catch {
    Check "manifest 可达" $false $_.Exception.Message
}

# 3. 登录换 token + 鉴权端点（提供密码时才验证）
$token = $null
if ($Password -ne "") {
    try {
        $login = Invoke-RestMethod -Method POST -Uri "$Origin/api/user/login" `
            -ContentType "application/json" `
            -Body (@{ username = $Username; password = $Password } | ConvertTo-Json) `
            -TimeoutSec 10
        $token = if ($login.data) { $login.data.token } else { $login.token }
        Check "宿主登录取得 token" (-not [string]::IsNullOrEmpty($token))
    } catch {
        Check "宿主登录" $false $_.Exception.Message
    }
} else {
    Write-Host "  SKIP 登录与鉴权端点（未提供 -Password）" -ForegroundColor DarkGray
}

$headers = @{}
if ($token) { $headers["Authorization"] = $token }

if ($token) {
    try {
        $session = Invoke-RestMethod -Method GET -Uri "$base/v1/session" `
            -Headers $headers -TimeoutSec 10
        Check "session 返回 user_id" (-not [string]::IsNullOrEmpty($session.user_id))
        Check "  username 存在" (-not [string]::IsNullOrEmpty($session.username))
    } catch {
        Check "session 可达" $false $_.Exception.Message
    }

    try {
        $env_data = Invoke-RestMethod -Method GET `
            -Uri "$base/v1/data/minecraft-server/servers" -Headers $headers -TimeoutSec 15
        Check "data 信封（minecraft-server/servers）" ($null -ne $env_data.records)
    } catch {
        Check "data 信封（需 minecraft-server 插件在线）" $false $_.Exception.Message
    }
}

Write-Host ""
Write-Host "=== 结果：$pass 通过 / $fail 失败 ==="
if ($fail -gt 0) { exit 1 }
