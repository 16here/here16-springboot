# Phase2 验证 Step5：支付订单79(链路②) + 新单等待延迟关单(链路①)
import json, subprocess, time, hashlib, re
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
    data = login("user6", "user6123456")
    H = {"Authorization": f"Bearer {data.get('token') or data.get('accessToken')}"}

    print("== Step5a 支付订单79（链路② 支付回调） ==", flush=True)
    oid = 79
    pay = requests.post(BASE + f"/api/pay/{oid}", json={"payMethod": "ALIPAY"}, headers=H)
    pj = pay.json()
    print(f"  createPayment code={pj.get('code')} body={str(pj)[:300]}", flush=True)
    payment_no = None
    if ok(pj):
        d = pj["data"]
        payment_no = d.get("paymentNo") or d.get("payNo") or d.get("orderNo")
    print(f"  paymentNo={payment_no}", flush=True)
    if payment_no:
        yml = open(r"D:\myproject\here16\here16-springboot\src\main\resources\application.yml", encoding="utf-8").read()
        m = re.search(r"mock-key:\s*(\S+)", yml)
        mock_key = m.group(1) if m else None
        print(f"  mockKey={mock_key}", flush=True)
        if mock_key:
            raw = f"{payment_no}&199.0&{mock_key}"
            sign = hashlib.sha256(raw.encode()).hexdigest()
            cb = {"paymentNo": payment_no, "amount": 199.0, "mockSign": sign}
            cr = requests.post(BASE + "/api/pay/callback/alipay", json=cb)
            print(f"  callback resp={cr.status_code} body={cr.text[:250]}", flush=True)
            time.sleep(5)

    print("== Step5b 数据库核对 ==", flush=True)
    conn = db()
    cur = conn.cursor()
    cur.execute("SELECT order_no, status FROM orders WHERE id=%s", (oid,))
    print(f"  orders(79): {cur.fetchall()}", flush=True)
    cur.execute("SELECT payment_no, pay_method, status FROM payments WHERE order_id=%s ORDER BY id DESC LIMIT 2", (oid,))
    print(f"  payments(79): {cur.fetchall()}", flush=True)
    cur.execute("SELECT behavior_type, COUNT(*) FROM user_behaviors WHERE user_id=%s GROUP BY behavior_type", (4,))
    print(f"  user_behaviors(user4): {cur.fetchall()}", flush=True)
    conn.close()

    print("== Step5c 新订单 -> 等待延迟关单（链路①） ==", flush=True)
    # 加购商品2（sku4, price 259）
    add = requests.post(BASE + "/api/cart", json={"productId": 2, "skuId": 4, "quantity": 1}, headers=H)
    print(f"  加购2: {add.text[:80]}", flush=True)
    time.sleep(1)
    cart = requests.get(BASE + "/api/cart", headers=H).json()
    items = cart.get("data", {}).get("items") or []
    cid2 = items[-1].get("cartId")
    rid = f"ph2b-{int(time.time())}"
    r = requests.post(BASE + "/api/orders", json={"cartIds": [cid2], "addressId": 5, "remark": "delay-cancel-test", "requestId": rid}, headers=H)
    oj = r.json()
    print(f"  下单: code={oj.get('code')} data={oj.get('data')}", flush=True)
    if not ok(oj):
        print("  下单失败", flush=True)
        return
    oid2 = oj["data"].get("id") or oj["data"].get("orderId")
    print(f"  newOrderId={oid2} 等待 ~80s（timeout-minutes=1，延迟级别约1分钟）...", flush=True)
    time.sleep(80)
    conn = db()
    cur = conn.cursor()
    cur.execute("SELECT order_no, status, cancelled_at FROM orders WHERE id=%s", (oid2,))
    row = cur.fetchall()
    print(f"  orders({oid2}) after wait: {row}", flush=True)
    cur.execute("SELECT COUNT(*) FROM inventory_logs WHERE order_id=%s", (oid2,))
    print(f"  inventory_logs({oid2}): {cur.fetchone()}", flush=True)
    conn.close()
    print("  （status=0待付款/4已取消；已取消则延迟消息链路生效）", flush=True)

    print("== DONE ==", flush=True)

if __name__ == "__main__":
    main()
