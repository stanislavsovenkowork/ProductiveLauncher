// Fixed, read-only capability probe; never accepts a command or arguments.
#include <Security/Authorization.h>
#include <Security/AuthorizationTags.h>
#include <Security/Security.h>
#include <CoreFoundation/CoreFoundation.h>
#include <sys/stat.h>
#include <poll.h>
#include <stdio.h>
#include <string.h>
#include <unistd.h>

static int safe_path(void) {
    const char *paths[] = {"/usr", "/usr/bin", "/usr/bin/id"};
    for (int i = 0; i < 3; i++) {
        struct stat st;
        if (lstat(paths[i], &st) || st.st_uid != 0 || (st.st_mode & 0022)) return 0;
        if (i < 2 ? !S_ISDIR(st.st_mode) : !S_ISREG(st.st_mode)) return 0;
    }
    CFURLRef url = CFURLCreateWithFileSystemPath(NULL, CFSTR("/usr/bin/id"), kCFURLPOSIXPathStyle, false);
    SecStaticCodeRef code = NULL; SecRequirementRef requirement = NULL;
    OSStatus result = url ? SecStaticCodeCreateWithPath(url, kSecCSDefaultFlags, &code) : errSecParam;
    if (result == errSecSuccess) result = SecRequirementCreateWithString(CFSTR("identifier \"com.apple.id\" and anchor apple"), kSecCSDefaultFlags, &requirement);
    if (result == errSecSuccess) result = SecStaticCodeCheckValidity(code, kSecCSStrictValidate, requirement);
    if (requirement) CFRelease(requirement); if (code) CFRelease(code); if (url) CFRelease(url);
    return result == errSecSuccess;
}

// 0 means the fixed Apple binary reported effective UID 0; every other value
// is inconclusive or denied. No installation, daemon, files or network changes.
int focusdeck_root_probe(void) {
    if (!safe_path()) return 3;
    AuthorizationRef auth = NULL;
    OSStatus result = AuthorizationCreate(NULL, kAuthorizationEmptyEnvironment, kAuthorizationFlagDefaults, &auth);
    if (result != errAuthorizationSuccess || !auth) return 3;
    const char tool[] = "/usr/bin/id";
    AuthorizationItem item = {kAuthorizationRightExecute, sizeof(tool)-1, (void *)tool, 0};
    AuthorizationRights rights = {1, &item};
    result = AuthorizationCopyRights(auth, &rights, kAuthorizationEmptyEnvironment,
        kAuthorizationFlagInteractionAllowed | kAuthorizationFlagExtendRights | kAuthorizationFlagPreAuthorize, NULL);
    if (result != errAuthorizationSuccess) { AuthorizationFree(auth, kAuthorizationFlagDestroyRights); return result == errAuthorizationCanceled ? 1 : 3; }
    if (!safe_path()) { AuthorizationFree(auth, kAuthorizationFlagDestroyRights); return 3; }
    char *args[] = {"-u", NULL}; FILE *channel = NULL;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    result = AuthorizationExecuteWithPrivileges(auth, tool, kAuthorizationFlagDefaults, args, &channel);
#pragma clang diagnostic pop
    int outcome = 3;
    if (result == errAuthorizationSuccess && channel) {
        struct pollfd ready = {fileno(channel), POLLIN | POLLHUP, 0};
        if (poll(&ready, 1, 5000) > 0) {
            char output[32] = {0}; ssize_t n = read(fileno(channel), output, sizeof(output)-1);
            if (n == 2 && memcmp(output, "0\n", 2) == 0) outcome = 0;
        }
        fclose(channel);
    }
    AuthorizationFree(auth, kAuthorizationFlagDestroyRights);
    return outcome;
}
