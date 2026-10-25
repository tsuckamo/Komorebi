"""公開前検査。検出した秘密の値はエラーやログにも出さない。"""
from pathlib import Path
import re
import subprocess
import zipfile

SECRET = re.compile(rb'(?:-----BEGIN (?:RSA |EC |OPENSSH )?PRIVATE KEY-----|gh[pousr]_[A-Za-z0-9_]{30,}|github_pat_[A-Za-z0-9_]{30,})')
PRIVATE_NAMES = re.compile(r'(?i)(?:^|/)(?:local\.properties|update-config\.json|\.env(?:\..*)?|build-info[^/]*\.json)$|\.(?:p12|jks|keystore|dpapi\.xml|log)$')

def check_bytes(data, forbidden):
    if SECRET.search(data):
        raise ValueError('秘密情報のパターンを検出したため公開を中止しました')
    for value in forbidden:
        if value and any(value.encode(encoding) in data for encoding in ('utf-8', 'utf-16le')):
            raise ValueError('個人用の値を検出したため公開を中止しました')

def check_apk(path, forbidden):
    with zipfile.ZipFile(path) as archive:
        for member in archive.infolist():
            if PRIVATE_NAMES.search(member.filename):
                raise ValueError('APK内に個人用ファイルを検出しました')
            check_bytes(archive.read(member), forbidden)

def check_changes(repo, base, forbidden):
    # 各コミットも検査する。最終ツリーから削除しても履歴には残るため。
    git = ['git', '-C', str(repo)]
    commits = subprocess.check_output([*git, 'rev-list', f'{base}..HEAD'], text=True).splitlines()
    blobs = set()
    for commit in commits:
        check_bytes(subprocess.check_output([*git, 'show', '-s', '--format=%B%n%an%n%ae%n%cn%n%ce', commit]), forbidden)
        names = set(subprocess.check_output([*git, 'diff-tree', '--no-commit-id', '--name-only', '-z', '-r', '-m', '--diff-filter=ACMR', commit]).decode('utf-8').split('\0'))
        tree = subprocess.check_output([*git, 'ls-tree', '-r', '-z', commit]).split(b'\0')
        for entry in filter(None, tree):
            metadata, raw_name = entry.split(b'\t', 1)
            name = raw_name.decode('utf-8')
            if name not in names:
                continue
            if PRIVATE_NAMES.search(name):
                raise ValueError('公開対象の履歴に個人用ファイルを検出しました')
            mode, kind, oid = metadata.split()
            if kind == b'blob':
                blobs.add(oid)
    # WindowsでファイルごとにGitを起動しない。同一内容も一度だけ検査する。
    with subprocess.Popen([*git, 'cat-file', '--batch'], stdin=subprocess.PIPE, stdout=subprocess.PIPE) as process:
        try:
            for oid in blobs:
                process.stdin.write(oid + b'\n'); process.stdin.flush()
                header = process.stdout.readline().split()
                if len(header) != 3 or header[1] != b'blob':
                    raise ValueError('公開対象の履歴を検査できませんでした')
                size = int(header[2])
                data = process.stdout.read(size)
                if len(data) != size or process.stdout.read(1) != b'\n':
                    raise ValueError('公開対象の履歴が不完全です')
                check_bytes(data, forbidden)
        finally:
            process.stdin.close()
            process.stdout.close()
        if process.wait():
            raise ValueError('公開対象の履歴を検査できませんでした')

def check_json(path, forbidden):
    check_bytes(Path(path).read_bytes(), forbidden)
