# -*- coding: utf-8 -*-
"""验证补运单：管理端运单详情 id=28（iQOO 订单）"""
import json, subprocess, urllib.request, urllib.error

BASE = 'http://localhost:8080'
DOCKER = r"C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"

def req(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    r = urllib.request.Request(BASE + path, method=method, headers=headers,
                               data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return json.loads(resp.read().decode()), resp.status
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode()), e.code
        except Exception:
            return {'message': 'raw http ' + str(e.code)}, e.code

cap, _ = req('GET', '/api/auth/captcha')
cid = ((cap or {}).get('data') or {}).get('captchaId')
out = subprocess.run([DOCKER, 'exec', 'redis', 'redis-cli', 'GET', 'captcha:' + cid],
                     capture_output=True, text=True, timeout=10)
login, st = req('POST', '/api/auth/login', {'account': 'admin', 'password': '123456',
                'captchaId': cid, 'captchaCode': (out.stdout or '').strip()})
atoken = (login.get('data') or {}).get('token')
print('admin login ok=%s' % bool(atoken), flush=True)

# 管理端运单详情（iQOO 订单补的运单 id=28）
r, st = req('GET', '/api/admin/shipping/28', token=atoken)
d = r.get('data') or {}
print('admin shipping detail http=%s code=%s orderId=%s trackNo=%s company=%s receiver=%s' % (
    st, r.get('code'), d.get('orderId'), d.get('trackingNo'), d.get('shippingCompany'), d.get('receiverName')), flush=True)

# 管理端运单列表
r, st = req('GET', '/api/admin/shipping?page=1&pageSize=10', token=atoken)
dd = r.get('data') or {}
print('admin shipping list http=%s total=%s' % (st, dd.get('total')), flush=True)
