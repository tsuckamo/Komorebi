import tempfile
import unittest
from pathlib import Path
import zipfile
from unittest.mock import Mock, patch
from privacy import check_apk, check_bytes, PRIVATE_NAMES
from updater import Updater, validate_config, properties

class UpdateSafetyTests(unittest.TestCase):
    def config(self):
        return {'repository': 'tsuckamo/Komorebi', 'branch': 'main', 'certificateSha256': 'a' * 64,
                'gitEmail': 'test@users.noreply.github.com'}

    def test_upstream_cannot_be_a_publish_target(self):
        cfg = self.config()
        cfg['repository'] = 'BeerEgg2001/Komorebi'
        with self.assertRaises(ValueError):
            validate_config(cfg)

    def test_other_repo_or_branch_is_rejected(self):
        for key, value in [('repository', 'another/repo'), ('branch', 'develop')]:
            cfg = self.config(); cfg[key] = value
            with self.assertRaises(ValueError): validate_config(cfg)

    def test_personal_commit_email_is_rejected(self):
        cfg = self.config(); cfg['gitEmail'] = 'person@example.invalid'
        with self.assertRaises(ValueError): validate_config(cfg)

    def test_expected_configuration_is_accepted(self):
        validate_config(self.config())

    def test_private_values_in_dex_or_resources_are_rejected(self):
        for encoding in ('utf-8', 'utf-16le'):
            with self.assertRaises(ValueError):
                check_bytes('prefix PRIVATE_TEST_VALUE suffix'.encode(encoding), ['PRIVATE_TEST_VALUE'])

    def test_secret_patterns_are_rejected_without_echoing_value(self):
        secret = b'ghp_' + b'a' * 40
        with self.assertRaises(ValueError) as result: check_bytes(secret, [])
        self.assertNotIn(secret.decode(), str(result.exception))

    def test_apk_is_unpacked_for_inspection(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'test.apk'
            with zipfile.ZipFile(apk, 'w', zipfile.ZIP_DEFLATED) as archive:
                archive.writestr('classes.dex', b'PRIVATE_TEST_VALUE')
            with self.assertRaises(ValueError): check_apk(apk, ['PRIVATE_TEST_VALUE'])

    def test_private_filenames_are_rejected(self):
        for name in ['local.properties', 'a/private.p12', '.env', 'config/password.dpapi.xml', 'build-info.json']:
            self.assertIsNotNone(PRIVATE_NAMES.search(name))
        self.assertIsNone(PRIVATE_NAMES.search('custom-update.json'))

    def test_safe_apk_and_version_properties(self):
        with tempfile.TemporaryDirectory() as directory:
            apk = Path(directory) / 'test.apk'
            with zipfile.ZipFile(apk, 'w') as archive: archive.writestr('classes.dex', b'public code')
            check_apk(apk, ['PRIVATE_TEST_VALUE'])
            prop = Path(directory) / 'release.properties'
            prop.write_text('# comment\nversionCode=24\nversionName=1.1.1-custom.24\n', encoding='utf-8')
            self.assertEqual('24', properties(prop)['versionCode'])

    def pipeline(self, directory):
        updater = Updater.__new__(Updater)
        updater.root = Path(directory)
        updater.repo = Path(directory) / 'source'
        updater.repo.mkdir()
        updater.cfg = {'privacyBaseCommit': 'baseline'}
        updater.forbidden = []
        updater.prepare_repository = Mock()
        updater.status = Mock()
        updater.build = Mock()
        updater.finish_publication = Mock()
        updater.git = Mock(return_value=Mock(returncode=0, stdout='commit\n'))
        (updater.repo / 'custom-release.properties').write_text('versionCode=24\nversionName=1.1.1-custom.24\nupstreamTag=1.1.1\nupstreamCommit=baseline\n', encoding='utf-8')
        return updater

    @patch('updater.upstream_release', return_value='1.1.1')
    def test_no_new_release_never_builds_or_publishes(self, latest):
        with tempfile.TemporaryDirectory() as directory:
            updater = self.pipeline(directory)
            updater.run()
            updater.build.assert_not_called()
            updater.finish_publication.assert_not_called()
            self.assertEqual('current', updater.status.call_args.args[0])

    @patch('updater.upstream_release', return_value='1.2.0')
    def test_conflict_aborts_without_building_or_publishing(self, latest):
        with tempfile.TemporaryDirectory() as directory:
            updater = self.pipeline(directory)
            updater.git.side_effect = lambda *args, **kwargs: Mock(returncode=1 if args[:2] == ('merge', '--no-ff') else 0, stdout='commit\n')
            updater.run()
            updater.git.assert_any_call('merge', '--abort', allow_failure=True)
            updater.build.assert_not_called()
            updater.finish_publication.assert_not_called()
            self.assertEqual('blocked', updater.status.call_args.args[0])

    @patch('updater.check_changes')
    @patch('updater.upstream_release', return_value='1.1.1')
    def test_build_failure_never_publishes_or_creates_pending(self, latest, scan):
        with tempfile.TemporaryDirectory() as directory:
            updater = self.pipeline(directory)
            updater.build.side_effect = RuntimeError('test failure')
            with self.assertRaises(RuntimeError): updater.run(force=True)
            updater.finish_publication.assert_not_called()
            self.assertFalse((updater.root / 'pending.json').exists())

    def test_failed_test_report_is_rejected(self):
        with tempfile.TemporaryDirectory() as directory:
            updater = self.pipeline(directory)
            reports = updater.repo / 'app/build/test-results/testDebugUnitTest'
            reports.mkdir(parents=True)
            (reports / 'TEST-example.xml').write_text('<testsuite tests="1" failures="1" errors="0" skipped="0"/>')
            with self.assertRaises(ValueError): updater.tests_passed()

if __name__ == '__main__': unittest.main()
