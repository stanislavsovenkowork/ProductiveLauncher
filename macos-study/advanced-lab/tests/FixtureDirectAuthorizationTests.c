#include <CommonCrypto/CommonDigest.h>
#include <Security/Authorization.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>
#include <fcntl.h>
#include <stdio.h>
#include <stdlib.h>
#include <string.h>
#include <assert.h>
int fixture_control_verified(const char *, const char *, uid_t, const char *);
OSStatus fixture_direct_stop(void);
int main(void) {
    assert(fixture_direct_stop() == errAuthorizationDenied); // no prompt or privileged execution
    char dir[] = "/tmp/focusdeck-direct-auth.XXXXXX"; assert(mkdtemp(dir));
    char file[512], linkpath[512]; snprintf(file,sizeof(file),"%s/control",dir); snprintf(linkpath,sizeof(linkpath),"%s/link",dir);
    int fd=open(file,O_WRONLY|O_CREAT|O_EXCL,0700); assert(fd>=0); const char *data="synthetic fixture control";
    assert(write(fd,data,strlen(data))==(ssize_t)strlen(data)); assert(close(fd)==0);
    unsigned char digest[CC_SHA256_DIGEST_LENGTH]; char hex[65]; CC_SHA256(data,(CC_LONG)strlen(data),digest);
    for (size_t i=0;i<sizeof(digest);++i) snprintf(hex+i*2,3,"%02x",digest[i]);
    assert(fixture_control_verified(dir,file,getuid(),hex));
    assert(!fixture_control_verified(dir,file,getuid()+1,hex));
    assert(!fixture_control_verified(dir,file,getuid(),"0000000000000000000000000000000000000000000000000000000000000000"));
    assert(symlink(file,linkpath)==0); assert(!fixture_control_verified(dir,linkpath,getuid(),hex));
    assert(chmod(file,0755)==0); assert(!fixture_control_verified(dir,file,getuid(),hex));
    assert(unlink(linkpath)==0); assert(unlink(file)==0); assert(rmdir(dir)==0);
    puts("Direct C feasibility: fake owner/hash/mode/symlink checks passed; no Authorization API called and no root action."); return 0;
}
