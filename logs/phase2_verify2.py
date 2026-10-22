# Phase2 验证补充：双删重试 + 支付回调异步 + 行为落库核对
import json, subprocess, time, sys
import requests, pymysql

BASE = "http://localhost:8080"

def redis_get(key):
    r = subprocess.run(["docker", "exec", "redis", "redis-cli", "GET", key], capture_output=True, text=True)
    return r.stdout.strip()

def redis_keys(pattern):
    r = subprocess.run(["docker", "exec", "redis", "redis-cli", "KEYS", pattern], capture_output=True, text=True)
    return [l for l in r.stdout.splitlines() if l.strip()]

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
    print("== Step2b 双删（补 SKU 重试） ==", flush=True)
    adata = login("admin", "123456")
    AH = {"Authorization": f"Bearer {adata.get('token') or adata.get('accessToken')}"}
    # 先删除缓存再请求一次列表重新缓存
    requests.get(BASE + "/api/products?page=1&pageSize=5")
    keys = redis_keys("*product*")
    print(f"  update前 keys: {keys}", flush=True)
    upd = {"name": "红枣蜜 散装500g", "price": 199.0, "categoryId": 2, "description": "双删验证", "status": 1, "sku": "P255-TEST"}
    r = requests.put(BASE + "/api/admin/products/255", json=upd, headers=AH)
    print(f"  update resp={r.status_code} body={r.text[:150]}", flush=True)
    time.sleep(3)
    keys2 = redis_keys("*product*")
    print(f"  update后 keys: {keys2}", flush=True)
    left = [k for k in keys2 if "cache:product" in k]
    print(f"  双删结果: cache残留={left} （期望空/已删）", flush=True)

    print("== Step3b 链路② 支付回调 -> PURCHASE ==", flush=True)
    # 找一笔待付款订单：先取订单列表第一个待付款
    data = login("user6", "user6123456")
    H = {"Authorization": f"Bearer {data.get('token') or data.get('accessToken')}"}
    o = requests.get(BASE + "/api/orders?page=1&pageSize=10", headers=H).json()
    pend = [x for x in o["data"]["list"] if x.get("status") in ("PENDING_PAYMENT", "待付款", "UNPAID")]
    print(f"  订单总数={o['data']['total']} 待付款={len(pend)}", flush=True)
    if not pend:
        print("  无待付款订单，跳过支付回调（可先下单）", flush=True)
        return
    oid = pend[0]["id"]
    print(f"  使用订单 {oid} 状态={pend[0]['status']}", flush=True)
    # mock 回调（验签 SHA-256(paymentNo&amount&mockKey)，见 application.yml pay.mock-key）
    # 先构造 payment 请求拿到支付号
    pay = requests.post(BASE + f"/api/pay/{oid}", json={"method": "ALIPAY", "amount": 199.0}, headers=H)
    print(f"  createPayment resp={pay.status_code} body={pay.text[:200]}", flush=True)
    pj = pay.json()
    payment_no = None
    if pj.get("code") in (0, 200):
        payment_no = pj["data"].get("paymentNo") or pj["data"].get("orderNo")
    print(f"  paymentNo={payment_no}", flush=True)

    print("== Step4 行为落库核对（链路③） ==", flush=True)
    conn = db()
    cur = conn.cursor()
    cur.execute("SELECT behavior_type, COUNT(*) FROM user_behaviors WHERE user_id=%s GROUP BY behavior_type", (4,))
    rows = cur.fetchall()
    print(f"  user_behaviors(user_id=4): {rows}", flush=True)
    conn.close()

if __name__ == "__main__":
    main()
