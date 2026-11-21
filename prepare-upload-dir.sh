#!/usr/bin/env bash
set -euo pipefail
cd "${1:-.}"
[ -d data ] || { echo 'ERROR: ./data is missing; check the project directory' >&2; exit 1; }
[ ! -L data ] || { echo 'ERROR: ./data is a symlink' >&2; exit 1; }
[ ! -L data/uploads ] || { echo 'ERROR: ./data/uploads is a symlink' >&2; exit 1; }
if [ ! -e data/uploads ]; then
  install -d -o 10001 -g 10001 -m 0700 data/uploads
else
  [ -d data/uploads ] || { echo 'ERROR: ./data/uploads is not a directory' >&2; exit 1; }
  # Change the upload DIRECTORY only; never recursively touch existing data/files.
  chown 10001:10001 data/uploads
  chmod 0700 data/uploads
fi
stat -c 'Upload directory: mode=%a owner=%u:%g path=%n' data/uploads
printf '%s\n' 'Existing upload files were not modified.'
