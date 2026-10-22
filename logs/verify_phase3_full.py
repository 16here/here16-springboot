# -*- coding: utf-8 -*-
"""找有 SKU 的商品，然后跑加购→下单→支付→回调全链路"""
import json, time, urllib.request, urllib.error, hashlib
from urllib.parse import urlencode

BASE = "http://localhost:8080"
OUT = []

def req(method, path, token=None, body=None, form=None):
    url = BASE + path
    data = None
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
    elif form is not None:
        data = urlencode(form).encode()
    r = urllib.request.Request(url, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=15) as resp:
            raw = resp.read().decode()
            try:
                return json.loads(raw)
            except Exception:
                return raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        OUT.append(f"{method} {path} -> HTTP {e.code}: {raw[:300]}")
        try:
            return json.loads(raw)
        except Exception:
            return raw

token = open(r"D:\myproject\here16\here16-springboot\logs\token6.txt", encoding="utf-8-sig").read().strip()

# 找有 SKU 的商品（最多看 15 个）
product_id, sku_id = None, None
for pid in range(1, 16):
    r = req("GET", f"/api/products/{pid}", token)
    d = (r or {}).get("data") or {}
    skus = d.get("skus") or []
    if skus:
        product_id, sku_id = pid, skus[0]["id"]
        OUT.append(f"use product {pid} sku {sku_id} name={d.get('name')}")
        break
if not product_id:
    OUT.append("NO product with sku found")
    raise SystemExit("\n".join(OUT))

# 加购
r = req("POST", "/api/cart", token, body={"productId": product_id, "skuId": sku_id, "quantity": 1})
cart = (r or {}).get("data") or {}
cart_id = cart.get("id") or cart.get("cartId")
OUT.append(f"addCart -> {json.dumps(r, ensure_ascii=False)[:300]}")

# 购物车列表取 cartId
r = req("GET", "/api/cart", token)
items = (r or {}).get("data", {}).get("items") or (r or {}).get("data") or []
if isinstance(items, list) and items:
    cart_id = items[-1].get("id") or items[-1].get("cartId")
OUT.append(f"cartId={cart_id}")

# 下单
rid = "phase3-" + str(int(time.time() * 1000))
r = req("POST", "/api/orders", token, body={"cartIds": [cart_id], "addressId": 4, "remark": "phase3-verify", "requestId": rid})
if not (r or {}).get("data"):
    OUT.append(f"order fail: {json.dumps(r, ensure_ascii=False)[:400]}")
    raise SystemExit("\n".join(OUT))
order_id = r["data"]["id"]
OUT.append(f"orderId={order_id} status={r['data']['status']} payable={r['data']['totalAmount']}")

# 发起支付
r = req("POST", f"/api/pay/{order_id}", token, body={"payMethod": "alipay"})
pay = (r or {}).get("data") or {}
payment_no, amount = pay.get("paymentNo"), str(pay.get("amount"))
OUT.append(f"pay -> paymentNo={payment_no} amount={amount}")

# mock 回调
sign = hashlib.sha256(f"{payment_no}&{amount}&mock-pay-key-2026".encode()).hexdigest()
resp = req("POST", "/api/pay/callback/alipay", form={"paymentNo": payment_no, "amount": amount, "mockSign": sign, "tradeNo": "MOCK" + payment_no})
OUT.append(f"callback -> {str(resp)[:200]}")

# 查订单状态
time.sleep(1)
r = req("GET", f"/api/orders/{order_id}", token)
d = (r or {}).get("data") or {}
OUT.append(f"order final status={d.get('status')} statusText={d.get('statusText')}")

# 管理端订单列表（广播 JOIN）
r = req("GET", "/api/admin/orders?page=1&pageSize=5", token)
data = (r or {}).get("data") or {}
items = data.get("list") or data.get("records") or []
OUT.append(f"adminOrderList total={data.get('total')} first5={[x.get('orderNo') for x in items[:5]]}")

print("\n".join(OUT))
