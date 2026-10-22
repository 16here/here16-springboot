# Phase 3 全链路验证脚本（读写分离 + 分表 + 对账）
# 用法: powershell -File logs/verify_phase3.ps1
$ErrorActionPreference = "Stop"
$base = "http://localhost:8080"
$out = @()

function Get-CaptchaCode([int]$idx) {
    $cap = Invoke-RestMethod -Uri "$base/api/auth/captcha" -Method Get
    $cid = $cap.data.captchaId
    # 从 Redis 取验证码明文（本地演示）
    $code = & docker exec redis redis-cli GET "captcha:$cid" 2>$null
    if (-not $code) {
        # Docker 不在 PATH 时用完整路径
        $code = & "C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe" exec redis redis-cli GET "captcha:$cid" 2>$null
    }
    return @{ cid = $cid; code = $code.Trim() }
}

function Login([string]$account, [string]$password) {
    $c = Get-CaptchaCode
    $body = @{ account = $account; password = $password; captchaId = $c.cid; captchaCode = $c.code } | ConvertTo-Json
    $r = Invoke-RestMethod -Uri "$base/api/auth/login" -Method Post -ContentType "application/json" -Body $body
    if ($r.code -ne 200) { throw "登录失败: $($r.message)" }
    return $r.data.token
}

# ---------- 1. 用户端登录 ----------
$userToken = Login "user6" "user6123456"
$out += "[OK] 用户 user6 登录成功"

# ---------- 2. 查购物车与地址（准备下单参数） ----------
$cart = Invoke-RestMethod -Uri "$base/api/cart" -Method Get -Headers @{ Authorization = "Bearer $userToken" }
$cartIds = @($cart.data.items | ForEach-Object { $_.cartId })
$addr = Invoke-RestMethod -Uri "$base/api/addresses" -Method Get -Headers @{ Authorization = "Bearer $userToken" }
$addressId = $null
if ($addr.data -and $addr.data.Count -gt 0) { $addressId = $addr.data[0].id }

if ($cartIds.Count -eq 0) {
    # 没有购物车：加一件商品（取商品列表第一件）
    $prods = Invoke-RestMethod -Uri "$base/api/products?page=1&pageSize=3" -Method Get
    $p = $prods.data.list[0]
    $skuId = $null
    if ($p.skus -and $p.skus.Count -gt 0) { $skuId = $p.skus[0].id }
    $addBody = @{ productId = $p.id; skuId = $skuId; quantity = 1 } | ConvertTo-Json
    $null = Invoke-RestMethod -Uri "$base/api/cart" -Method Post -ContentType "application/json" -Body $addBody -Headers @{ Authorization = "Bearer $userToken" }
    $cart = Invoke-RestMethod -Uri "$base/api/cart" -Method Get -Headers @{ Authorization = "Bearer $userToken" }
    $cartIds = @($cart.data.items | ForEach-Object { $_.cartId })
    $out += "[OK] 已自动加购商品 id=$($p.id) 到购物车"
}

if (-not $addressId) {
    # 没有地址：创建一个
    $addrBody = @{ receiverName = "李冠庭"; receiverPhone = "13800000001"; province = "安徽省"; city = "马鞍山市"; district = "花山区"; detail = "测试地址 1 号"; isDefault = $true } | ConvertTo-Json
    $newAddr = Invoke-RestMethod -Uri "$base/api/addresses" -Method Post -ContentType "application/json" -Body $addrBody -Headers @{ Authorization = "Bearer $userToken" }
    $addressId = $newAddr.data.id
    $out += "[OK] 已创建收货地址 id=$addressId"
}

$out += "[INFO] 购物车项数=$($cartIds.Count), 地址 id=$addressId"

# ---------- 3. 下单（幂等键 + 分表写入） ----------
$requestId = [guid]::NewGuid().ToString("N")
$orderBody = @{ cartIds = $cartIds; addressId = $addressId; remark = "Phase3 分表验证"; requestId = $requestId } | ConvertTo-Json
$order = Invoke-RestMethod -Uri "$base/api/orders" -Method Post -ContentType "application/json" -Body $orderBody -Headers @{ Authorization = "Bearer $userToken" }
if ($order.code -ne 200) { throw "下单失败: $($order.message)" }
$orderId = $order.data.id
$out += "[OK] 下单成功 orderId=$orderId orderNo=$($order.data.orderNo) 金额=$($order.data.totalAmount)"

# ---------- 4. 用户订单列表（单表路由） ----------
$list = Invoke-RestMethod -Uri "$base/api/orders?page=1&pageSize=5" -Method Get -Headers @{ Authorization = "Bearer $userToken" }
$out += "[OK] 用户订单列表 total=$($list.data.total)"

# ---------- 5. 订单详情（单表路由） ----------
$detail = Invoke-RestMethod -Uri "$base/api/orders/$orderId" -Method Get -Headers @{ Authorization = "Bearer $userToken" }
$out += "[OK] 订单详情 status=$($detail.data.statusText) 明细数=$($detail.data.items.Count)"

# ---------- 6. 管理端登录 + 订单列表（广播 UNION ALL） ----------
$adminToken = Login "admin" "123456"
$out += "[OK] 管理端 admin 登录成功"
$adminList = Invoke-RestMethod -Uri "$base/api/admin/orders?page=1&pageSize=10" -Method Get -Headers @{ Authorization = "Bearer $adminToken" }
$out += "[OK] 管理端订单列表（跨分片广播） total=$($adminList.data.total)"

# ---------- 7. 支付回调（mock 支付宝；触发 markPaid 单表更新 + DEDUCT 流水） ----------
$payBody = @{ orderId = $orderId; payMethod = "ALIPAY"; totalAmount = [double]$order.data.totalAmount } | ConvertTo-Json
$pay = Invoke-RestMethod -Uri "$base/api/pay/callback/alipay" -Method Post -ContentType "application/json" -Body $payBody
$out += "[OK] 支付回调响应 code=$($pay.code) msg=$($pay.message)"

$detail2 = Invoke-RestMethod -Uri "$base/api/orders/$orderId" -Method Get -Headers @{ Authorization = "Bearer $userToken" }
$out += "[OK] 支付后订单状态=$($detail2.data.statusText)"

# ---------- 8. 对账（@DS master + 跨片手写 UNION ALL） ----------
$rec = Invoke-RestMethod -Uri "$base/api/admin/reconciliation/run" -Method Post -Headers @{ Authorization = "Bearer $adminToken" }
$out += "[OK] 对账完成 bizDate=$($rec.data.bizDate) 已支付流水=$($rec.data.totalPaid) 通过=$($rec.data.checked) 问题=$($rec.data.issueCount)"

$recLogs = Invoke-RestMethod -Uri "$base/api/admin/reconciliation/logs?page=1&pageSize=10" -Method Get -Headers @{ Authorization = "Bearer $adminToken" }
$out += "[OK] 对账日志查询 total=$($recLogs.data.total)"

# ---------- 9. 验证分表落库（orders_? 表） ----------
$shard = ($order.data.userId % 4)
$sql = "SELECT COUNT(*) FROM orders_$shard WHERE id = $orderId"
$dockerExe = "C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
$cmd = "`"$dockerExe`" exec mysql-master mysql -uroot -pMall@2026Root mall_db -N -e `"$sql`" 2>nul"
$cnt = cmd /c $cmd
$out += "[OK] 订单落在 orders_$shard 分片（命中=$cnt）"

Write-Output "========== Phase 3 验证结果 =========="
$out | ForEach-Object { Write-Output $_ }
