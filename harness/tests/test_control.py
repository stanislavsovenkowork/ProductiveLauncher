import importlib.util
from pathlib import Path
import tempfile
import unittest
from unittest.mock import patch

spec=importlib.util.spec_from_file_location("local_control",Path(__file__).parents[1]/"control.py")
c=importlib.util.module_from_spec(spec);spec.loader.exec_module(c)

class LocalControlTests(unittest.TestCase):
    def snapshot(self,loaded):
        return {"services":{name:{"loaded":loaded,"installed":loaded} for name in c.SERVICES}}

    def test_stop_preserves_history_and_never_reinstalls(self):
        with tempfile.TemporaryDirectory() as directory:
            report=Path(directory)/"history.json";report.write_text("known history")
            with patch.object(c,"status",side_effect=[self.snapshot(True),self.snapshot(False)]), patch.object(c,"invoke",return_value={}) as action:
                result=c.change(directory,False)
            self.assertTrue(result["complete"])
            self.assertFalse(result["automaticReinstallAfterStop"])
            self.assertEqual(report.read_text(),"known history")
            self.assertEqual([call.args[1] for call in action.call_args_list],["uninstall"]*3)

    def test_start_is_explicit_and_idempotent_when_loaded(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(c,"status",return_value=self.snapshot(True)),patch.object(c,"invoke") as action:
            result=c.change(directory,True)
            self.assertTrue(result["complete"]);action.assert_not_called()

    def test_failed_stop_is_visible(self):
        with tempfile.TemporaryDirectory() as directory, patch.object(c,"status",return_value=self.snapshot(True)),patch.object(c,"invoke",side_effect=ValueError("failure")):
            result=c.change(directory,False)
            self.assertFalse(result["complete"])
            self.assertEqual(set(result["failures"]),set(c.SERVICES))

    def test_control_audit_does_not_invent_work_or_start_background_collection(self):
        import json
        with tempfile.TemporaryDirectory() as directory, patch.object(c,"status",side_effect=[self.snapshot(True),self.snapshot(False)]),patch.object(c,"invoke",return_value={}):
            c.change(directory,False)
            event=json.loads((Path(directory)/"control-events.jsonl").read_text())
            self.assertEqual(event["type"],"disabled")
            self.assertIsNone(event["sessionId"])
            self.assertTrue(all(value is None for value in event["metrics"].values()))

    def test_concurrent_control_is_refused(self):
        import fcntl
        with tempfile.TemporaryDirectory() as directory:
            with (Path(directory)/".control-operation.lock").open("a+") as lock:
                fcntl.flock(lock,fcntl.LOCK_EX|fcntl.LOCK_NB)
                with self.assertRaises(ValueError):c.change(directory,False)

    def test_deploy_has_private_explicit_stop_and_start_no_activation(self):
        with tempfile.TemporaryDirectory() as directory:
            result=c.deploy(directory)
            self.assertFalse(result["servicesChanged"])
            command=Path(result["stopCommand"])
            self.assertEqual(command.stat().st_mode & 0o777,0o700)
            self.assertIn(" stop ",command.read_text())
            self.assertNotIn("launchctl",command.read_text())

if __name__=="__main__":unittest.main()
