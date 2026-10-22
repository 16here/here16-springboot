# -*- coding: utf-8 -*-
"""雪花ID修复全链路验证：管理端登录/详情 + 用户下单→支付链路"""
import json, subprocess, urllib.request, urllib.error, urllib.parse
from urllib.parse import urlencode

BASE = "http://localhost:8080"
OUT = []
DOCKER = r"C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"

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

# ===== 管理端 =====
r = req("GET", "/api/auth/captcha")
d = (r or {}).get("data") or {}
cid = d.get("captchaId") or d.get("id")
captcha = d.get("captchaText") or d.get("code")
if not captcha:
    try:
        out = subprocess.run([DOCKER, "exec", "redis", "redis-cli", "GET", f"captcha:{cid}"],
                             capture_output=True, text=True, timeout=10)
        captcha = (out.stdout or "").strip()
    except Exception as e:
        OUT.append(f"redis captcha err: {e}")
OUT.append(f"[1] captcha cid={cid} text={captcha}")
r = req("POST", "/api/auth/login", body={"account": "admin", "password": "123456",
                                         "captchaId": cid, "captchaCode": captcha})
atoken = ((r or {}).get("data") or {}).get("token")
OUT.append(f"[2] admin login code={r.get('code') if isinstance(r, dict) else r} token={'Y' if atoken else 'N'}")
if atoken:
    r = req("GET", "/api/admin/orders?page=1&pageSize=3", atoken)
    aorders = (r.get("data") or {}).get("list") or []
    OUT.append(f"[3] admin list code={r.get('code')} n={len(aorders)} first_id={aorders[0].get('id') if aorders else '-'} type={type(aorders[0].get('id')).__name__ if aorders else '-'}")
    if aorders:
        oid = aorders[0]["id"]
        r = req("GET", f"/api/admin/orders/{oid}", atoken)
        dd = (r.get("data")) or {}
        OUT.append(f"[4] admin detail code={r.get('code')} status={dd.get('status')} id={dd.get('id')} msg={r.get('message')}")

# ===== 用户下单链路 =====
utoken = open(r"D:\myproject\here16\here16-springboot\logs\token6.txt", encoding="utf-8-sig").read().strip()
r = req("GET", "/api/cart", utoken)
cart = (r.get("data") or {})
items = cart.get("items") or cart.get("list") or []
OUT.append(f"[5] user cart n={len(items)}")
if not items:
    # 加购一件商品
    r = req("POST", "/api/cart", utoken, body={"productId": 2, "skuId": 4, "quantity": 1})
    OUT.append(f"[5b] add cart code={r.get('code')} msg={r.get('message')}")

# 下单（幂等键）
import uuid
r = req("POST", "/api/orders", utoken, body={
    "addressId": 1, "remark": "snowflake-verify", "idempotencyKey": str(uuid.uuid4()),
    "items": [{"productId": 2, "skuId": 4, "quantity": 1}]
})
d = (r.get("data")) or {}
oid = d.get("id")
OUT.append(f"[6] create order code={r.get('code')} id={oid} type={type(oid).__name__} status={d.get('status')} msg={r.get('message')}")

if oid:
    # 用返回的 id（字符串）直接查详情 + 发起支付（模拟前端行为）
    r = req("GET", f"/api/orders/{oid}", utoken)
    dd = (r.get("data")) or {}
    OUT.append(f"[7] order detail by str id code={r.get('code')} status={dd.get('status')} id={dd.get('id')} type={type(dd.get('id')).__name__}")
    r = req("POST", f"/api/pay/{oid}", utoken, body={"payMethod": "alipay"})
    pd = (r.get("data")) or {}
    OUT.append(f"[8] pay code={r.get('code')} orderId={pd.get('orderId')} type={type(pd.get('orderId')).__name__} payNo={pd.get('paymentNo')}")

print("\n".join(OUT))
