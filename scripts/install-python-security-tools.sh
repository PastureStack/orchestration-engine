#!/usr/bin/env bash
# Keep the Ubuntu interpreter ABI; update both live pip and new-environment seeds.
set -euo pipefail
pip_wheel=/tmp/pip-security-dist/pip-26.2.2+pasturestack.1-py3-none-any.whl
certifi_wheel=/tmp/certifi-2026.10.19-py3-none-any.whl
test -s "$certifi_wheel"
python_packages=(libpython3.14-minimal libpython3.14-stdlib python3.14-minimal python3.14 python3.14-venv)
python_debs=()
for package in "${python_packages[@]}"; do
    file="/tmp/${package}.deb"
    test "$(dpkg-deb -f "$file" Package)" = "$package"
    test "$(dpkg-deb -f "$file" Version)" = 3.14.4-1ubuntu0.2
    test "$(dpkg-deb -f "$file" Architecture)" = amd64
    python_debs+=("$file")
done
dpkg -i "${python_debs[@]}"
for package in "${python_packages[@]}"; do
    test "$(dpkg-query -W -f='${Version}' "$package")" = 3.14.4-1ubuntu0.2
done
python3 /usr/local/lib/pip-security-overlay/build-pip-overlay.py \
    --output /tmp/pip-security-dist --work /tmp/pip-security-build
echo 'be5f63d537bbe611dc422fabb6740a44b5997888b592888df5091727f023802a  /tmp/pip-security-dist/pip-26.2.2+pasturestack.1-py3-none-any.whl' | sha256sum -c -
mkdir -p /licenses/pip-security
cp /tmp/pip-security-dist/* /licenses/pip-security/
apt-get purge -y python3-pip
PYTHONPATH="$pip_wheel" python3 -m pip install --break-system-packages --no-index --no-deps \
    "$pip_wheel" "$certifi_wheel"
find /usr/share/python-wheels -maxdepth 1 -type f -name 'pip-*.whl' -delete
install -m 0644 "$pip_wheel" /usr/share/python-wheels/
python3 -c 'import pip, certifi, pip._vendor.certifi as vendor; assert pip.__version__ == "26.2.2+pasturestack.1"; assert certifi.__version__ == "2026.10.19"; assert vendor.__version__ == "2026.10.19"'
bash /usr/local/lib/pip-security-overlay/verify-pip-runtime.sh --virtualenv /usr/share/python-wheels
printf 'PYTHON_SECURITY_TOOLS_OK pip=26.2.2+pasturestack.1 certifi=2026.10.19 urllib3=2.8.0 ensurepip=26.2.2+pasturestack.1 virtualenv=26.2.2+pasturestack.1\n'
