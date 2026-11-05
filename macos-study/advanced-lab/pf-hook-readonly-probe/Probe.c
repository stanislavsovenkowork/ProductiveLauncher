// Read-only diagnostic. No shell, file write, PF mutation or connection data output.
#include <Security/Authorization.h>
#include <Security/AuthorizationTags.h>
#include <Security/Security.h>
#include <CoreFoundation/CoreFoundation.h>
#include <sys/stat.h>
#include <poll.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <unistd.h>

static int verify_pfctl(void) {
    const char *paths[] = {"/sbin", "/sbin/pfctl"};
    for (int i = 0; i < 2; i++) {
        struct stat st;
        if (lstat(paths[i], &st) || st.st_uid != 0 || (st.st_mode & 0022)) return 0;
        if (i == 0 ? !S_ISDIR(st.st_mode) : !S_ISREG(st.st_mode)) return 0;
    }
    CFURLRef url = CFURLCreateWithFileSystemPath(NULL, CFSTR("/sbin/pfctl"), kCFURLPOSIXPathStyle, false);
    SecStaticCodeRef code = NULL; SecRequirementRef requirement = NULL;
    OSStatus result = url ? SecStaticCodeCreateWithPath(url, kSecCSDefaultFlags, &code) : errSecParam;
    if (result == errSecSuccess) result = SecRequirementCreateWithString(CFSTR("identifier \"com.apple.pfctl\" and anchor apple"), kSecCSDefaultFlags, &requirement);
    if (result == errSecSuccess) result = SecStaticCodeCheckValidity(code, kSecCSStrictValidate, requirement);
    if (requirement) CFRelease(requirement); if (code) CFRelease(code); if (url) CFRelease(url);
    return result == errSecSuccess;
}

static int read_pf(AuthorizationRef auth, const char *kind, char *output, size_t capacity) {
    const char tool[] = "/sbin/pfctl";
    char *args[] = {"-s", (char *)kind, NULL}; FILE *channel = NULL;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    OSStatus result = AuthorizationExecuteWithPrivileges(auth, tool, kAuthorizationFlagDefaults, args, &channel);
#pragma clang diagnostic pop
    if (result != errAuthorizationSuccess || !channel) return 0;
    size_t used = 0; int complete = 0;
    while (used + 1 < capacity) {
        struct pollfd ready = {fileno(channel), POLLIN | POLLHUP, 0};
        if (poll(&ready, 1, 5000) <= 0) break;
        ssize_t n = read(fileno(channel), output + used, capacity - used - 1);
        if (n == 0) { complete = 1; break; }
        if (n < 0) break; used += (size_t)n;
    }
    output[used] = '\0'; fclose(channel); return complete;
}

static int nonempty_lines(const char *text) {
    int lines = 0, nonspace = 0;
    for (const char *p = text; *p; p++) {
        if (*p == '\n') { if (nonspace) lines++; nonspace = 0; }
        else if (*p != ' ' && *p != '\t' && *p != '\r') nonspace = 1;
    }
    return lines + (nonspace ? 1 : 0);
}
static int parse_info(char *output, int *status, int *entries) {
    char *line = output; int foundStatus = 0, foundEntries = 0;
    while (*line) {
        char *end = strchr(line, '\n'); if (end) *end = '\0';
        while (*line == ' ' || *line == '\t') line++;
        if (strncmp(line, "Status: Enabled", 15) == 0) { *status = 1; foundStatus++; }
        else if (strncmp(line, "Status: Disabled", 16) == 0) { *status = 0; foundStatus++; }
        else if (strncmp(line, "current entries", 15) == 0) {
            char *number = line + 15; while (*number == ' ' || *number == '\t') number++;
            char *after = NULL; long count = strtol(number, &after, 10);
            if (after != number && count >= 0 && count <= 2147483647L) { *entries = (int)count; foundEntries++; }
        }
        if (!end) break; line = end + 1;
    }
    return foundStatus == 1 && foundEntries == 1;
}

// status 1=enabled, 0=disabled; entries are from PF's aggregate current count.
// Any parse, timeout, or authorization failure is unknown; no data is persisted.
// Empty rule output is not proof of success: execute API does not report exit status.
int focusdeck_pf_readonly(int *status, int *entries, int *rules, int *nat, int *anchors, int *apple_wildcard) {
    *status = -1; *entries = -1; *rules = -1; *nat = -1; *anchors = -1;
    *apple_wildcard = -1;
    if (!verify_pfctl()) return 3;
    AuthorizationRef auth = NULL;
    OSStatus result = AuthorizationCreate(NULL, kAuthorizationEmptyEnvironment, kAuthorizationFlagDefaults, &auth);
    if (result != errAuthorizationSuccess || !auth) return 3;
    const char tool[] = "/sbin/pfctl";
    AuthorizationItem item = {kAuthorizationRightExecute, sizeof(tool)-1, (void *)tool, 0};
    AuthorizationRights rights = {1, &item};
    result = AuthorizationCopyRights(auth, &rights, kAuthorizationEmptyEnvironment,
        kAuthorizationFlagInteractionAllowed | kAuthorizationFlagExtendRights | kAuthorizationFlagPreAuthorize, NULL);
    if (result != errAuthorizationSuccess) { AuthorizationFree(auth, kAuthorizationFlagDestroyRights); return result == errAuthorizationCanceled ? 1 : 3; }
    if (!verify_pfctl()) { AuthorizationFree(auth, kAuthorizationFlagDestroyRights); return 3; }
    int outcome = 3;
    char output[65536] = {0};
    if (read_pf(auth, "info", output, sizeof(output))) {
        if (parse_info(output, status, entries)) outcome = 0;
    }
    const char *kinds[] = {"rules", "nat", "Anchors"}; int *counts[] = {rules, nat, anchors};
    for (int i = 0; i < 3; i++) {
        memset(output, 0, sizeof(output));
        if (read_pf(auth, kinds[i], output, sizeof(output))) {
            int count = nonempty_lines(output);
            if (count > 0) {
                *counts[i] = count;
                if (i == 0) {
                    *apple_wildcard = 0;
                    char *line = output;
                    while (*line) {
                        char *end = strchr(line, '\n'); if (end) *end = '\0';
                        while (*line == ' ' || *line == '\t') line++;
                        if (!strcmp(line, "anchor \"com.apple/*\" all") || !strcmp(line, "anchor \"com.apple/*\"")) *apple_wildcard = 1;
                        if (!end) break; line = end + 1;
                    }
                }
            }
        }
    }
    memset(output, 0, sizeof(output));
    AuthorizationFree(auth, kAuthorizationFlagDestroyRights); return outcome;
}
