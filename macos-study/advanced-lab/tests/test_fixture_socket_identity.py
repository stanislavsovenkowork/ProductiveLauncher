import importlib.util
import json
import os
import pathlib
import socket
import tempfile
import unittest

path = pathlib.Path(__file__).resolve().parents[1] / 'v2-fixture-admin.py'
spec = importlib.util.spec_from_file_location('fixture_admin', path)
admin = importlib.util.module_from_spec(spec); spec.loader.exec_module(admin)

class FixtureSocketIdentityTests(unittest.TestCase):
    def test_only_recorded_inode_matches(self):
        with tempfile.TemporaryDirectory(prefix='focusdeck-socket-') as d:
            a, b = pathlib.Path(d)/'a', pathlib.Path(d)/'b'
            first, second = socket.socket(socket.AF_UNIX), socket.socket(socket.AF_UNIX)
            try:
                first.bind(str(a)); second.bind(str(b)); info = os.lstat(a)
                marker = json.dumps({'device': info.st_dev, 'inode': info.st_ino}).encode()
                self.assertTrue(admin.socket_identity_matches(a, marker, os.getuid()))
                self.assertFalse(admin.socket_identity_matches(b, marker, os.getuid()))
                self.assertFalse(admin.socket_identity_matches(a, marker, os.getuid()+1))
                self.assertFalse(admin.socket_identity_matches(a, b'bad', os.getuid()))
                regular = pathlib.Path(d)/'file'; regular.write_text('fixture')
                self.assertFalse(admin.socket_identity_matches(regular, marker, os.getuid()))
            finally: first.close(); second.close()

if __name__ == '__main__': unittest.main()
