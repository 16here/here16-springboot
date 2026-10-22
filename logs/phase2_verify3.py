# Phase2 验证：下单 -> mock支付回调(链路②) -> 行为落库核对 -> 第二单等待延迟关单(链路①)
import json, subprocess, time, sys, hashlib
import requests, pymysql

BASE = "http://localhost:8080"

def redis_get(key):
    r = subprocess.run(["docker", "exec", "redis", "redis-cli", "GET", key], capture_output=True, text=True)
    return r.stdout.strip()

def ok(j):
    return j.get("code") in (0, 200)

def login(account, password):
    cid = requests.get(BASE + "/api/auth/captcha").json()["data"]["captchaId"]
    code = redis_get(f"captcha:{cid}")
    j = requests.post(BASE + "/api/auth/login", json={"account": account, "password": password, "captchaId": cid, "captchaCode": code}).json()
    assert ok(j), f"login fail {j}"
    return j["data"]

def db():
    return pymysql.connect(host="localhost", port=3306, user="root", password="719892144Qq", database="mall_db", charset="utf8mb4")

def main():
    print("== Step0 登录 ==", flush=True)
    data = login("user6", "user6123456")
    H = {"Authorization": f"Bearer {data.get('token') or data.get('accessToken')}"}

    print("== Step1 加购 + 查看购物车与地址 ==", flush=True)
    # 加购商品 1（sku id=1，price 199）
    add = requests.post(BASE + "/api/cart", json={"productId": 1, "skuId": 1, "quantity": 1}, headers=H)
    print(f"  加购 resp={add.status_code} body={add.text[:120]}", flush=True)
    time.sleep(1)
    cart = requests.get(BASE + "/api/cart", headers=H).json()
    items = cart.get("data", {}).get("items") or []
    print(f"  cart items={len(items)} 第一项={items[0] if items else None}", flush=True)
    addr = requests.get(BASE + "/api/addresses", headers=H).json()
    addrs = addr.get("data") if isinstance(addr.get("data"), list) else (addr.get("data", {}).get("list") or [])
    print(f"  addresses={len(addrs)} 第一项id={addrs[0].get('id') if addrs else None}", flush=True)
    if not items or not addrs:
        print("  缺购物车项或地址，中止", flush=True)
        return
    cart_id = items[0].get("id") or items[0].get("cartId")
    addr_id = addrs[0].get("id")
    print(f"  cartId={cart_id} addrId={addr_id}", flush=True)

    print("== Step2 下单（幂等键 requestId） ==", flush=True)
    rid = f"ph2-{int(time.time())}"
    body = {"cartIds": [cart_id], "addressId": addr_id, "remark": "phase2-mq-verify", "requestId": rid}
    r = requests.post(BASE + "/api/orders", json=body, headers=H)
    j = r.json()
    print(f"  下单 resp={r.status_code} code={j.get('code')} msg={j.get('message')}", flush=True)
    if not ok(j):
        print(f"  body={j}", flush=True)
        return
    oid = j["data"].get("id") or j["data"].get("orderId")
    ono = j["data"].get("orderNo")
    print(f"  orderId={oid} orderNo={ono}", flush=True)

    print("== Step3 mock 支付回调（链路②） ==", flush=True)
    # 查 payment 配置的 mockKey 与验签方式（SHA-256(paymentNo&amount&mockKey)）
    pay = requests.post(BASE + f"/api/pay/{oid}", json={"method": "ALIPAY"}, headers=H)
    pj = pay.json()
    print(f"  createPayment resp={pay.status_code} code={pj.get('code')} body={str(pj)[:220]}", flush=True)
    payment_no = None
    if ok(pj):
        d = pj["data"]
        payment_no = d.get("paymentNo") or d.get("payNo") or d.get("orderNo")
    print(f"  paymentNo={payment_no}", flush=True)
    if payment_no:
        # mockKey 需要从 application.yml 读取
        import re
        yml = open(r"D:\myproject\here16\here16-springboot\src\main\resources\application.yml", encoding="utf-8").read()
        m = re.search(r"mock-key:\s*(\S+)", yml)
        mock_key = m.group(1) if m else None
        print(f"  mockKey={mock_key}", flush=True)
        amount = "199.0"
        if mock_key:
            raw = f"{payment_no}&{amount}&{mock_key}"
            sign = hashlib.sha256(raw.encode()).hexdigest()
            cb = {"paymentNo": payment_no, "amount": float(amount), "mockSign": sign}
            cr = requests.post(BASE + "/api/pay/callback/alipay", json=cb)
            print(f"  callback resp={cr.status_code} body={cr.text[:200]}", flush=True)
        time.sleep(4)
    else:
        print("  未拿到 paymentNo，改查订单支付状态", flush=True)

    print("== Step4 数据库核对（链路②+③） ==", flush=True)
    conn = db()
    cur = conn.cursor()
    cur.execute("SELECT order_no, status FROM orders WHERE id=%s", (oid,))
    print(f"  orders: {cur.fetchall()}", flush=True)
    cur.execute("SELECT payment_no, method, status FROM payments WHERE order_id=%s ORDER BY id DESC LIMIT 3", (oid,))
    print(f"  payments: {cur.fetchall()}", flush=True)
    cur.execute("SELECT behavior_type, COUNT(*) FROM user_behaviors WHERE user_id=%s GROUP BY behavior_type", (4,))
    print(f"  user_behaviors(user4): {cur.fetchall()}", flush=True)
    conn.close()
    print("== DONE ==", flush=True)

if __name__ == "__main__":
    main()
