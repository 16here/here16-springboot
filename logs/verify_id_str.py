# -*- coding: utf-8 -*-
"""验证雪花 ID 字符串化：列表/详情 id 是字符串；字符串 id 调支付/详情不再 404"""
import json, time, urllib.request, urllib.error

BASE = "http://localhost:8080"
OUT = []

def req(method, path, token=None, body=None):
    url = BASE + path
    data = None
    headers = {}
    if token:
        headers["Authorization"] = "Bearer " + token
    if body is not None:
        data = json.dumps(body).encode()
        headers["Content-Type"] = "application/json"
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
        try:
            return json.loads(raw)
        except Exception:
            return raw

token = open(r"D:\myproject\here16\here16-springboot\logs\token6.txt", encoding="utf-8-sig").read().strip()

# 1. 订单列表：id 应为字符串
r = req("GET", "/api/orders?page=1&pageSize=3", token)
items = (r or {}).get("data", {}).get("list") or (r or {}).get("data", {}).get("records") or []
for o in items[:2]:
    OUT.append(f"list id={o.get('id')!r} type={type(o.get('id')).__name__} orderNo={o.get('orderNo')}")

# 2. 取一个订单 id（字符串）→ 详情（字符串传参）
if items:
    oid = items[0]["id"]
    OUT.append(f"detail by str id={oid!r}")
    r = req("GET", f"/api/orders/{oid}", token)
    d = (r or {}).get("data") or {}
    OUT.append(f"detail code={r.get('code')} status={d.get('status')} id={d.get('id')!r} type={type(d.get('id')).__name__}")

    # 3. 发起支付（字符串 orderId 路径参数）
    r = req("POST", f"/api/pay/{oid}", token, body={"payMethod": "alipay"})
    d = (r or {}).get("data") or {}
    OUT.append(f"pay code={r.get('code')} orderId={d.get('orderId')!r} type={type(d.get('orderId')).__name__} paymentNo={d.get('paymentNo')}")

print("\n".join(OUT))
