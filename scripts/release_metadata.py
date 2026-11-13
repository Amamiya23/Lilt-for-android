#!/usr/bin/env python3
"""Validate a stable release and generate its signed manifest input."""
import argparse
import hashlib
import json
import os
import re
import urllib.error
import urllib.request
from pathlib import Path


def versions():
    gradle = Path('app/build.gradle.kts').read_text(encoding='utf-8')
    name = re.search(r'^\s*versionName\s*=\s*"([^"]+)"', gradle, re.M)
    code = re.search(r'^\s*versionCode\s*=\s*(\d+)', gradle, re.M)
    sdk = re.search(r'^\s*minSdk\s*=\s*(\d+)', gradle, re.M)
    app = re.search(r'^\s*applicationId\s*=\s*"([^"]+)"', gradle, re.M)
    if not all((name, code, sdk, app)):
        raise SystemExit('Could not read version configuration from app/build.gradle.kts')
    if not re.fullmatch(r'(0|[1-9]\d*)\.(0|[1-9]\d*)\.(0|[1-9]\d*)', name.group(1)):
        raise SystemExit('versionName must be a stable X.Y.Z version')
    return name.group(1), int(code.group(1)), int(sdk.group(1)), app.group(1)


def request(url):
    headers = {'Accept': 'application/vnd.github+json', 'User-Agent': 'Lilt-release'}
    token = os.environ.get('GH_TOKEN')
    if token and url.startswith('https://api.github.com/'):
        headers['Authorization'] = 'Bearer ' + token
    with urllib.request.urlopen(urllib.request.Request(url, headers=headers), timeout=30) as response:
        return response.read()


def validate(repo, tag):
    name, code, _, _ = versions()
    if tag != 'v' + name:
        raise SystemExit(f'Tag {tag} does not match versionName {name}')
    if code < 1:
        raise SystemExit('versionCode must be positive')
    try:
        release = json.loads(request(f'https://api.github.com/repos/{repo}/releases/latest'))
    except urllib.error.HTTPError as error:
        if error.code == 404:
            return
        raise
    assets = [asset for asset in release['assets'] if asset['name'] == 'update.json']
    if len(assets) != 1:
        raise SystemExit('Latest stable release is missing update.json; cannot verify versionCode')
    previous = json.loads(request(assets[0]['browser_download_url']))
    if code <= int(previous['versionCode']):
        raise SystemExit(f'versionCode {code} must exceed latest published versionCode {previous["versionCode"]}')


def generate(repo, tag, apk, output):
    name, code, sdk, app = versions()
    apk_path = Path(apk)
    digest = hashlib.sha256()
    with apk_path.open('rb') as source:
        for chunk in iter(lambda: source.read(1024 * 1024), b''):
            digest.update(chunk)
    manifest = {
        'versionName': name,
        'versionCode': code,
        'packageName': app,
        'minSdk': sdk,
        'apkUrl': f'https://github.com/{repo}/releases/download/{tag}/Lilt.apk',
        'sha256': digest.hexdigest(),
        'size': apk_path.stat().st_size,
        'notes': Path('RELEASE_NOTES.md').read_text(encoding='utf-8').strip(),
    }
    Path(output).write_bytes((json.dumps(manifest, ensure_ascii=False, separators=(',', ':')) + '\n').encode('utf-8'))


if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('action', choices=['validate', 'generate'])
    parser.add_argument('--repo', required=True)
    parser.add_argument('--tag', required=True)
    parser.add_argument('--apk')
    parser.add_argument('--output')
    args = parser.parse_args()
    if args.action == 'validate':
        validate(args.repo, args.tag)
    else:
        if not args.apk or not args.output:
            parser.error('generate requires --apk and --output')
        generate(args.repo, args.tag, args.apk, args.output)
