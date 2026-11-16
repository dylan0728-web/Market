
import hashlib
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
    headers = {
        "User-Agent": "HY-Market-Version-Updater",
        "Cache-Control": "no-cache, no-store",
        "Pragma": "no-cache",
    }

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
        candidate = (
            pathlib.Path(sdk)
            / "cmdline-tools"
            / "latest"
            / "bin"
            / "apkanalyzer"
        )
        if candidate.exists():
            return str(candidate)

        for candidate in (pathlib.Path(sdk) / "cmdline-tools").glob(
            "*/bin/apkanalyzer"
        ):
            if candidate.exists():
                return str(candidate)

    raise RuntimeError("找不到 Android SDK apkanalyzer")


def main():
    token = os.environ.get("GITHUB_TOKEN")

    if not token:
        raise RuntimeError("缺少 GITHUB_TOKEN")

    apkanalyzer = find_apkanalyzer()

    print("正在讀取 market-update Release...")
    release_data = json.loads(
        request(
            API_URL,
            token,
            "application/vnd.github+json"
        ).decode("utf-8")
    )

    old_data = {}

    if VERSION_FILE.exists():
        old_data = json.loads(
            VERSION_FILE.read_text(encoding="utf-8")
        )

    versions = old_data.get("versions", {})

    if not isinstance(versions, dict):
        versions = {}

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
        asset_url = asset.get("url")

        if not asset_url:
            print(f"略過：{name}，沒有 API 下載網址")
            continue

        apk_path = None

        try:
            print(f"\n正在取得最新資產：{name}")
            print(f"Asset ID：{asset.get('id')}")
            print(f"上傳時間：{asset.get('updated_at')}")
            print(f"檔案大小：{asset.get('size')} bytes")

            data = request(
                asset_url,
                token,
                "application/octet-stream"
            )

            if not data.startswith(b"PK"):
                raise RuntimeError(
                    "下載內容不是有效的 APK ZIP 檔案"
                )

            actual_sha256 = hashlib.sha256(data).hexdigest()
            expected_digest = asset.get("digest")

            if expected_digest:
                if expected_digest.startswith("sha256:"):
                    expected_digest = expected_digest.split(":", 1)[1]

                if actual_sha256.lower() != expected_digest.lower():
                    raise RuntimeError(
                        "SHA256 不一致，拒絕更新版本紀錄"
                    )

            print(f"下載 SHA256：{actual_sha256}")

            with tempfile.NamedTemporaryFile(
                suffix=".apk",
                delete=False
            ) as temp:
                temp.write(data)
                apk_path = temp.name

            result = subprocess.run(
                [
                    apkanalyzer,
                    "manifest",
                    "version-name",
                    apk_path
                ],
                capture_output=True,
                text=True,
                timeout=90
            )

            version = result.stdout.strip()

            if result.returncode != 0 or not version:
                raise RuntimeError(
                    result.stderr.strip() or "無法讀取 APK 版本號"
                )

            versions[name] = version
            print(f"更新成功：{name} = {version}")

        except Exception as error:
            print(f"處理失敗，保留舊版本：{name}：{error}")

        finally:
            if apk_path and os.path.exists(apk_path):
                os.unlink(apk_path)

    output = {"versions": versions}

    VERSION_FILE.write_text(
        json.dumps(output, ensure_ascii=False, indent=2) + "\n",
        encoding="utf-8"
    )

    print(f"\n完成，versions.json 共 {len(versions)} 筆有效版本")


if __name__ == "__main__":
    main()
