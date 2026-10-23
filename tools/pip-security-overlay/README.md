# 共用 pip 安全重包

這是內部衍生版本 `26.2.2+pasturestack.1`，不是 PyPA 官方 pip 發布。
所有專案及映像的對外 release tag 仍使用原本的純數字規則。

來源是官方 pip `26.2.2` 的 commit
`b3b696fe2122f2339d782517559bfade2cb6b879`。使用上游 `vendoring==1.4.0`
及 pip 原有的三份 urllib3 patches，把官方 PyPI urllib3 改成 `2.8.0`。
不手改 urllib3 原始碼、不 debundle。其餘內嵌 library 的每一個檔案均須
保持原始 SHA-256；licenses、內嵌 CycloneDX SBOM 及 wheel RECORD 全部保留。

## 建置

Linux Python 3.12/3.14，amd64/arm64；所有工具與原始碼的 URL/SHA 在
`inputs.json`。工具的相依套件不會進入產物。

```sh
python3 build-pip-overlay.py --work /tmp/pip-build --output /tmp/pip-dist \
  --input-cache /tmp/pip-inputs
# 從完全相同的已驗證上游檔案重新離線建置：
python3 build-pip-overlay.py --work /tmp/pip-rebuild --output /tmp/pip-dist2 \
  --input-cache /tmp/pip-inputs --offline
```

下載後的 vendoring、安裝工具與 wheel 建置使用 `PIP_NO_INDEX=1`。
每次建置都做兩次 wheel 並比較位元組，驗證全部 RECORD hashes、無 native
extension、保留 license，以及只有 urllib3 library 有變更。
輸出為 wheel、`build-evidence.json`、`pip-vendor.cdx.json`、工具 hash lock
與來源 manifest。已在 Python 3.12 建出 wheel SHA-256：
`be5f63d537bbe611dc422fabb6740a44b5997888b592888df5091727f023802a`。

## 安裝與三種 seed 路徑

編譯 CPython 前，以 `install-ensurepip-wheel.py --stdlib <Python-source>/Lib
--wheel <wheel> --sha256 <sha>` 同步 `_PIP_VERSION` 與 `_bundled` wheel
檔名，再正常編譯/安裝；不能只改 site-packages。

Ubuntu 的 distro Python 使用 `/usr/share/python-wheels` 時，將該固定 wheel
放入這個 seed directory 並移除其中舊的 pip wheel（不要動其他 wheel）；
以此 wheel `--no-index --no-deps` 安裝 live pip。既有 Python ABI 不變。

完成映像內安裝後執行：

```sh
bash verify-pip-runtime.sh
# Engine/compose 已有 virtualenv 時，再驗第三條 seed 路徑：
bash verify-pip-runtime.sh --virtualenv /usr/share/python-wheels
```

此 gate 驗證 live pip、ensurepip.version、新 venv、選用的新 virtualenv
全部載入衍生 pip 與 urllib3 2.8.0，並重跑攻防、可信/不可信 TLS、CLI。
本機 Python 3.12 的 wheel/攻防/TLS/CLI 已通過；真正 3.14 的 seed 三路驗證
須由消費映像 CI 執行，不能把本機結果冒充為 3.14 PASS。

## 已驗證的漏洞界線

- CVE-2026-97689：pip `response_chunks` 使用 `raw.stream`，確實可達。
  測試在舊版對惡意 chunk-size/trailer 讀入 131072 bytes；新版在 65537 bytes
  即拒絕，正常資料與邊界內的 chunk extension 保持可用。
- CVE-2026-57585：上游 fix commit
  `2c56ddb5d0025ed481d962c0f5d62d19dec7476d` 只修 Cython/C 實作；本 wheel
  無 `.so/.pyd`，實際 `Unpacker` 為 `pip._vendor.msgpack.fallback`。
  不為消除套件名稱掃描結果而更動未受影響的 msgpack。
- setuptools 70.3.0 僅保留 `pkg_resources`；CVE-2025-47273 所需的
  `setuptools/package_index.py` 與 CVE-2026-59890 所需的
  `setuptools/command/egg_info.py` 均不存在。

`check-pip-vendor-slice.py` 會比對實際載入的全部 299 個 vendor 檔案與
固定 wheel，拒絕額外檔案、其他同版本完整 distribution。只有取得此證據後，
`filter-pip-vendor-findings.py` 才可對上述三個 CVE（含 msgpack GHSA 別名）
產生綁定該映像的 OpenVEX 與有效掃描檔。原始掃描必須另行保存；未知 CVE、
其他版本、非 SBOM 套件來源與 urllib3 finding 全部保留，不做泛用版本排除。
執行 `python3 test-pip-vex.py` 可驗證以上六組正反控制。

官方政策：https://pip.pypa.io/en/stable/development/vendoring-policy/
