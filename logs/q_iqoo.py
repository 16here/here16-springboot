# -*- coding: utf-8 -*-
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
        with urllib.request.urlopen(r, timeout=15) as resp:
            return json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        return json.loads(e.read().decode())

# 1) 验证码：captchaId + Redis 里的明文
cap = req('GET', '/api/auth/captcha')
d = (cap or {}).get('data') or {}
cid = d.get('captchaId') or d.get('id')
out = subprocess.run([DOCKER, 'exec', 'redis', 'redis-cli', 'GET', 'captcha:' + cid],
                     capture_output=True, text=True, timeout=10)
text = (out.stdout or '').strip()
print('captcha cid=' + str(cid) + ' text=' + str(text), flush=True)

login = req('POST', '/api/auth/login', {'account': 'admin', 'password': '123456', 'captchaId': cid, 'captchaCode': text})
print('login:', login.get('code'), login.get('message'), flush=True)
token = (login.get('data') or {}).get('token')
if not token:
    raise SystemExit('login failed')

# 2) 模拟前端修复后的行为：字符串订单 ID 直查详情 + 发起支付
detail = req('GET', '/api/orders/106377609566224384', token=token)
dd = detail.get('data') or {}
print('detail str-id:', detail.get('code'), detail.get('message'), 'id=' + str(dd.get('id')), 'status=' + str(dd.get('status')), flush=True)

pay = req('POST', '/api/pay/106377609566224384', {'payMethod': 'alipay'}, token=token)
pd = pay.get('data') or {}
print('pay str-id:', pay.get('code'), pay.get('message'), 'orderId=' + str(pd.get('orderId')), 'payNo=' + str(pd.get('payNo')), flush=True)
