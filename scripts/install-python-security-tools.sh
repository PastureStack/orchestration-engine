#!/usr/bin/env bash
# Keep the Ubuntu interpreter ABI; update both live pip and new-environment seeds.
set -euo pipefail
pip_wheel=/tmp/pip-26.2.2-py3-none-any.whl
certifi_wheel=/tmp/certifi-2026.10.19-py3-none-any.whl
test -s "$pip_wheel"
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
apt-get purge -y python3-pip
PYTHONPATH="$pip_wheel" python3 -m pip install --break-system-packages --no-index --no-deps \
    "$pip_wheel" "$certifi_wheel"
find /usr/share/python-wheels -maxdepth 1 -type f -name 'pip-*.whl' -delete
install -m 0644 "$pip_wheel" /usr/share/python-wheels/
python3 -c 'import pip, certifi, pip._vendor.certifi as vendor; assert pip.__version__ == "26.2.2"; assert certifi.__version__ == "2026.10.19"; assert vendor.__version__ == "2026.10.19"'
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
python3 -m venv "$scratch/ensurepip"
"$scratch/ensurepip/bin/python" -c 'import pip, pip._vendor.certifi as c; assert pip.__version__ == "26.2.2"; assert c.__version__ == "2026.10.19"'
python3 -m virtualenv --no-download --pip=26.2.2 --extra-search-dir=/usr/share/python-wheels "$scratch/virtualenv"
"$scratch/virtualenv/bin/python" -c 'import pip, pip._vendor.certifi as c; assert pip.__version__ == "26.2.2"; assert c.__version__ == "2026.10.19"'
printf 'PYTHON_SECURITY_TOOLS_OK pip=26.2.2 certifi=2026.10.19 ensurepip=26.2.2 virtualenv=26.2.2\n'
