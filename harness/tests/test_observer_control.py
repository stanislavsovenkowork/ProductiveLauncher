import importlib.util
import json
import pathlib
import plistlib
import tempfile
import unittest
from unittest.mock import patch

spec = importlib.util.spec_from_file_location('observer_control',pathlib.Path(__file__).parents[1]/'native/control.py')
control = importlib.util.module_from_spec(spec)
spec.loader.exec_module(control)


class ObserverControlTests(unittest.TestCase):
    def test_explicit_paths_and_no_automatic_receiver_discovery(self):
        with tempfile.TemporaryDirectory() as directory:
            result = control.prepare(directory,'/bin/echo')
            config = json.loads(pathlib.Path(result['config']).read_text())
            self.assertIsNone(config['sessionSnapshot'])
            self.assertFalse(config['network'])
            with open(result['plist'],'rb') as stream:job = plistlib.load(stream)
            self.assertNotIn('--session-snapshot',job['ProgramArguments'])
            self.assertEqual(job['StandardErrorPath'],'/dev/null')
            self.assertEqual(pathlib.Path(config['binary']).stat().st_mode & 0o777,0o700)
            with self.assertRaises(FileExistsError):control.prepare(directory,'/bin/echo')

    def test_install_refuses_changed_executable(self):
        with tempfile.TemporaryDirectory() as directory:
            result = control.prepare(directory,'/bin/echo')
            config = json.loads(pathlib.Path(result['config']).read_text())
            pathlib.Path(config['binary']).write_text('# changed')
            with self.assertRaises(ValueError):control.install(directory)

    def test_mock_install_uninstall_preserve_data(self):
        with tempfile.TemporaryDirectory() as directory:
            private = str(pathlib.Path(directory)/'private')
            home = pathlib.Path(directory)/'home'
            control.prepare(private,'/bin/echo')
            fake = control.subprocess.CompletedProcess([],0,stdout='loaded',stderr='')
            with patch.object(pathlib.Path,'home',return_value=home),patch.object(control.subprocess,'run',return_value=fake):
                self.assertTrue(control.install(private)['loaded'])
                self.assertFalse(control.uninstall(private)['installed'])
                self.assertTrue((pathlib.Path(private)/'observer-config.json').exists())


if __name__ == '__main__':unittest.main()
