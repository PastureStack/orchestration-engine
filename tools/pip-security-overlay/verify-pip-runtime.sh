#!/usr/bin/env bash
# Run inside the finished Python image. Pass --virtualenv for Engine/compose.
set -Eeuo pipefail
root=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
expected=26.2.2+pasturestack.1
python3 -c 'import ensurepip,pip,pip._vendor.urllib3 as u; assert ensurepip.version()==pip.__version__=="26.2.2+pasturestack.1"; assert u.__version__=="2.8.0"; print("BASE_AND_ENSUREPIP_OK",ensurepip.version(),pip.__version__,u.__version__)'
python3 "$root/test-pip-overlay.py"
scratch=$(mktemp -d)
trap 'rm -rf "$scratch"' EXIT
python3 -m venv "$scratch/venv"
"$scratch/venv/bin/python" "$root/test-pip-overlay.py"
if [[ "${1:-}" = --virtualenv ]]; then
    seed=${2:?explicit patched wheel directory required}
    test -s "$seed/pip-$expected-py3-none-any.whl"
    python3 -m virtualenv --no-download --no-periodic-update \
        --app-data="$scratch/virtualenv-app-data" \
        --pip="$expected" --extra-search-dir="$seed" "$scratch/virtualenv"
    "$scratch/virtualenv/bin/python" "$root/test-pip-overlay.py"
fi
printf 'PIP_RUNTIME_SEED_PATHS_OK\n'
