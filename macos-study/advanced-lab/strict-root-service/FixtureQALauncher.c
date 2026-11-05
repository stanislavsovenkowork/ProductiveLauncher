// Fixed one-shot QA action. This is not a general privileged command channel.
#include <Security/Authorization.h>
#include <Security/AuthorizationTags.h>
#include <Security/Security.h>
#include <CoreFoundation/CoreFoundation.h>
#include <CommonCrypto/CommonDigest.h>
#include <sys/stat.h>
#include <fcntl.h>
#include <poll.h>
#include <unistd.h>
#include <errno.h>
#include <stdio.h>
#include <string.h>
#include <time.h>
#include <dirent.h>

static const char *stage = "/Library/PrivilegedHelperTools/FocusDeckStrictQAStage-cc5f9970";
static const char *copy = "/Library/PrivilegedHelperTools/FocusDeckStrictQAStage-cc5f9970/bootstrap.sh";
static const char *source = "/Users/stanislav/.codex/worktrees/ce11/ProductiveLauncher/macos-study/advanced-lab/strict-root-service/fixture-qa-bootstrap.sh";
static const char *expected = "93e97895c537a1eb0deb2c4bfa08ad328b80ca6ddb1b7f6c04b0e66add95b5be";

static int apple(const char *path) {
    struct stat s;
    if (lstat(path,&s) || s.st_uid != 0 || !S_ISREG(s.st_mode) || (s.st_mode & 0022)) return 0;
    CFURLRef url = CFURLCreateFromFileSystemRepresentation(NULL, (const UInt8 *)path, strlen(path), false);
    SecStaticCodeRef code = NULL; SecRequirementRef req = NULL;
    OSStatus status = url ? SecStaticCodeCreateWithPath(url, 0, &code) : errSecParam;
    if (!status) status = SecRequirementCreateWithString(CFSTR("anchor apple"), 0, &req);
    if (!status) status = SecStaticCodeCheckValidity(code, kSecCSStrictValidate, req);
    if (req) CFRelease(req); if (code) CFRelease(code); if (url) CFRelease(url);
    return status == errSecSuccess;
}
static int fixed_system_tools(void) {
    const char *paths[] = {"/usr/bin/env", "/bin/mkdir", "/usr/bin/install", "/bin/bash", "/bin/rm", "/bin/rmdir", "/sbin/sha256", "/usr/bin/id", "/usr/bin/codesign", "/usr/bin/sed", "/usr/bin/stat", "/bin/cp", "/bin/chmod"};
    for (size_t i=0; i<sizeof(paths)/sizeof(paths[0]); i++) if (!apple(paths[i])) return 0;
    return 1;
}
static int safe_copy(void) {
    struct stat d, s;
    if (lstat(stage,&d) || d.st_uid != 0 || !S_ISDIR(d.st_mode) || (d.st_mode & 0022)) return 0;
    int fd = open(copy,O_RDONLY|O_NOFOLLOW|O_CLOEXEC);
    if (fd < 0) return 0;
    if (fstat(fd,&s) || s.st_uid != 0 || !S_ISREG(s.st_mode) || (s.st_mode & 0022) || s.st_nlink != 1 || s.st_size <= 0 || s.st_size > 65536) { close(fd); return 0; }
    unsigned char bytes[65536], digest[CC_SHA256_DIGEST_LENGTH]; size_t n = 0;
    while (n < sizeof(bytes)) { ssize_t r = read(fd,bytes+n,sizeof(bytes)-n); if (r < 0) { close(fd); return 0; } if (!r) break; n += (size_t)r; }
    close(fd); if (n != (size_t)s.st_size) return 0;
    CC_SHA256(bytes,(CC_LONG)n,digest); char hex[65];
    for (int i=0; i<CC_SHA256_DIGEST_LENGTH; i++) snprintf(hex+i*2,3,"%02x",digest[i]);
    return !strcmp(hex,expected);
}
static int safe_empty_stage(void) {
    struct stat s; if (lstat(stage,&s) || s.st_uid != 0 || !S_ISDIR(s.st_mode) || (s.st_mode & 0022)) return 0;
    DIR *d=opendir(stage); if (!d) return 0; struct dirent *entry; int ok=1;
    while ((entry=readdir(d))) if (strcmp(entry->d_name,".") && strcmp(entry->d_name,"..")) { ok=0; break; }
    closedir(d); return ok;
}
static int execute(AuthorizationRef auth, char *const args[], int seconds, char *out, size_t capacity) {
    FILE *pipe = NULL; OSStatus s;
#pragma clang diagnostic push
#pragma clang diagnostic ignored "-Wdeprecated-declarations"
    s = AuthorizationExecuteWithPrivileges(auth,"/usr/bin/env",kAuthorizationFlagDefaults,args,&pipe);
#pragma clang diagnostic pop
    if (s != errAuthorizationSuccess || !pipe) return 0;
    struct timespec start, now; clock_gettime(CLOCK_MONOTONIC,&start); size_t n=0; int ok=0;
    for (;;) {
        clock_gettime(CLOCK_MONOTONIC,&now); int left = seconds - (int)(now.tv_sec-start.tv_sec);
        if (left <= 0) break;
        struct pollfd p = {fileno(pipe),POLLIN|POLLHUP,0}; int ready = poll(&p,1,left*1000);
        if (ready <= 0) break;
        char buffer[1024]; ssize_t r = read(fileno(pipe),buffer,sizeof(buffer));
        if (r == 0) { ok=1; break; } if (r < 0) { if (errno == EINTR) continue; break; }
        if (n+(size_t)r >= capacity) break; memcpy(out+n,buffer,(size_t)r); n+=(size_t)r;
    }
    out[n]=0; fclose(pipe); return ok;
}
// 0 = bounded fixture succeeded and staging is gone; 1 = denied; 2 = unknown.
int focusdeck_fixture_qa(void) {
    struct stat s; if (!fixed_system_tools() || lstat(stage,&s) == 0 || errno != ENOENT) return 2;
    AuthorizationRef auth=NULL;
    if (AuthorizationCreate(NULL,kAuthorizationEmptyEnvironment,kAuthorizationFlagDefaults,&auth) != errAuthorizationSuccess || !auth) return 2;
    const char tool[]="/usr/bin/env"; AuthorizationItem item={kAuthorizationRightExecute,sizeof(tool)-1,(void *)tool,0}; AuthorizationRights rights={1,&item};
    OSStatus r=AuthorizationCopyRights(auth,&rights,kAuthorizationEmptyEnvironment,kAuthorizationFlagInteractionAllowed|kAuthorizationFlagExtendRights|kAuthorizationFlagPreAuthorize,NULL);
    if (r != errAuthorizationSuccess) { AuthorizationFree(auth,kAuthorizationFlagDestroyRights); return r==errAuthorizationCanceled ? 1 : 2; }
    char output[8192] = {0};
    char *mk[]={"-i","PATH=/usr/bin:/bin:/usr/sbin:/sbin","LANG=C","/bin/bash","-c","/bin/mkdir -m 755 /Library/PrivilegedHelperTools/FocusDeckStrictQAStage-cc5f9970 && printf 'STAGE_CREATED\\n'",NULL};
    char *install[]={"-i","PATH=/usr/bin:/bin:/usr/sbin:/sbin","LANG=C","/bin/bash","-c","/usr/bin/install -m 755 /Users/stanislav/.codex/worktrees/ce11/ProductiveLauncher/macos-study/advanced-lab/strict-root-service/fixture-qa-bootstrap.sh /Library/PrivilegedHelperTools/FocusDeckStrictQAStage-cc5f9970/bootstrap.sh && printf 'STAGE_COPIED\\n'",NULL};
    char *run[]={"-i","PATH=/usr/bin:/bin:/usr/sbin:/sbin","LANG=C","/bin/bash",(char *)copy,NULL};
    char *remove[]={"-i","PATH=/usr/bin:/bin:/usr/sbin:/sbin","LANG=C","/bin/rm",(char *)copy,NULL};
    char *rmdir_args[]={"-i","PATH=/usr/bin:/bin:/usr/sbin:/sbin","LANG=C","/bin/rmdir",(char *)stage,NULL};
    int ok=execute(auth,mk,5,output,sizeof(output)) && strstr(output,"STAGE_CREATED") && safe_empty_stage();
    if (ok) ok=execute(auth,install,5,output,sizeof(output)) && strstr(output,"STAGE_COPIED") && safe_copy();
    if (ok) ok=execute(auth,run,130,output,sizeof(output)) && strstr(output,"\"result\": \"PASS\"") && strstr(output,"SUPERVISOR PASS") && strstr(output,"CLEANUP PASS");
    if (ok) ok=execute(auth,remove,5,output,sizeof(output)) && execute(auth,rmdir_args,5,output,sizeof(output)) && lstat(stage,&s) != 0 && errno == ENOENT;
    AuthorizationFree(auth,kAuthorizationFlagDestroyRights); return ok ? 0 : 2;
}
