/* Feasibility only: no main, no installer and not linked into a runnable app.
 * The deprecated API is considered only for a fixed fixture stop tool that is
 * already root-owned and independently verified. It cannot bootstrap itself. */
#include <Security/Authorization.h>
#include <Security/AuthorizationTags.h>
#include <CommonCrypto/CommonDigest.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <sys/poll.h>
#include <fcntl.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>

#define FIXTURE_DIRECTORY "/Library/Application Support/FocusDeckGuardV2Fixture"
#define FIXTURE_CONTROL FIXTURE_DIRECTORY "/FocusDeckV2FixtureControl"
#define FIXTURE_CONTROL_SHA "be5529d6a0c6d2297a590b5ab20b19fa9d652237c8926db2725d00b2fe93eaad"

/* Parameterised only for temporary-file tests; this function does not elevate. */
int fixture_control_verified(const char *directory, const char *path, uid_t owner, const char *expected_sha) {
    struct stat dir, before, opened, after;
    if (lstat(directory, &dir) || !S_ISDIR(dir.st_mode) || dir.st_uid != owner || (dir.st_mode & 0077)) return 0;
    if (lstat(path, &before) || !S_ISREG(before.st_mode) || before.st_uid != owner || (before.st_mode & 0077) || before.st_size > 20*1024*1024) return 0;
    int fd = open(path, O_RDONLY | O_NOFOLLOW | O_CLOEXEC);
    if (fd < 0) return 0;
    int ok = fstat(fd, &opened) == 0 && opened.st_dev == before.st_dev && opened.st_ino == before.st_ino &&
             S_ISREG(opened.st_mode) && opened.st_uid == owner && !(opened.st_mode & 0077);
    CC_SHA256_CTX ctx; unsigned char digest[CC_SHA256_DIGEST_LENGTH]; char hex[65];
    if (ok) {
        CC_SHA256_Init(&ctx); unsigned char buf[8192]; ssize_t n;
        while ((n = read(fd, buf, sizeof(buf))) > 0) CC_SHA256_Update(&ctx, buf, (CC_LONG)n);
        if (n < 0) ok = 0;
        CC_SHA256_Final(digest, &ctx);
        for (size_t i = 0; i < sizeof(digest); ++i) snprintf(hex + i*2, 3, "%02x", digest[i]);
        if (strcmp(hex, expected_sha) != 0) ok = 0;
    }
    if (lstat(path, &after) || after.st_dev != before.st_dev || after.st_ino != before.st_ino) ok = 0;
    close(fd); return ok;
}

/* Inert unless called by a future audited native app. Exactly one fixed root-owned
 * stop executable and one literal argument; never accepts an executable or shell
 * command from a caller. This does not solve secure first-time installation. */
OSStatus fixture_direct_stop(void) {
#ifndef FIXTURE_ENABLE_UNSAFE_COMPILE_PROBE
    // The live operation is deliberately disabled. A stop tool is not a reviewed installer path.
    return errAuthorizationDenied;
#else
    if (!fixture_control_verified(FIXTURE_DIRECTORY, FIXTURE_CONTROL, 0, FIXTURE_CONTROL_SHA)) return errAuthorizationDenied;
    AuthorizationRef authorization = NULL;
    OSStatus status = AuthorizationCreate(NULL, kAuthorizationEmptyEnvironment, kAuthorizationFlagDefaults, &authorization);
    if (status != errAuthorizationSuccess || authorization == NULL) return status;
    AuthorizationItem item = {kAuthorizationRightExecute, sizeof(FIXTURE_CONTROL) - 1, (void *)FIXTURE_CONTROL, 0};
    AuthorizationRights rights = {1, &item};
    status = AuthorizationCopyRights(authorization, &rights, kAuthorizationEmptyEnvironment,
                                     kAuthorizationFlagInteractionAllowed | kAuthorizationFlagExtendRights | kAuthorizationFlagPreAuthorize, NULL);
    if (status == errAuthorizationSuccess) {
        char *arguments[] = {"--stop", NULL}; FILE *channel = NULL;
        status = AuthorizationExecuteWithPrivileges(authorization, FIXTURE_CONTROL, kAuthorizationFlagDefaults, arguments, &channel);
        if (status == errAuthorizationSuccess) {
            /* A successful spawn alone is not a confirmed stop. */
            status = errAuthorizationInternal;
            if (channel != NULL) {
                int fd = fileno(channel); struct pollfd ready = {fd, POLLIN | POLLHUP, 0};
                if (poll(&ready, 1, 10000) > 0) {
                    char response[256]; ssize_t n = read(fd, response, sizeof(response)-1);
                    if (n > 0) {
                        response[n] = '\0';
                        if (strcmp(response, "Dedicated synthetic fixture stop acknowledged; daemon is exiting.\n") == 0) status = errAuthorizationSuccess;
                    }
                }
                fclose(channel);
            }
        }
    }
    AuthorizationFree(authorization, kAuthorizationFlagDestroyRights); return status;
#endif
}
