# -*- coding: utf-8 -*-
"""全链路验证：用户端+管理端订单详情、支付、下单跳转核心链路"""
import json, urllib.request, urllib.error

BASE = "http://localhost:8080"

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

def safe(obj):
    return (obj or {}).get("data") or {}

utoken = open(r"D:\myproject\here16\here16-springboot\logs\token6.txt", encoding="utf-8-sig").read().strip()

# 1. 用户订单列表：找待付款订单（status=0）
r = req("GET", "/api/orders?page=1&pageSize=20", utoken)
orders = (r.get("data") or {}).get("list") or []
unpaid = next((o for o in orders if o.get("status") == 0), None)
print(f"[1] 用户订单列表 total={r.get('data',{}).get('total')}, 待付款订单={'有' if unpaid else '无'} id={unpaid.get('id') if unpaid else '-'} type={type(unpaid.get('id')).__name__ if unpaid else '-'}")

# 2. 待付款订单 → 发起支付（字符串 orderId 路径参数）
if unpaid:
    oid = unpaid["id"]
    r = req("POST", f"/api/pay/{oid}", utoken, body={"payMethod": "alipay"})
    d = safe(r)
    print(f"[2] 支付 code={r.get('code')} orderId={d.get('orderId')} type={type(d.get('orderId')).__name__} paymentNo={d.get('paymentNo')}")

# 3. 用户订单详情（任意订单，字符串 id）
if orders:
    oid = orders[0]["id"]
    r = req("GET", f"/api/orders/{oid}", utoken)
    d = safe(r)
    print(f"[3] 用户详情 code={r.get('code')} status={d.get('status')} id={d.get('id')} type={type(d.get('id')).__name__}")

# 4. 管理端：登录拿 token → 订单列表 → 详情
atoken = None
r = req("POST", "/api/auth/admin/login", body={"username": "admin", "password": "123456", "captchaId": "", "captchaCode": ""})
if r.get("code") != 200:
    # 尝试带验证码的登录方式（从历史 verify_admin 看，admin 登录可能需要验证码；先直接试）
    r = req("POST", "/api/auth/admin/login", body={"username": "admin", "password": "123456"})
d = safe(r)
atoken = d.get("token") or (r.get("data") or {}).get("accessToken")
print(f"[4] admin 登录 code={r.get('code')} token={'有' if atoken else '无'}")

if atoken:
    r = req("GET", "/api/admin/orders?page=1&pageSize=3", atoken)
    aorders = (r.get("data") or {}).get("list") or []
    print(f"[5] admin 订单列表 code={r.get('code')} 条数={len(aorders)} 首单 id={aorders[0].get('id') if aorders else '-'} type={type(aorders[0].get('id')).__name__ if aorders else '-'}")
    if aorders:
        oid = aorders[0]["id"]
        r = req("GET", f"/api/admin/orders/{oid}", atoken)
        d = safe(r)
        print(f"[6] admin 详情 code={r.get('code')} status={d.get('status')} id={d.get('id')} msg={r.get('message')}")
