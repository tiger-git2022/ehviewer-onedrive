#!/usr/bin/env bash
# Attaches update.json to a published release: the fields of the GitHub API's release object that the app reads
# (tag_name, body, html_url, target_commitish, assets[].name/browser_download_url). The app fetches it from
# /releases/latest/download/update.json or /releases/download/nightly/update.json, which, unlike api.github.com
# (60 requests an hour per IP without login), has no request limit.
set -euo pipefail
tag="$1"
gh api "repos/$GITHUB_REPOSITORY/releases/tags/$tag" --jq '{
  tag_name, body, html_url, target_commitish,
  assets: [.assets[] | select(.name | endswith(".apk")) | {name, browser_download_url}]
}' > update.json
cat update.json
gh release upload "$tag" update.json --clobber
