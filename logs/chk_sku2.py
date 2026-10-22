import requests, json
BASE = "http://localhost:8080"
j = requests.get(BASE + "/api/products/255").json()
print(json.dumps(j.get("data", {}), ensure_ascii=False)[:1200])
