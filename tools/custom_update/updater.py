"""個人フォーク専用の更新処理。秘密と詳細ログはConfigPath配下のローカル環境に保持する。"""
import argparse
import datetime as dt
import hashlib
import json
import os
from pathlib import Path
import re
import shutil
import subprocess
import sys
import urllib.request
import xml.etree.ElementTree as ET
from privacy import check_apk, check_changes, check_json

OWN_REPO = 'tsuckamo/Komorebi'
UPSTREAM_REPO = 'BeerEgg2001/Komorebi'
APK_NAME = 'Komorebi-custom-armeabi-v7a-release.apk'
PACKAGE = 'com.beeregg2001.Komorebi'

def validate_config(cfg):
    # 書き込み先を設定値だけに委ねない。本家や別のリポジトリは拒否する。
    if cfg.get('repository') != OWN_REPO or cfg.get('branch') != 'main':
        raise ValueError('書き込み先は個人フォークのmainだけに限定されています')
    if not re.fullmatch(r'[0-9a-fA-F]{64}', cfg.get('certificateSha256', '')):
        raise ValueError('継続用署名の指紋が必要です')
    if not cfg.get('gitEmail', '').endswith('@users.noreply.github.com'):
        raise ValueError('公開コミットにはGitHubの非公開メールアドレスを使用してください')

def properties(path):
    return dict(line.split('=', 1) for line in path.read_text(encoding='utf-8').splitlines() if '=' in line and not line.startswith('#'))

def digest(path):
    result = hashlib.sha256()
    with path.open('rb') as stream:
        for block in iter(lambda: stream.read(1024 * 1024), b''):
            result.update(block)
    return result.hexdigest()

def upstream_release():
    # 最新の非プレリリースを1回だけ読む。Issue、PR、コメントは一切作成しない。
    request = urllib.request.Request('https://api.github.com/repos/' + UPSTREAM_REPO + '/releases/latest', headers={
        'Accept': 'application/vnd.github+json', 'User-Agent': 'Komorebi-personal-update-check'
    })
    with urllib.request.urlopen(request, timeout=30) as response:
        release = json.load(response)
    tag = release['tag_name']
    if release.get('draft') or release.get('prerelease') or not re.fullmatch(r'[A-Za-z0-9._+-]+', tag):
        raise ValueError('対応していない本家リリースです')
    return tag

class Updater:
    def __init__(self, config_path):
        self.config_path = config_path.resolve()
        self.cfg = json.loads(self.config_path.read_text(encoding='utf-8-sig'))
        validate_config(self.cfg)
        self.root = self.config_path.parent
        self.repo = self.root / 'source'
        self.forbidden = self.cfg['privateValues']
        self.env = os.environ.copy()
        self.env.update({
            'JAVA_HOME': self.cfg['javaHome'], 'ANDROID_HOME': self.cfg['androidSdk'],
            'ANDROID_SDK_ROOT': self.cfg['androidSdk'], 'GRADLE_USER_HOME': self.cfg['gradleHome'],
            'JAVA_TOOL_OPTIONS': '-Djava.io.tmpdir=' + self.cfg['tempDir'] + ' -Djdk.net.unixdomain.tmpdir=' + self.cfg['tempDir'],
            'GIT_TERMINAL_PROMPT': '0', 'GH_PROMPT_DISABLED': '1'
        })
        for key in list(self.env):
            if key.startswith('KOMOREBI_TEST_') or key.startswith('KOMOREBI_SIGN_'):
                self.env.pop(key)
        self.env.update(self.cfg.get('testEnvironment', {}))
        self.log = None

    def command(self, args, cwd=None, allow_failure=False, timeout=5400):
        result = subprocess.run([str(arg) for arg in args], cwd=cwd, env=self.env, stdout=subprocess.PIPE,
                                stderr=subprocess.STDOUT, timeout=timeout, encoding='utf-8', errors='replace')
        if self.log:
            self.log.write(result.stdout)
            self.log.flush()
        if result.returncode and not allow_failure:
            raise RuntimeError('処理が失敗しました。詳細はこのPC内のログを確認してください: ' + Path(str(args[0])).name)
        return result

    def git(self, *args, **kwargs):
        return self.command([self.cfg['git'], '-C', self.repo, *args], **kwargs)

    def gh(self, *args, **kwargs):
        # このメソッドの呼び出しはすべて自分のリポジトリを明示する。
        return self.command([self.cfg['gh'], *args], **kwargs)

    def status(self, state, message, **extra):
        value = {'time': dt.datetime.now().astimezone().isoformat(), 'state': state, 'message': message, **extra}
        (self.root / 'status.json').write_text(json.dumps(value, ensure_ascii=False, indent=2), encoding='utf-8')
        print(message, flush=True)

    def prepare_repository(self):
        expected = 'https://github.com/' + OWN_REPO + '.git'
        if not self.repo.exists():
            self.command([self.cfg['git'], 'clone', '--branch', 'main', expected, self.repo])
        if self.git('remote', 'get-url', 'origin').stdout.strip() != expected:
            raise ValueError('作業用リポジトリの送信先が違います')
        if self.git('status', '--porcelain').stdout.strip():
            raise ValueError('作業用リポジトリに未保存の変更があります。上書きせず停止します')
        self.git('config', 'user.name', self.cfg['gitName'])
        self.git('config', 'user.email', self.cfg['gitEmail'])
        self.git('config', 'remote.origin.pushurl', expected)
        self.git('config', 'remote.upstream.url', 'https://github.com/' + UPSTREAM_REPO + '.git')
        self.git('config', 'remote.upstream.pushurl', 'DISABLED')
        self.git('fetch', 'origin', 'main')
        self.git('checkout', '--detach', 'origin/main')

    def tests_passed(self):
        reports = list((self.repo / 'app/build/test-results/testDebugUnitTest').glob('TEST-*.xml'))
        if not reports:
            raise ValueError('テスト結果がありません')
        suites = [ET.parse(path).getroot() for path in reports]
        if any(int(s.get('failures', 0)) or int(s.get('errors', 0)) for s in suites):
            raise ValueError('テストが失敗したため公開しません')
        return {key: sum(int(s.get(key, 0)) for s in suites) for key in ('tests', 'failures', 'errors', 'skipped')}

    def build(self, props):
        self.command([self.cfg['python'], '-m', 'unittest', 'discover', '-s', 'tools/custom_update', '-p', 'test_*.py'], cwd=self.repo)
        self.git('submodule', 'update', '--init', '--recursive')
        (self.repo / 'local.properties').write_text('sdk.dir=' + self.cfg['androidSdk'].replace('\\', '/') + '\n', encoding='utf-8')
        fonts = self.repo / 'app/src/main/res/font'
        fonts.mkdir(parents=True, exist_ok=True)
        for file in Path(self.cfg['fontDirectory']).glob('notosansjp_*.ttf'):
            shutil.copy2(file, fonts / file.name)
        self.command([self.repo / 'gradlew.bat', ':app:testDebugUnitTest', ':app:assembleRelease', '-PpublicRelease',
                      '--console=plain', '-Dorg.gradle.jvmargs=-Xmx6g -Dfile.encoding=UTF-8 -Djdk.net.unixdomain.tmpdir=' + self.cfg['tempDir']], cwd=self.repo)
        stats = self.tests_passed()
        output = self.root / 'releases' / ('custom-' + props['versionCode'])
        output.mkdir(parents=True, exist_ok=True)
        apk = output / APK_NAME
        unsigned = self.repo / 'app/build/outputs/apk/release/app-armeabi-v7a-release-unsigned.apk'
        self.command([self.cfg['powershell'], '-NoProfile', '-File', self.root / 'scripts/sign.ps1',
                      '-ConfigPath', self.config_path, '-InputApk', unsigned, '-OutputApk', apk])
        # コンパイルされたDEX・リソース・ネイティブライブラリも検査する。
        check_apk(apk, self.forbidden)
        info = self.command([Path(self.cfg['buildTools']) / 'aapt.exe', 'dump', 'badging', apk]).stdout
        if ("name='" + PACKAGE + "'") not in info or ("versionCode='" + props['versionCode'] + "'") not in info:
            raise ValueError('公開対象APKのパッケージまたはバージョンが不正です')
        return apk, stats

    def finish_publication(self, pending):
        apk = Path(pending['apk'])
        manifest = pending['manifest']
        if digest(apk) != manifest['sha256']:
            raise ValueError('公開待ちAPKが検査時から変更されています')
        check_apk(apk, self.forbidden)
        if self.git('rev-parse', 'HEAD').stdout.strip() != pending['sourceCommit']:
            changed = self.git('diff', '--name-only', pending['sourceCommit'], 'HEAD').stdout.splitlines()
            feed_file = self.repo / 'custom-update.json'
            if changed != ['custom-update.json'] or not feed_file.exists() or json.loads(feed_file.read_text(encoding='utf-8')) != manifest:
                raise ValueError('公開待ちソースが変更されています')
        check_changes(self.repo, self.cfg['privacyBaseCommit'], self.forbidden)
        tag = pending['tag']
        self.git('push', 'origin', 'HEAD:refs/heads/main')
        current_tag = self.git('rev-parse', '--verify', 'refs/tags/' + tag, allow_failure=True)
        if current_tag.returncode:
            self.git('tag', tag, pending['sourceCommit'])
        elif current_tag.stdout.strip() != pending['sourceCommit']:
            raise ValueError('同名の配布タグが別コミットを指しています')
        self.git('push', 'origin', 'refs/tags/' + tag)
        notes = apk.parent / 'release-notes.txt'
        notes.write_text('個人フォークの更新です。本家への問い合わせ対象ではありません。\n\n'
                         '本家安定版: ' + pending['upstreamTag'] + '\n'
                         'カスタム機能を維持してテスト・署名・公開前検査を実施しました。\n'
                         '接続先や認証情報は含めていません。設定は端末で行ってください。\n', encoding='utf-8')
        check_json(notes, self.forbidden)
        existing = self.gh('release', 'view', tag, '--repo', OWN_REPO, '--json', 'isDraft,assets', allow_failure=True)
        if existing.returncode:
            self.gh('release', 'create', tag, '--repo', OWN_REPO, '--verify-tag', '--draft',
                    '--title', manifest['versionName'], '--notes-file', notes)
            details = {'isDraft': True, 'assets': []}
        else:
            details = json.loads(existing.stdout)
        asset = next((a for a in details['assets'] if a['name'] == APK_NAME), None)
        if asset is None:
            if not details['isDraft']:
                raise ValueError('公開済みリリースにAPKがありません')
            self.gh('release', 'upload', tag, apk, '--repo', OWN_REPO)
        # ネット上のAPKを再取得して照合してから更新情報を切り替える。
        downloaded = apk.parent / 'download-check'
        downloaded.mkdir(exist_ok=True)
        self.gh('release', 'download', tag, '--repo', OWN_REPO, '--pattern', APK_NAME, '--dir', downloaded, '--clobber')
        if digest(downloaded / APK_NAME) != manifest['sha256']:
            raise ValueError('配布されたAPKのハッシュが一致しません')
        if details['isDraft']:
            self.gh('release', 'edit', tag, '--repo', OWN_REPO, '--draft=false', '--latest')
        feed = self.repo / 'custom-update.json'
        feed.write_text(json.dumps(manifest, ensure_ascii=False, indent=2) + '\n', encoding='utf-8')
        check_json(feed, self.forbidden)
        self.git('add', '--', 'custom-update.json')
        if self.git('diff', '--cached', '--quiet', allow_failure=True).returncode:
            self.git('commit', '-m', 'chore: 個人版の更新情報を公開 ' + manifest['versionName'])
            self.git('push', 'origin', 'HEAD:refs/heads/main')
        (self.root / 'pending.json').unlink(missing_ok=True)
        self.status('published', '個人版の更新を公開しました。テレビで更新できます。', version=manifest['versionName'], tests=pending['tests'])

    def run(self, force=False, check_only=False):
        pending = self.root / 'pending.json'
        if pending.exists() and not check_only:
            self.finish_publication(json.loads(pending.read_text(encoding='utf-8')))
            return
        self.prepare_repository()
        props_file = self.repo / 'custom-release.properties'
        props = properties(props_file)
        tag = upstream_release()
        if tag == props['upstreamTag'] and not force:
            self.status('current', '本家の新しい安定版はありません。現在の個人版を維持します。', upstreamTag=tag)
            return
        if check_only:
            self.status('available', '本家の新しい安定版があります。検査後に個人版として更新します。', upstreamTag=tag)
            return
        if tag != props['upstreamTag']:
            self.git('fetch', 'upstream', 'refs/tags/' + tag)
            upstream_commit = self.git('rev-parse', 'FETCH_HEAD^{commit}').stdout.strip()
            merged = self.git('merge', '--no-ff', '--no-commit', upstream_commit, allow_failure=True)
            if merged.returncode:
                self.git('merge', '--abort', allow_failure=True)
                self.status('blocked', '本家更新との競合があり、配布を止めました。現在のテレビの版は継続利用できます。', upstreamTag=tag)
                return
            props['versionCode'] = str(int(props['versionCode']) + 1)
            props['versionName'] = tag.lstrip('v') + '-custom.' + props['versionCode']
            props['upstreamTag'] = tag
            props['upstreamCommit'] = upstream_commit
        else:
            # 手動の強制公開は同じ版の上書きを避けて番号を進める。
            feed = self.repo / 'custom-update.json'
            if feed.exists() and json.loads(feed.read_text(encoding='utf-8'))['versionCode'] >= int(props['versionCode']):
                props['versionCode'] = str(int(props['versionCode']) + 1)
                props['versionName'] = tag.lstrip('v') + '-custom.' + props['versionCode']
        props_file.write_text(''.join(key + '=' + value + '\n' for key, value in props.items()), encoding='utf-8')
        self.git('add', '--', 'custom-release.properties')
        if self.git('diff', '--cached', '--quiet', allow_failure=True).returncode or (self.repo / '.git/MERGE_HEAD').exists():
            self.git('commit', '-m', 'chore: 本家安定版を個人版へ取り込み ' + tag)
        check_changes(self.repo, self.cfg['privacyBaseCommit'], self.forbidden)
        apk, stats = self.build(props)
        release_tag = 'custom-' + props['versionCode']
        manifest = {
            'schemaVersion': 1, 'packageName': PACKAGE, 'versionCode': int(props['versionCode']),
            'versionName': props['versionName'], 'releaseNotes': 'カスタム機能を維持した検証済みの個人版です。',
            'abi': 'armeabi-v7a', 'size': apk.stat().st_size, 'sha256': digest(apk),
            'apkUrl': 'https://github.com/' + OWN_REPO + '/releases/download/' + release_tag + '/' + APK_NAME
        }
        publication = {'apk': str(apk), 'manifest': manifest, 'tag': release_tag, 'upstreamTag': tag,
                       'sourceCommit': self.git('rev-parse', 'HEAD').stdout.strip(), 'tests': stats}
        pending.write_text(json.dumps(publication, ensure_ascii=False, indent=2), encoding='utf-8')
        self.finish_publication(publication)

def main():
    parser = argparse.ArgumentParser()
    parser.add_argument('--config', type=Path, required=True)
    parser.add_argument('--force', action='store_true')
    parser.add_argument('--check-only', action='store_true')
    args = parser.parse_args()
    updater = Updater(args.config)
    # 二重実行は既存プロセスに任せる。ロックはプロセス終了時にOSが解放する。
    import msvcrt
    with (updater.root / 'run.lock').open('a+b') as lock:
        lock.seek(0)
        if not lock.read(1):
            lock.write(b'0'); lock.flush()
        lock.seek(0)
        try:
            msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
        except OSError:
            print('更新処理はすでに実行中です')
            return
        logs = updater.root / 'logs'
        logs.mkdir(exist_ok=True)
        with (logs / (dt.datetime.now().strftime('%Y%m%d-%H%M%S') + '.log')).open('w', encoding='utf-8') as log:
            updater.log = log
            try:
                updater.run(args.force, args.check_only)
            except Exception as error:
                import traceback
                traceback.print_exc(file=log)
                updater.status('failed', '更新の検査または配布が失敗し、停止しました。詳細ログはこのPC内にあります。')
                sys.exit(1)

if __name__ == '__main__':
    main()
