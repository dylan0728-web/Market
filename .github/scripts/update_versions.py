import json
import os
import pathlib
import shutil
import subprocess
import tempfile
import urllib.request

REPO = "dylan0728-web/Market"
TAG = "market-update"
VERSION_FILE = pathlib.Path("versions.json")
API_URL = f"https://api.github.com/repos/{REPO}/releases/tags/{TAG}"

def request(url, token=None, accept=None):
    headers = {"User-Agent": "HY-Market-Version-Updater"}
    if token:
        headers["Authorization"] = f"Bearer {token}"
    if accept:
        headers["Accept"] = accept
    req = urllib.request.Request(url, headers=headers)
    with urllib.request.urlopen(req, timeout=120) as response:
        return response.read()

def find_apkanalyzer():
    found = shutil.which("apkanalyzer")
    if found:
        return found

    sdk = os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT")
    if sdk:
        candidate = pathlib.Path(sdk) / "cmdline-tools" / "latest" / "bin" / "apkanalyzer"
        if candidate.exists():
            return str(candidate)

    raise RuntimeError("找不到 Android SDK apkanalyzer")

def main():
    token = os.environ.get("GITHUB_TOKEN")
    if not token:
        raise RuntimeError("缺少 GITHUB_TOKEN")

    apkanalyzer = find_apkanalyzer()

    release_data = json.loads(request(
        API_URL, token, "application/vnd.github+json"
    ).decode("utf-8"))

    old_data = {}
    if VERSION_FILE.exists():
        old_data = json.loads(VERSION_FILE.read_text(encoding="utf-8"))

    versions = old_data.get("versions", {})
    if not isinstance(versions, dict):
        versions = {}

    # 移除空白版本，並排除市場更新 APK
    versions = {
        name: str(version).strip()
        for name, version in versions.items()
        if name.lower() != "hy.apk"
        and isinstance(version, (str, int, float))
        and str(version).strip()
    }

    assets = release_data.get("assets", [])
    apk_assets = [
        asset for asset in assets
        if asset.get("name", "").lower().endswith(".apk")
        and asset.get("name", "").lower() != "hy.apk"
    ]

    print(f"找到 {len(apk_assets)} 個 APK 資產")

    for asset in apk_assets:
        name = asset["name"]
        url = asset.get("browser_download_url")
        if not url:
            print(f"略過：{name}，沒有下載網址")
            continue

        try:
            data = request(url)
            with tempfile.NamedTemporaryFile(suffix=".apk", delete=False) as temp:
                temp.write(data)
                apk_path = temp.name

            try:
                result = subprocess.run(
                    [apkanalyzer, "manifest", "version-name", apk_path],
                    capture_output=True,
                    text=True,
                    timeout=90
                )
                version = result.stdout.strip()

                if result.returncode != 0 or not version:
                    print(f"讀取失敗，保留舊版本：{name}")
                    continue

                versions[name] = version
                print(f"更新：{name} = {version}")
            finally:
                pathlib.Path(apk_path).unlink(missing_ok=True)

        except Exception as error:
            print(f"處理失敗，保留舊版本：{name}：{error}")

    output = {"versions": versions}
    VERSION_FILE.write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8"
    )
    print(f"完成，versions.json 共 {len(versions)} 筆有效版本")

if __name__ == "__main__":
    main()