#!/usr/bin/env bash
set -euo pipefail
cd "${1:-.}"
[ -d data ] || { echo 'ERROR: ./data is missing' >&2; exit 1; }
[ ! -L data ] && [ ! -L data/uploads ] || { echo 'ERROR: symlink not allowed' >&2; exit 1; }
if [ ! -e data/uploads ]; then
  install -d -o 10001 -g 10001 -m 0700 data/uploads
else
  [ -d data/uploads ] || { echo 'ERROR: uploads is not a directory' >&2; exit 1; }
  chown 10001:10001 data/uploads
  chmod 0700 data/uploads
fi
stat -c 'Upload directory: mode=%a owner=%u:%g path=%n' data/uploads
echo 'Existing uploaded files are unchanged.'
