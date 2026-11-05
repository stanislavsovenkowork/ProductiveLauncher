# Fixture-only root QA: not ready to run

This is an unfinished, review-only preparation. **Do not open or execute
`FixtureQALauncher`: its staging design was rejected during review.** A
root-readable source could be copied to a world-readable stage if the source
were replaced. Cleanup after partial failures is also unproved. The source
hashes below describe an earlier checkpoint, not the current files.

Do not execute the bootstrap
with root privileges: there is no reviewed, working root-owned staging and
authorization entry point. No root QA process, job, socket, store or helper was
created by this preparation. Production v5, personal targets and network were
not changed.

The intended scope is the single signed GuardFixture (CDHash
`3e481e3d08c534a55081e55313fa2768f44d57bc`) in
`/Library/PrivilegedHelperTools/FocusDeckStrictQA/GuardFixture`. The proposed
bootstrap refuses any pre-existing QA helper, store or socket. If authorized in
the future, it would create only that helper directory, the separate root store
`/Library/Application Support/FocusDeckStrictQA`, and socket
`/tmp/focusdeck-strict-root-qa-501.sock`; it does not create a launchd job.
Proposed policy is 90 seconds with a 120-second service limit. It would attempt
to confirm early-exit refusal, a new client's retained exit deadline, signal-0
permission denial, reopen without another binding and natural release.

Independent review identified the missing trusted staging path and the need
for an independent bounded supervisor. The new supervisor code compiles but
has not been independently reviewed or exercised; its child/group cleanup and
crash handling must not be treated as proved. Abrupt supervisor or machine
failure may leave QA state requiring careful manual recovery. There remains a
PID identity-check-to-signal race; no claim of atomic target signalling is
made. Mock and syntax checks are not live root proof. No actual user interface
or installed production service is tested by this fixture.

Source SHA-256 at the stop boundary:

* `fixture-qa-bootstrap.sh`: `7f398ba9c61dc8abf52a270f2bae3b6a63efe46d9f0b213614c67c2891e6b0af`
* `fixture-qa-driver.py`: `0e40d00713c2c284ce961f2ec69aff42b324bd0bdd228997c893765de1852a78`
* `FixtureQASupervisor.c`: `e6c97241e076f9a957b87a5743106f17164a697cfb83acb4176e87f5497fe6d9`
* `StrictFixtureDaemon.swift`: `48b5267ee448832ff4c55852e7237884d2cded7dd9fd382ce5cd4d3957016bad`

ShellCheck and Python syntax checks passed; the daemon, client and supervisor
were separately compiled and signed for QA. No live integration, setup,
rollback, watchdog, expiry or cleanup outcome was tested.
The unique temporary build directories referenced by the draft bootstrap were
removed at the stop boundary; rebuilding and a new hash review are required.
One earlier local-only supervisor artifact remains at
`/tmp/focusdeck-fixture-supervisor-QA/FixtureQASupervisor`; the directory was
not uniquely created, so it was not removed. It is not running or installed.
