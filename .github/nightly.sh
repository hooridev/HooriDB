#!/usr/bin/env bash
# What nightly.yml asks of GitHub's releases, as a script of its own so that it can be tried
# against a stub of gh. `prepare` decides whether and as what to build, `publish` releases the
# assets in the current folder and keeps the newest nightlies.
set -euo pipefail

nightly='^v[0-9]+\.[0-9]+\.[0-9]+-nightly\.[0-9]{12}$'
keep=14

# Into a variable first: a failure in a loop's list or a test would pass unnoticed.
releases() {
  gh release list --limit 200 --json tagName,isDraft,isPrerelease
}

# The published nightlies' tags of the releases on stdin, oldest first by their timestamp,
# whatever their base version.
nightlies() {
  jq -r --arg re "$nightly" \
    'map(select((.isDraft | not) and (.tagName | test($re)))) | sort_by(.tagName | split(".") | last) | .[].tagName'
}

# Takes PROJECT_VERSION (the default branch's, X.Y.Z-SNAPSHOT) and GITHUB_SHA; writes build,
# version and previous (the newest nightly's tag, if any) to GITHUB_OUTPUT.
prepare() {
  local base=${PROJECT_VERSION%-SNAPSHOT}
  if [ "$base" = "$PROJECT_VERSION" ] || ! [[ $base =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]]; then
    echo "::error::The default branch's version $PROJECT_VERSION is not X.Y.Z-SNAPSHOT."
    exit 1
  fi
  local list stables stable previous commit
  list=$(releases)
  stables=$(jq -r 'map(select((.isDraft or .isPrerelease) | not)) | .[].tagName' <<< "$list" |
    sed -nE 's/^v([0-9]+\.[0-9]+\.[0-9]+)$/\1/p')
  for stable in $stables; do
    if [ "$(printf '%s\n' "$base" "$stable" | sort -V | tail -n 1)" = "$stable" ]; then
      echo "::error::$stable is released, so the default branch's version $PROJECT_VERSION must be raised: updates would never offer nightlies of $base."
      exit 1
    fi
  done
  previous=$(nightlies <<< "$list" | tail -n 1)
  if [ -n "$previous" ]; then
    commit=$(gh api "repos/$GH_REPO/commits/$previous" --jq .sha)
    if [ "$commit" = "$GITHUB_SHA" ]; then
      echo "::notice::$previous is of $GITHUB_SHA already; nothing to build."
      echo build=false >> "$GITHUB_OUTPUT"
      return
    fi
  fi
  {
    echo build=true
    echo "version=$base-nightly.$(date -u +%Y%m%d%H%M)"
    echo "previous=$previous"
  } >> "$GITHUB_OUTPUT"
}

# Takes VERSION, PREVIOUS and GITHUB_SHA, in the folder of the assets.
publish() {
  sha256sum HooriDB-* > SHA256SUMS
  local list drafts tag old
  list=$(releases)
  # A draft has no tag until it is published, so a failed run leaves its draft behind.
  drafts=$(jq -r --arg re "$nightly" 'map(select(.isDraft and (.tagName | test($re)))) | .[].tagName' <<< "$list")
  for tag in $drafts; do
    gh release delete "$tag" --yes
  done
  local notes=()
  if [ -n "$PREVIOUS" ]; then notes=(--notes-start-tag "$PREVIOUS"); fi
  tag=v$VERSION
  gh release create "$tag" --draft --prerelease --latest=false --target "$GITHUB_SHA" \
    --title "HooriDB $VERSION" --generate-notes "${notes[@]}" HooriDB-* SHA256SUMS
  gh release edit "$tag" --draft=false --prerelease --latest=false
  list=$(releases)
  old=$(nightlies <<< "$list" | head -n -"$keep")
  for tag in $old; do
    gh release delete "$tag" --cleanup-tag --yes
  done
}

"$@"
