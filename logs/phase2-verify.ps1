# Phase2 RocketMQ 全链路验证脚本（PowerShell 版）
# 覆盖四条链路：①延迟消息关单 ②支付回调异步(PURCHASE) ③用户行为异步落库 ④缓存延迟双删
# 用法：分步运行，每步输出快照文本

$ErrorActionPreference = 'Stop'
$base = 'http://localhost:8080'

# ---------- Step 0: 登录 user6（验证码流程） ----------
function Get-CaptchaCode {
    $cap = Invoke-RestMethod -Method Post -Uri "$base/api/auth/captcha" -ContentType 'application/json' -Body '{}'
    $code = docker exec redis redis-cli GET "captcha:$($cap.captchaId)"
    return @{ captchaId = $cap.captchaId; code = $code }
}

function Login-User {
    param([string]$u, [string]$p)
    $c = Get-CaptchaCode
    $body = @{ account = $u; password = $p; captchaId = $c.captchaId; captchaCode = $c.code } | ConvertTo-Json
    return Invoke-RestMethod -Method Post -Uri "$base/api/auth/login" -ContentType 'application/json' -Body $body
}

$user = Login-User 'user6' 'user6123456'
$token = $user.data.token
$uid = $user.data.id
Write-Output "LOGIN user6 ok uid=$uid"
$h = @{ Authorization = "Bearer $token" }

# ---------- Step 1: 链路③ 浏览行为异步落库 ----------
# 先记录当前 user_behaviors 中该用户 VIEW 计数
$b1 = Invoke-RestMethod -Method Get -Uri "$base/api/products/255" -Headers $h
Write-Output "BROWSE product 255 title=$($b1.data.name)"
Start-Sleep -Seconds 3
Write-Output "STEP3-DONE (view behaviors expected async; check DB separately)"

# ---------- Step 2: 链路④ 缓存延迟双删 ----------
# 先请求商品列表触发缓存写入
$list = Invoke-RestMethod -Method Get -Uri "$base/api/products?page=1&pageSize=5"
Write-Output "LIST products cached ok total=$($list.data.total)"
$keys = docker exec redis redis-cli KEYS '*product*'
Write-Output "REDIS-PRODUCT-KEYS: $($keys -join ',')"

# ---------- Step 3: 链路② mock 支付回调 ----------
# 需要先有一个待付款订单；尝试用已有订单 76/77/78 或新下单（Step 4 再下），这里先看订单列表
$orders = Invoke-RestMethod -Method Get -Uri "$base/api/orders?page=1&pageSize=5" -Headers $h
$ol = $orders.data
Write-Output "ORDERS resp code=$($orders.code) total=$($ol.total) firstId=$($ol.list[0].id) status=$($ol.list[0].status)"

Write-Output "PHASE2-VERIFY-STEP0-3-DONE"
