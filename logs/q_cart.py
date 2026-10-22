# -*- coding: utf-8 -*-
import json, urllib.request, urllib.error

BASE = 'http://localhost:8080'

def req(method, path, body=None, token=None):
    headers = {'Content-Type': 'application/json'}
    if token:
        headers['Authorization'] = 'Bearer ' + token
    r = urllib.request.Request(BASE + path, method=method, headers=headers,
                               data=json.dumps(body).encode() if body is not None else None)
    try:
        with urllib.request.urlopen(r, timeout=20) as resp:
            return resp.status, json.loads(resp.read().decode())
    except urllib.error.HTTPError as e:
        try:
            return e.code, json.loads(e.read().decode())
        except Exception:
            return e.code, {'message': 'raw http ' + str(e.code)}

utoken = open(r"D:\myproject\here16\here16-springboot\logs\token6.txt", encoding="utf-8-sig").read().strip()

for i in range(3):
    st, r = req('POST', '/api/cart', token=utoken, body={'productId': 2, 'skuId': 4, 'quantity': 1})
    print('POST #%d status=%s code=%s msg=%s data=%s' % (i, st, r.get('code'), r.get('message'), json.dumps(r.get('data'), ensure_ascii=False)[:150]), flush=True)
    st, r = req('GET', '/api/cart', token=utoken)
    items = (r.get('data') or {}).get('items') or []
    print('  GET  #%d status=%s code=%s n=%d qty=%s' % (i, st, r.get('code'), len(items), items[0].get('quantity') if items else '-'), flush=True)
