#!/usr/bin/env bash
# Publishes a bundle of Shidur's webOS interface for installed shells to download (ADR-057, 0.4.0).
# Run by hand on the owner's Raspberry Pi, the only place the signing key is kept.
#
#   publish-bundle.sh keygen <file>        make the signing key pair once
#   publish-bundle.sh [--dry-run] [--run <id>]
#
# keygen writes the private key, as the line SHIDUR_WEBOS_SIGNING_KEY_B64=<PEM, base64>, to <file>
# (mode 600, refused if it exists) for the owner to add to his secrets file, and prints the public
# key as base64 for webos/shell/public-key.txt. Nothing secret is printed.
#
# Publishing:
#   1. takes the shidur-webos-bundle artifact of the latest green "webOS app" run on main (or of
#      run <id>), from GitHub's Actions API;
#   2. checks every file's size and SHA-512 against its bundle.json, the app id and that the
#      repository's webos/shell/public-key.txt at that commit is the signing key's public half;
#   3. reads the published manifest and refuses a version that is not newer;
#   4. signs bundle.json's exact bytes (Ed25519) and checks the signature;
#   5. uploads the three files, reads them back, then uploads the manifest and reads it back: the
#      manifest never names files that are not there yet.
#   --dry-run does all of it except the uploads.
#
# Settings, from the environment (never from the repository):
#   SHIDUR_WEBOS_SIGNING_KEY_B64    the private key (keygen's line)
#   SHIDUR_WEBOS_GITHUB_TOKEN       read-only GitHub token (Actions: read on the repository)
#   TIVICOPY_UPDATES_UPLOAD_TOKEN   the update host's upload token
#   SHIDUR_REPO                     optional, default idanck/TiviMate-copy
#   SHIDUR_UPDATE_HOST              optional, default https://updates.idanck.com
#   SHIDUR_GITHUB_API               optional, default https://api.github.com (the tests point both elsewhere)
set -euo pipefail

REPO="${SHIDUR_REPO:-idanck/TiviMate-copy}"
HOST="${SHIDUR_UPDATE_HOST:-https://updates.idanck.com}"
API="${SHIDUR_GITHUB_API:-https://api.github.com}"
APP_ID="com.idanck.shidur"
WORKFLOW="webos.yml"
ARTIFACT="shidur-webos-bundle"
FILES=(style.css polyfills.js index.js)
WORK=""

die() {
  echo "publish-bundle: $*" >&2
  exit 1
}

need() {
  for tool in "$@"; do
    command -v "${tool}" >/dev/null 2>&1 || die "needs ${tool}"
  done
}

keygen() {
  local out="${1:-}"
  [ -n "${out}" ] || die "usage: publish-bundle.sh keygen <file>"
  [ ! -e "${out}" ] || die "${out} exists: refusing to replace a key"
  need openssl base64
  local work
  work="$(mktemp -d)"
  chmod 700 "${work}"
  (
    umask 077
    openssl genpkey -algorithm ed25519 -out "${work}/key.pem"
    printf 'SHIDUR_WEBOS_SIGNING_KEY_B64=%s\n' "$(base64 -w0 "${work}/key.pem")" >"${out}"
  )
  chmod 600 "${out}"
  echo "private key written to ${out} (mode 600); add its line to the secrets file"
  echo "public key, for webos/shell/public-key.txt:"
  openssl pkey -in "${work}/key.pem" -pubout -outform DER | tail -c 32 | base64 -w0
  echo
  rm -rf "${work}"
}

github() {
  curl -fsSL -H "Authorization: Bearer ${SHIDUR_WEBOS_GITHUB_TOKEN}" \
    -H "Accept: application/vnd.github+json" -H "X-GitHub-Api-Version: 2022-11-28" "$@"
}

sha512_of() {
  openssl dgst -sha512 -r "$1" | cut -d' ' -f1
}

upload() {
  local key="$1" file="$2" type="$3"
  curl -fsS -X PUT -H "Authorization: Bearer ${TIVICOPY_UPDATES_UPLOAD_TOKEN}" \
    -H "Content-Type: ${type}" --data-binary "@${file}" "${HOST}/publish/${key}" >/dev/null ||
    die "upload of ${key} failed"
}

read_back() {
  local key="$1" file="$2" out
  out="$(mktemp)"
  curl -fsS -H "Cache-Control: no-cache" -o "${out}" "${HOST}/${key}" || die "reading back ${key} failed"
  [ "$(sha512_of "${out}")" = "$(sha512_of "${file}")" ] || die "${key} read back differs from what was uploaded"
  rm -f "${out}"
}

publish() {
  local dry_run=0 run_id=""
  while [ $# -gt 0 ]; do
    case "$1" in
      --dry-run) dry_run=1 ;;
      --run) run_id="${2:-}"; shift ;;
      *) die "unknown argument: $1" ;;
    esac
    shift
  done
  need curl jq openssl unzip base64
  [ -n "${SHIDUR_WEBOS_SIGNING_KEY_B64:-}" ] || die "SHIDUR_WEBOS_SIGNING_KEY_B64 is not set"
  [ -n "${SHIDUR_WEBOS_GITHUB_TOKEN:-}" ] || die "SHIDUR_WEBOS_GITHUB_TOKEN is not set"
  [ "${dry_run}" = 1 ] || [ -n "${TIVICOPY_UPDATES_UPLOAD_TOKEN:-}" ] || die "TIVICOPY_UPDATES_UPLOAD_TOKEN is not set"

  WORK="$(mktemp -d)"
  chmod 700 "${WORK}"
  trap 'rm -rf "${WORK}"' EXIT
  local work="${WORK}"
  (umask 077 && printf '%s' "${SHIDUR_WEBOS_SIGNING_KEY_B64}" | base64 -d >"${work}/key.pem")
  openssl pkey -in "${work}/key.pem" -pubout -out "${work}/public.pem" 2>/dev/null || die "the signing key does not read as a key"
  local public_key
  public_key="$(openssl pkey -pubin -in "${work}/public.pem" -outform DER | tail -c 32 | base64 -w0)"

  # 1. The run and its artifact.
  local runs
  if [ -z "${run_id}" ]; then
    runs="$(github "${API}/repos/${REPO}/actions/workflows/${WORKFLOW}/runs?branch=main&event=push&status=success&per_page=1")"
    run_id="$(jq -r '.workflow_runs[0].id // empty' <<<"${runs}")"
    [ -n "${run_id}" ] || die "no green ${WORKFLOW} run on main"
  fi
  local run head_sha
  run="$(github "${API}/repos/${REPO}/actions/runs/${run_id}")"
  [ "$(jq -r '.head_branch' <<<"${run}")" = "main" ] || die "run ${run_id} is not on main"
  [ "$(jq -r '.conclusion' <<<"${run}")" = "success" ] || die "run ${run_id} is not green"
  head_sha="$(jq -r '.head_sha' <<<"${run}")"
  local artifact_url
  artifact_url="$(github "${API}/repos/${REPO}/actions/runs/${run_id}/artifacts" |
    jq -r --arg n "${ARTIFACT}" '.artifacts[] | select(.name == $n and .expired == false) | .archive_download_url' | head -n1)"
  [ -n "${artifact_url}" ] || die "run ${run_id} has no ${ARTIFACT} artifact (or it expired)"
  github -o "${work}/bundle.zip" "${artifact_url}"
  mkdir "${work}/bundle"
  unzip -q "${work}/bundle.zip" -d "${work}/bundle"
  local b="${work}/bundle"

  # 2. What is in it.
  local version commit
  version="$(jq -r '.version' "${b}/bundle.json")"
  commit="$(jq -r '.commit' "${b}/bundle.json")"
  [ "$(jq -r '.app' "${b}/bundle.json")" = "${APP_ID}" ] || die "bundle.json is not ${APP_ID}'s"
  [[ "${version}" =~ ^[0-9]+\.[0-9]+\.[0-9]+$ ]] || die "bad version ${version}"
  [[ "${head_sha}" == "${commit}"* ]] || die "bundle.json commit ${commit} is not run ${run_id}'s ${head_sha}"
  local base="${HOST}/webos/bundles/${version}-${commit}"
  for f in "${FILES[@]}"; do
    [ -f "${b}/${f}" ] || die "the artifact has no ${f}"
    local entry
    entry="$(jq -c --arg p "${f}" '.files[] | select(.path == $p)' "${b}/bundle.json")"
    [ -n "${entry}" ] || die "bundle.json does not name ${f}"
    [ "$(jq -r '.url' <<<"${entry}")" = "${base}/${f}" ] || die "bundle.json puts ${f} somewhere else"
    [ "$(jq -r '.size' <<<"${entry}")" = "$(wc -c <"${b}/${f}" | tr -d ' ')" ] || die "${f}: size is not bundle.json's"
    [ "$(jq -r '.sha512' <<<"${entry}")" = "$(sha512_of "${b}/${f}")" ] || die "${f}: SHA-512 is not bundle.json's"
  done
  local repo_key
  repo_key="$(github -H "Accept: application/vnd.github.raw+json" \
    "${API}/repos/${REPO}/contents/webos/shell/public-key.txt?ref=${head_sha}" | tr -d '[:space:]')"
  [ "${repo_key}" = "${public_key}" ] || die "the shell at ${head_sha} carries another public key than the signing key's"
  echo "bundle ${version} ${commit} from run ${run_id}: files match bundle.json"

  # 3. Newer than what is published.
  local published_version=""
  if curl -fsS -o "${work}/published.json" "${HOST}/webos/manifest.json" 2>/dev/null; then
    published_version="$(jq -r '.payload' "${work}/published.json" | base64 -d | jq -r '.version')"
    local newest
    newest="$(printf '%s\n%s\n' "${published_version}" "${version}" | sort -V | tail -n1)"
    { [ "${newest}" = "${version}" ] && [ "${published_version}" != "${version}" ]; } ||
      die "${version} is not newer than the published ${published_version}"
    echo "published now: ${published_version}"
  else
    echo "published now: nothing"
  fi

  # 4. Sign and check.
  openssl pkeyutl -sign -inkey "${work}/key.pem" -rawin -in "${b}/bundle.json" -out "${work}/signature.bin"
  openssl pkeyutl -verify -pubin -inkey "${work}/public.pem" -rawin -in "${b}/bundle.json" \
    -sigfile "${work}/signature.bin" >/dev/null || die "the signature does not verify"
  jq -n --arg p "$(base64 -w0 "${b}/bundle.json")" --arg s "$(base64 -w0 "${work}/signature.bin")" \
    '{payload: $p, signature: $s}' >"${work}/manifest.json"

  if [ "${dry_run}" = 1 ]; then
    echo "dry run: would publish ${version} ${commit} (${FILES[*]} under ${base}/, then the manifest)"
    return 0
  fi

  # 5. Files first, then the manifest.
  for f in "${FILES[@]}"; do
    local type="text/javascript; charset=utf-8"
    [ "${f}" = "style.css" ] && type="text/css; charset=utf-8"
    upload "webos/bundles/${version}-${commit}/${f}" "${b}/${f}" "${type}"
    read_back "webos/bundles/${version}-${commit}/${f}" "${b}/${f}"
  done
  upload "webos/manifest.json" "${work}/manifest.json" "application/json; charset=utf-8"
  read_back "webos/manifest.json" "${work}/manifest.json"
  echo "published ${version} ${commit}: installed shells take it at their next launch, and run it at the one after"
}

case "${1:-}" in
  keygen) shift; keygen "$@" ;;
  -h|--help) sed -n '2,/^set -euo/p' "$0" | sed '$d' ;;
  *) publish "$@" ;;
esac
