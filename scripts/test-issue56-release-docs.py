#!/usr/bin/env python3
"""Adversarial tests for docs, source and ZIP privacy checks (synthetic IPs)."""
import importlib.util
from pathlib import Path
import subprocess
import sys
sys.dont_write_bytecode=True
import tempfile
import unittest
import zipfile

ROOT=Path(__file__).resolve().parents[1]
SCRIPT=ROOT/'tools/audit-source-package.py'
CHECK=ROOT/'scripts/check-issue56-release-docs.py'

spec=importlib.util.spec_from_file_location('issue56_source_audit',SCRIPT)
mod=importlib.util.module_from_spec(spec)
spec.loader.exec_module(mod)

# Construct synthetic example addresses without embedding the dotted literal
# into the *repository's* own source or configuration files.
PRIVATE='.'.join(['192','168','45','212'])
OTHER_PRIVATE='.'.join(['10','56','7','34'])
FIXTURE_PRIVATE='.'.join(['10','1','2','3'])
TEST_NET='.'.join(['198','51','100','212'])


class ReleaseDocumentationTests(unittest.TestCase):
    def test_release_procedure_is_complete(self):
        proc=subprocess.run([sys.executable,str(CHECK)],capture_output=True,text=True,timeout=8)
        self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)

    def test_missing_review_procedure_fails(self):
        with tempfile.TemporaryDirectory() as temp:
            root=Path(temp)
            proc=subprocess.run([sys.executable,str(CHECK),'--root',str(root)],capture_output=True,text=True,timeout=8)
            self.assertNotEqual(proc.returncode,0)

    def test_real_source_tree_is_clean(self):
        proc=subprocess.run([sys.executable,str(SCRIPT),str(ROOT)],capture_output=True,text=True,timeout=18)
        self.assertEqual(proc.returncode,0,proc.stdout+proc.stderr)

    def test_unapproved_rfc1918_in_docs_and_tests_is_blocked_without_printing_ip(self):
        for path in ('README.md','src/test/java/example/SyntheticFixtureTest.java'):
            with self.subTest(path=path),tempfile.TemporaryDirectory() as temp:
                f=Path(temp)/path;f.parent.mkdir(parents=True,exist_ok=True)
                f.write_text('example: '+PRIVATE+' another '+OTHER_PRIVATE)
                proc=subprocess.run([sys.executable,str(SCRIPT),str(Path(temp))],capture_output=True,text=True,timeout=8)
                self.assertNotEqual(proc.returncode,0)
                self.assertIn('non-fixture private IPv4 literal',proc.stderr)
                self.assertNotIn(PRIVATE,proc.stderr)
                self.assertNotIn(OTHER_PRIVATE,proc.stderr)

    def test_documentation_ranges_are_not_private(self):
        self.assertIsNone(mod.private_ipv4_evidence('README.md',('example '+TEST_NET).encode()))

    def test_security_test_uses_only_explicit_synthetic_fixture_addresses(self):
        name='src/test/java/info/trizub/clamav/webclient/service/WebhookAddressPolicyTest.java'
        self.assertIsNone(mod.private_ipv4_evidence(name,FIXTURE_PRIVATE.encode()))
        self.assertIsNotNone(mod.private_ipv4_evidence(name,PRIVATE.encode()))

    def test_svg_artwork_decimal_coordinates_do_not_trigger_false_positive(self):
        name='src/main/resources/static/flags/1x1/fk.svg'
        self.assertIsNone(mod.private_ipv4_evidence(name,('d="'+OTHER_PRIVATE+'"').encode()))
        self.assertIsNotNone(mod.private_ipv4_evidence(name,PRIVATE.encode()))

    def test_zip_package_is_also_blocked_before_publication(self):
        with tempfile.TemporaryDirectory() as temp:
            path=Path(temp)/'source.zip'
            with zipfile.ZipFile(path,'w') as out:
                out.writestr('archive/README.md','internal example: '+PRIVATE)
            proc=subprocess.run([sys.executable,str(SCRIPT),str(path)],capture_output=True,text=True,timeout=8)
            self.assertNotEqual(proc.returncode,0)
            self.assertNotIn(PRIVATE,proc.stderr)

if __name__=='__main__':unittest.main(verbosity=2)
