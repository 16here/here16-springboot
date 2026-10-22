# Phase2 验证 Step6：新单 -> alipay 支付 -> mock回调(链路②)
import subprocess, time, hashlib, re
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

    # 加购商品1 -> 下单
    add = requests.post(BASE + "/api/cart", json={"productId": 1, "skuId": 1, "quantity": 1}, headers=H)
    time.sleep(1)
    cart = requests.get(BASE + "/api/cart", headers=H).json()
    items = cart.get("data", {}).get("items") or []
    cid = items[-1]["cartId"]
    rid = f"ph2c-{int(time.time())}"
    r = requests.post(BASE + "/api/orders", json={"cartIds": [cid], "addressId": 5, "remark": "pay-callback-test", "requestId": rid}, headers=H)
    oj = r.json()
    print(f"  下单: code={oj.get('code')} data={str(oj.get('data'))[:150]}", flush=True)
    if not ok(oj):
        print("  下单失败", flush=True)
        return
    oid = oj["data"].get("id") or oj["data"].get("orderId")
    print(f"  orderId={oid}", flush=True)

    pay = requests.post(BASE + f"/api/pay/{oid}", json={"payMethod": "alipay"}, headers=H)
    pj = pay.json()
    print(f"  createPayment: code={pj.get('code')} msg={pj.get('message')} data={str(pj.get('data'))[:220]}", flush=True)
    payment_no = None
    if ok(pj):
        d = pj["data"]
        payment_no = d.get("paymentNo") or d.get("payNo") or d.get("orderNo")
        amount = d.get("amount")
        server_sign = d.get("mockSign")
    print(f"  paymentNo={payment_no} amount={amount}", flush=True)
    if payment_no:
        yml = open(r"D:\myproject\here16\here16-springboot\src\main\resources\application.yml", encoding="utf-8").read()
        m = re.search(r"mock-key:\s*(\S+)", yml)
        mock_key = m.group(1)
        print(f"  mockKey={mock_key}", flush=True)
        raw = f"{payment_no}&199.00&{mock_key}"
        sign = hashlib.sha256(raw.encode()).hexdigest()
        print(f"  serverSign={server_sign}", flush=True)
        print(f"  mySign(199.00)={sign}", flush=True)
        cb = {"paymentNo": payment_no, "amount": "199.00", "mockSign": sign}
        cr = requests.post(BASE + "/api/pay/callback/alipay", data=cb)
        print(f"  callback: resp={cr.status_code} body={cr.text[:300]}", flush=True)
        time.sleep(6)

    conn = db()
    cur = conn.cursor()
    cur.execute("SELECT order_no, status, paid_at FROM orders WHERE id=%s", (oid,))
    print(f"  orders({oid}): {cur.fetchall()}  (status: 1=已付款)", flush=True)
    cur.execute("SELECT payment_no, pay_method, status, amount FROM payments WHERE order_id=%s ORDER BY id DESC LIMIT 2", (oid,))
    print(f"  payments({oid}): {cur.fetchall()}", flush=True)
    cur.execute("SELECT behavior_type, COUNT(*) FROM user_behaviors WHERE user_id=%s AND behavior_type='PURCHASE'", (4,))
    print(f"  PURCHASE count(user4): {cur.fetchall()}", flush=True)
    conn.close()
    print("== DONE ==", flush=True)

if __name__ == "__main__":
    main()
