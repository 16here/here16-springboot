# -*- coding: utf-8 -*-
"""发货修复端到端验证 v3：真实前端路径（cartIds 下单）"""
import json, subprocess, urllib.request, urllib.error, uuid, time

BASE = 'http://localhost:8080'
DOCKER = r"C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"

def req(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    r = urllib.request.Request(BASE + path, method=method, headers=headers,
                               data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(r, timeout=25) as resp:
            return json.loads(resp.read().decode()), resp.status
    except urllib.error.HTTPError as e:
        try:
            return json.loads(e.read().decode()), e.code
        except Exception:
            return {'message': 'raw http ' + str(e.code)}, e.code

# ---- admin 登录 ----
cap, _ = req('GET', '/api/auth/captcha')
cid = ((cap or {}).get('data') or {}).get('captchaId')
out = subprocess.run([DOCKER, 'exec', 'redis', 'redis-cli', 'GET', 'captcha:' + cid],
                     capture_output=True, text=True, timeout=10)
login, st = req('POST', '/api/auth/login', {'account': 'admin', 'password': '123456',
                'captchaId': cid, 'captchaCode': (out.stdout or '').strip()})
atoken = (login.get('data') or {}).get('token')
print('[1] admin login http=%s ok=%s' % (st, bool(atoken)), flush=True)

# ---- 管理端列表含 85/86 ----
r, st = req('GET', '/api/admin/orders?page=1&pageSize=100', token=atoken)
dd = r.get('data') or {}
ids = [o.get('id') for o in (dd.get('list') or [])]
print('[2] admin list total=%s has85=%s has86=%s' % (dd.get('total'), 85 in ids, 86 in ids), flush=True)

# ---- 85 发货：能查到订单（报状态错误）而非不存在 ----
r, st = req('POST', '/api/admin/shipping/ship', token=atoken,
            body={'orderId': 85, 'shippingCompany': '顺丰', 'trackingNo': 'SF1234567890'})
print('[3] ship 85: http=%s code=%s msg=%s' % (st, r.get('code'), r.get('message')), flush=True)

# ---- 用户真实链路：加购 → cartIds 下单 → 支付 → 发货 → 查物流 ----
utoken = open(r"D:\myproject\here16\here16-springboot\logs\token6.txt", encoding="utf-8-sig").read().strip()
r, st = req('POST', '/api/cart', token=utoken, body={'productId': 2, 'skuId': 4, 'quantity': 1})
print('[4] add cart http=%s code=%s' % (st, r.get('code')), flush=True)
time.sleep(0.5)
r, st = req('GET', '/api/cart', token=utoken)
items = (r.get('data') or {}).get('items') or []
print('[5] cart http=%s n=%d' % (st, len(items)), flush=True)
if not items:
    raise SystemExit('cart empty, abort')
cart_id = items[0].get('cartId')

r, st = req('POST', '/api/orders', token=utoken, body={
    'addressId': 5, 'remark': 'ship-fix-verify', 'requestId': str(uuid.uuid4()),
    'cartIds': [cart_id]})
oid = (r.get('data') or {}).get('id')
print('[6] create order http=%s code=%s msg=%s id=%s' % (st, r.get('code'), r.get('message'), oid), flush=True)
if not oid:
    raise SystemExit('order create failed')

r, st = req('POST', '/api/pay/' + str(oid), token=utoken, body={'payMethod': 'alipay'})
pd = r.get('data') or {}
pay_no = pd.get('paymentNo')
print('[7] pay http=%s code=%s payNo=%s' % (st, r.get('code'), pay_no), flush=True)
if pay_no:
    import urllib.parse, hashlib
    amount_str = str(pd.get('amount'))
    sign = hashlib.sha256(('{}&{}&mock-pay-key-2026'.format(pay_no, amount_str)).encode()).hexdigest()
    data = urllib.parse.urlencode({'paymentNo': pay_no, 'amount': amount_str, 'mockSign': sign}).encode()
    try:
        with urllib.request.urlopen(urllib.request.Request(BASE + '/api/pay/callback/alipay', data=data,
                                                           headers={'Content-Type': 'application/x-www-form-urlencoded'}), timeout=25) as resp:
            body = resp.read().decode(errors='replace')
            print('[8] mock callback http=%s body=%s' % (resp.status, body), flush=True)
    except urllib.error.HTTPError as e:
        print('[8] mock callback http=%s body=%s' % (e.code, e.read().decode(errors='replace')), flush=True)
    time.sleep(1)

# ---- 管理端发货（ShippingService.ship 修复路径） ----
r, st = req('POST', '/api/admin/shipping/ship', token=atoken,
            body={'orderId': oid, 'shippingCompany': '顺丰', 'trackingNo': 'SF-verify-001'})
print('[9] admin ship http=%s code=%s msg=%s' % (st, r.get('code'), r.get('message')), flush=True)

# ---- 用户查物流 ----
r, st = req('GET', '/api/shipping/' + str(oid), token=utoken)
sd = r.get('data') or {}
print('[10] user shipping http=%s code=%s trackNo=%s traces=%d' %
      (st, r.get('code'), sd.get('trackingNo'), len(sd.get('traces') or [])), flush=True)
