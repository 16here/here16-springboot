import subprocess
docker = r"C:\Users\liguanting\AppData\Local\Programs\DockerDesktop\resources\bin\docker.exe"
out = subprocess.run([docker, "exec", "redis", "redis-cli", "--scan", "--pattern", "captcha:*"],
                     capture_output=True, text=True, timeout=10)
keys = [k for k in (out.stdout or "").splitlines() if k]
print("KEYS:", keys)
res = {}
for k in keys:
    v = subprocess.run([docker, "exec", "redis", "redis-cli", "GET", k],
                       capture_output=True, text=True, timeout=10)
    res[k] = (v.stdout or "").strip()
print("VALUES:", res)
