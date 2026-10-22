# -*- coding: utf-8 -*-
"""下单→详情→支付 完整链路（cartId 字段）"""
import json, uuid, urllib.request, urllib.error

BASE = "http://localhost:8080"
OUT = []

def req(method, path, token=None, body=None):
    headers = {}
    if token: headers["Authorization"] = "Bearer " + token
    data = None
    if body is not None:
        data = json.dumps(body).encode(); headers["Content-Type"] = "application/json"
    r = urllib.request.Request(BASE + path, data=data, headers=headers, method=method)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())

utoken = open(r"D:\myproject\here16\here16-springboot\logs\token6.txt", encoding="utf-8-sig").read().strip()

# 0. 加购（确保购物车有货）
r = req("POST", "/api/cart", utoken, body={"productId": 2, "skuId": 4, "quantity": 1})
OUT.append(f"[0] add cart code={r.get('code')} msg={r.get('message')}")

# 1. 购物车
r = req("GET", "/api/cart", utoken)
data = (r.get("data") or {})
items = data.get("items") or data.get("list") or []
OUT.append(f"[1] cart n={len(items)}")
cart_ids = [it.get("cartId") for it in items if it.get("cartId")]
OUT.append(f"[1] cartIds={cart_ids}")

# 2. 下单
r = req("POST", "/api/orders", utoken, body={
    "addressId": 5, "remark": "snowflake-e2e", "requestId": str(uuid.uuid4()), "cartIds": cart_ids
})
d = (r.get("data")) or {}
oid = d.get("id")
OUT.append(f"[2] create code={r.get('code')} id={oid} type={type(oid).__name__} status={d.get('status')} msg={r.get('message')}")

if oid:
    # 3. 详情（字符串 id 直接查）
    r = req("GET", f"/api/orders/{oid}", utoken)
    dd = (r.get("data")) or {}
    OUT.append(f"[3] detail code={r.get('code')} status={dd.get('status')} id={dd.get('id')} type={type(dd.get('id')).__name__}")
    # 4. 发起支付
    r = req("POST", f"/api/pay/{oid}", utoken, body={"payMethod": "alipay"})
    pd = (r.get("data")) or {}
    OUT.append(f"[4] pay code={r.get('code')} orderId={pd.get('orderId')} type={type(pd.get('orderId')).__name__} payNo={pd.get('paymentNo')} mock={pd.get('mock')}")
    # 5. 支付状态
    if pd.get("paymentNo"):
        r = req("GET", f"/api/pay/status?paymentNo={pd.get('paymentNo')}", utoken)
        sd = (r.get("data")) or {}
        OUT.append(f"[5] paystatus code={r.get('code')} status={sd.get('status')} orderId={sd.get('orderId')} type={type(sd.get('orderId')).__name__}")

print("\n".join(OUT))
