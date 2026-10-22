# -*- coding: utf-8 -*-
"""登录 admin 并验证管理端订单列表（广播 JOIN）与对账接口"""
import json, time, urllib.request, urllib.error, urllib.parse
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
        with urllib.request.urlopen(r, timeout=20) as resp:
            raw = resp.read().decode()
            try:
                return json.loads(raw)
            except Exception:
                return raw
    except urllib.error.HTTPError as e:
        raw = e.read().decode()
        OUT.append(f"{method} {path} -> HTTP {e.code}: {raw[:200]}")
        try:
            return json.loads(raw)
        except Exception:
            return raw

# 1. 验证码
r = req("GET", "/api/auth/captcha")
d = (r or {}).get("data") or {}
cid = d.get("captchaId") or d.get("id")
captcha = d.get("captchaText") or d.get("code") or d.get("captchaCode")
OUT.append(f"captcha: cid={cid} text={captcha}")

# 2. 用 redis 取验证码（docker exec）
import subprocess
if not captcha:
    try:
        out = subprocess.run(
            [r"C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe",
             "exec", "redis", "redis-cli", "GET", f"captcha:{cid}"],
            capture_output=True, text=True, timeout=10)
        captcha = (out.stdout or "").strip()
        OUT.append(f"captcha from redis: {captcha}")
    except Exception as e:
        OUT.append(f"redis captcha error: {e}")

# 3. 登录 admin
r = req("POST", "/api/auth/login", body={"account": "admin", "password": "123456",
                                         "captchaId": cid, "captchaCode": captcha})
token = ((r or {}).get("data") or {}).get("token")
OUT.append(f"login admin -> code={r.get('code') if isinstance(r, dict) else r} token_len={len(token or '')}")
if not token:
    raise SystemExit("\n".join(OUT))

# 4. 管理端订单列表（广播 JOIN + 分页）
r = req("GET", "/api/admin/orders?page=1&pageSize=5", token)
data = (r or {}).get("data") or {}
items = data.get("list") or data.get("records") or []
OUT.append(f"adminOrderList total={data.get('total')} items={len(items)}")
for it in items[:3]:
    OUT.append(f"  order id={it.get('id')} no={it.get('orderNo')} status={it.get('status')} buyer={it.get('buyerName') or it.get('username')}")

# 5. 对账接口
r = req("POST", "/api/admin/reconciliation/run", token)
OUT.append(f"recon run -> {json.dumps(r, ensure_ascii=False)[:400]}")
r = req("GET", "/api/admin/reconciliation/latest", token)
OUT.append(f"recon latest -> {json.dumps(r, ensure_ascii=False)[:400]}")
r = req("GET", "/api/admin/reconciliation/logs?page=1&pageSize=5", token)
data = (r or {}).get("data") or {}
lst = data.get("list") or data.get("records") or []
OUT.append(f"recon logs total={data.get('total')} first={[(x.get('bizType'), x.get('diffCount'), x.get('status')) for x in lst[:3]]}")

print("\n".join(OUT))
