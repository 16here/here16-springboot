# -*- coding: utf-8 -*-
"""给补的运单同步 mock 轨迹并验证"""
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

for i in range(3):
    r, st = req('POST', '/api/admin/shipping/28/sync', token=atoken)
    print('sync #%d http=%s code=%s msg=%s' % (i + 1, st, r.get('code'), r.get('message')), flush=True)

r, st = req('GET', '/api/admin/shipping/28', token=atoken)
d = r.get('data') or {}
traces = d.get('traces') or []
print('detail after sync: status=%s statusText=%s traces=%d' % (d.get('status'), d.get('statusText'), len(traces)), flush=True)
for t in traces:
    print('  -', t.get('trackTime'), '|', t.get('trackInfo'), flush=True)
