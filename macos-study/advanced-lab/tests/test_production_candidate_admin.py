import hashlib
import fcntl
import importlib.util
import json
import os
import pathlib
import socket
import tempfile
import unittest
from unittest.mock import patch

HERE = pathlib.Path(__file__).resolve().parents[1]
spec = importlib.util.spec_from_file_location('candidate_admin', HERE/'production-candidate-admin.py')
admin = importlib.util.module_from_spec(spec); spec.loader.exec_module(admin)

class AdminFixtureTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory(prefix='focusdeck-admin-')
        self.root = pathlib.Path(self.temp.name)
        self.old = admin.ROOT, admin.SOCKET, admin.ROOT_OWNER
        admin.ROOT, admin.SOCKET, admin.ROOT_OWNER = self.root/'private', self.root/'socket', os.getuid()
        self.daemon = self.root/'daemon'; self.daemon.write_bytes(b'fixture daemon')
        self.recovery = self.root/'recovery'; self.recovery.write_bytes(b'fixture recovery')
        self.app = str(self.root/'ui.app'); self.target = str(self.root/'synthetic.app')
        self.ident = 'dev.stanislav.focusdeck.synthetic'
        self.requirement = 'identifier "dev.stanislav.focusdeck.synthetic" and anchor apple generic'
        self.target_info = {'signingID': self.ident, 'bundleID': self.ident, 'teamID': 'ABCD123456', 'requirement': self.requirement, 'cdhash': 'b'*40}
        self.ui_info = {'signingID': 'dev.stanislav.focusdeck.ui', 'bundleID': 'dev.stanislav.focusdeck.ui', 'teamID': None, 'requirement': 'test', 'cdhash': 'a'*40}
        self.proposal = self.root/'proposal.json'
        self.value = {'schemaVersion': 1, 'proposalStatus':'local_uninstalled', 'allowedUID':os.getuid(), 'policyRevisionId':'e9de3bd8-c8b4-43ca-b4a3-02af93cfd33f',
                      'uiCDHash':'a'*40, 'blockedSigningIDs':[self.ident], 'blockedDomains':[], 'protectedDomains':admin.PROTECTED_DOMAINS,
                      'approvedWindow': {'policyVersion':2,'policyRevisionId':'e9de3bd8-c8b4-43ca-b4a3-02af93cfd33f','windowId':'5f3f5fce-6648-4a88-aaad-a552576f45a9','timezone':'Europe/Minsk','localDate':'2030-01-01','startsAt':'2030-01-01T12:00:00Z','endsAt':'2030-01-01T13:00:00Z','reliefBudgetSeconds':300,'reliefRemainingSeconds':300,'reliefMaxGrantSeconds':300,'exitCoolingOffSeconds':15,'targetRefs':[self.ident],'state':'inactive'},
                      'targetApplications':[{'signingID':self.ident,'path':self.target}],
                      'signingRequirements':[{'signingID':self.ident,'teamID':'ABCD123456','requirement':self.requirement}]}
        self.write_proposal()
        self.daemon_hash = hashlib.sha256(self.daemon.read_bytes()).hexdigest()
        self.recovery_hash = hashlib.sha256(self.recovery.read_bytes()).hexdigest()
        self.signatures = patch.object(admin, 'inspect_app', side_effect=lambda path: self.ui_info if path == self.app else self.target_info)
        self.signatures.start()

    def tearDown(self):
        self.signatures.stop(); admin.ROOT, admin.SOCKET, admin.ROOT_OWNER = self.old; self.temp.cleanup()

    def write_proposal(self):
        self.proposal.write_text(json.dumps(self.value)); self.proposal.chmod(0o600)
        self.proposal_hash = hashlib.sha256(self.proposal.read_bytes()).hexdigest()

    def draft(self):
        return admin.draft(self.proposal, self.app, self.daemon, self.recovery, self.proposal_hash, self.daemon_hash, self.recovery_hash, os.getuid())

    def test_valid_plan_and_idle_install_with_no_overwrite(self):
        trust, daemon, recovery, revision = self.draft(); self.assertFalse(admin.ROOT.exists())
        self.assertEqual(trust['signingRequirements'][0]['teamID'], 'ABCD123456'); self.assertEqual(trust['blockedDomains'], [])
        admin.install(trust, daemon, recovery, revision)
        self.assertEqual(admin.check_root(), {'root-config.json','manifest.json','ready.json','daemon.lock',admin.POLICY,admin.DAEMON,admin.RECOVERY})
        ledger = admin.check_config(); self.assertEqual(ledger['policy']['reliefRemainingSeconds'], 300)
        with self.assertRaises(ValueError): admin.install(trust, daemon, recovery, revision)
        with self.assertRaises(ValueError): admin.remove()
        self.mark_terminal(); admin.remove(); admin.remove()
        self.assertTrue((admin.ROOT/admin.POLICY).exists()); self.assertFalse((admin.ROOT/admin.DAEMON).exists())

    def test_spoof_team_requirement_adhoc_and_protected_rejected(self):
        self.target_info['teamID'] = 'OTHER12345'
        with self.assertRaises(ValueError): self.draft()
        self.target_info['teamID'] = None
        with self.assertRaises(ValueError): self.draft()
        self.target_info['teamID'] = 'ABCD123456'; self.target_info['requirement'] = 'different'
        with self.assertRaises(ValueError): self.draft()
        self.target_info['requirement'] = self.requirement
        self.value['blockedSigningIDs'] = ['com.openai.codex']; self.write_proposal()
        with self.assertRaises(ValueError): self.draft()
        self.assertFalse(admin.ROOT.exists())

    def test_changed_hash_uid_and_ui_signature_rejected(self):
        self.daemon.write_bytes(b'tampered')
        with self.assertRaises(ValueError): self.draft()
        self.daemon.write_bytes(b'fixture daemon'); self.ui_info['cdhash'] = 'c'*40
        with self.assertRaises(ValueError): self.draft()
        self.ui_info['cdhash'] = 'a'*40; self.value['allowedUID'] += 1; self.write_proposal()
        with self.assertRaises(ValueError): self.draft()
        self.assertFalse(admin.ROOT.exists())

    def mark_terminal(self):
        path = admin.ROOT/admin.POLICY; ledger = json.loads(path.read_text()); ledger['policy']['state'] = 'exit'; ledger['policy']['reliefRemainingSeconds'] = 0
        path.write_text(json.dumps(ledger)); path.chmod(0o600)

    def test_locked_daemon_refuses_removal_and_recovery_does_not_read_ui(self):
        admin.install(*self.draft()); self.mark_terminal()
        lock = admin.ROOT/'daemon.lock'; lock.write_text(''); lock.chmod(0o600)
        with lock.open('r+') as stream:
            fcntl.flock(stream, fcntl.LOCK_EX | fcntl.LOCK_NB)
            with self.assertRaises(ValueError): admin.remove()
        self.signatures.stop()
        with patch.object(admin.subprocess, 'run') as invoke:
            admin.recover()
            invoke.assert_called_once_with([str(admin.ROOT/admin.RECOVERY), '--recover'], check=True)
        self.signatures.start()
        self.assertTrue((admin.ROOT/admin.POLICY).exists())  # Mocked recovery made no real change.

    def test_remove_refuses_policy_unknown_file_and_foreign_socket(self):
        admin.install(*self.draft())
        with self.assertRaises(ValueError): admin.remove()
        self.mark_terminal()
        (admin.ROOT/'unknown').write_text('untouched')
        with self.assertRaises(ValueError): admin.remove()
        (admin.ROOT/'unknown').unlink()
        sock = socket.socket(socket.AF_UNIX); sock.bind(str(admin.SOCKET))
        try:
            with self.assertRaises(ValueError): admin.remove()
            self.assertTrue(admin.ROOT.exists())
        finally: sock.close(); admin.SOCKET.unlink()
        (admin.ROOT/admin.LOG).write_text('history'); (admin.ROOT/admin.LOG).chmod(0o600)
        admin.remove(); admin.remove(); self.assertEqual((admin.ROOT/admin.LOG).read_text(), 'history')
        self.assertTrue((admin.ROOT/admin.POLICY).exists())

    def test_partial_install_has_no_ready_and_cannot_be_reinitialized(self):
        original = admin.write_private
        def fail_ready(directory, name, data, mode):
            if name == 'ready.json': raise OSError('synthetic failure before ready')
            return original(directory, name, data, mode)
        with patch.object(admin, 'write_private', side_effect=fail_ready):
            with self.assertRaises(OSError): admin.install(*self.draft())
        self.assertTrue((admin.ROOT/admin.POLICY).exists()); self.assertFalse((admin.ROOT/'ready.json').exists())
        with self.assertRaises(OSError): admin.check_config()
        with self.assertRaises(ValueError): admin.install(*self.draft())

    def test_unsupported_directory_fsync_refuses_confirmation(self):
        admin.install(*self.draft())
        with patch.object(admin, 'sync_directory', side_effect=OSError('unsupported')):
            with self.assertRaises(OSError): admin.check_config()

    def test_tampered_ready_and_missing_ledger_refuse(self):
        admin.install(*self.draft())
        ready = admin.ROOT/'ready.json'; valid_ready = ready.read_bytes(); ready.write_text('{}'); ready.chmod(0o600)
        with self.assertRaises(ValueError): admin.check_config()
        ready.write_bytes(valid_ready); ready.chmod(0o600)
        (admin.ROOT/admin.POLICY).unlink()
        with self.assertRaises(OSError): admin.check_config()
        with self.assertRaises(ValueError): admin.install(*self.draft())

if __name__ == '__main__': unittest.main()
