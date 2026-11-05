// One-shot root QA supervisor. It owns only the two process groups it starts.
// The child PID remains unreaped until we decide not to signal it, preventing
// PID reuse before a group signal. No launchd job or network operations.
#include <sys/types.h>
#include <sys/stat.h>
#include <sys/wait.h>
#include <mach/mach_time.h>
#include <spawn.h>
#include <signal.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>
#include <errno.h>
#include <grp.h>

extern char **environ;
static volatile sig_atomic_t interrupted = 0;
static void stop_request(int signal_number) { (void)signal_number; interrupted = 1; }
static double continuous(void) {
    mach_timebase_info_data_t base;
    if (mach_timebase_info(&base) != KERN_SUCCESS || !base.denom) return -1;
    return (double)mach_continuous_time() * base.numer / base.denom / 1e9;
}
static int spawn_group(pid_t *child, const char *path, char *const args[]) {
    posix_spawnattr_t attr;
    if (posix_spawnattr_init(&attr)) return -1;
    int status = posix_spawnattr_setflags(&attr, POSIX_SPAWN_SETPGROUP);
    if (!status) status = posix_spawnattr_setpgroup(&attr, 0);
    if (!status) status = posix_spawn(child, path, NULL, &attr, args, environ);
    posix_spawnattr_destroy(&attr);
    return status ? -1 : 0;
}
static int spawn_user_driver(pid_t *child, char *const args[]) {
    pid_t pid = fork();
    if (pid < 0) return -1;
    if (pid == 0) {
        if (setpgid(0, 0) || setgroups(0, NULL) || setgid(20) || setuid(501)) _exit(126);
        char *const clean[] = {"PATH=/usr/bin:/bin", "LANG=C", NULL};
        execve("/usr/bin/python3", args, clean);
        _exit(127);
    }
    *child = pid;
    return 0;
}
static int peek(pid_t child, siginfo_t *info) {
    memset(info, 0, sizeof(*info));
    if (waitid(P_PID, child, info, WEXITED|WNOHANG|WNOWAIT)) return errno == EINTR ? 0 : -1;
    return info->si_pid == child ? 1 : 0;
}
static int end_child(pid_t child) {
    if (child <= 1) return 1;
    siginfo_t info; int state = peek(child, &info);
    if (state < 0) return 0;
    // Only our unreaped child can own this process group ID.
    if (kill(-child, SIGTERM) && errno != ESRCH) return 0;
    double now = continuous(); if (now < 0) return 0;
    double until = now + 0.25;
    while ((now = continuous()) >= 0 && now < until) {
        usleep(50000);
    }
    if (kill(-child, SIGKILL) && errno != ESRCH) return 0;
    now = continuous(); if (now < 0) return 0;
    until = now + 2;
    while ((now = continuous()) >= 0 && now < until) {
        int status; pid_t r = waitpid(child, &status, WNOHANG);
        if (r == child) return 1;
        if (r < 0 && errno != EINTR) return 0;
        usleep(50000);
    }
    return 0;
}
int main(int argc, char **argv) {
    if (geteuid() != 0 || argc != 2 || strcmp(argv[1], "--run")) return 2;
    signal(SIGTERM, stop_request); signal(SIGINT, stop_request); signal(SIGCHLD, SIG_DFL);
    const char *base = "/Library/PrivilegedHelperTools/FocusDeckStrictQA";
    const char *daemon = "/Library/PrivilegedHelperTools/FocusDeckStrictQA/StrictRootQADaemon";
    const char *socket_path = "/tmp/focusdeck-strict-root-qa-501.sock";
    char *daemon_args[] = {(char *)daemon, "--serve", NULL};
    pid_t daemon_pid = 0, driver_pid = 0;
    int success = 0; siginfo_t info_child;
    double start = continuous();
    if (start < 0 || spawn_group(&daemon_pid, daemon, daemon_args)) goto done;
    double now;
    while (!interrupted && (now = continuous()) >= start && now - start < 5) {
        struct stat info;
        if (!lstat(socket_path, &info) && info.st_uid == 0 && S_ISSOCK(info.st_mode)) break;
        int state = peek(daemon_pid, &info_child);
        if (state != 0) goto done;
        usleep(50000);
    }
    struct stat info;
    if (interrupted || lstat(socket_path, &info) || info.st_uid != 0 || !S_ISSOCK(info.st_mode)) goto done;
    char pid_string[32]; snprintf(pid_string, sizeof(pid_string), "%d", daemon_pid);
    char *driver_args[] = {"/usr/bin/python3",
        "/Library/PrivilegedHelperTools/FocusDeckStrictQA/fixture-qa-driver.py",
        "/tmp/focusdeck-strict-ui-qa.VMc4B6gu/StrictRootQAClient",
        "b82613d50f3dbf7bf24b5395e6b0c5e74d0b16f0",
        "/Library/PrivilegedHelperTools/FocusDeckStrictQA/GuardFixture", pid_string, NULL};
    (void)base;
    if (spawn_user_driver(&driver_pid, driver_args)) goto done;
    while (!interrupted && (now = continuous()) >= start && now - start < 115) {
        int d = peek(daemon_pid, &info_child);
        if (d != 0) goto done;
        int r = peek(driver_pid, &info_child);
        if (r != 0) { success = r == 1 && info_child.si_code == CLD_EXITED && info_child.si_status == 0; goto done; }
        usleep(100000);
    }
done:
    if (driver_pid > 1 && !end_child(driver_pid)) success = 0;
    if (daemon_pid > 1 && !end_child(daemon_pid)) success = 0;
    puts(success ? "SUPERVISOR PASS: synthetic driver finished and child stopped" : "SUPERVISOR UNKNOWN OR FAIL: inspect owned QA scope");
    return success ? 0 : 1;
}
