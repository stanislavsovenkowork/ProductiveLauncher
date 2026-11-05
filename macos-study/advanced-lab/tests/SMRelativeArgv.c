#include <unistd.h>
#include <stdio.h>
// Exercise the launchd-style relative argv[0] without launching a privileged service.
int main(int argc, char **argv) {
    if (argc != 2) return 2;
    char *args[] = {"Contents/Library/HelperTools/SMCalendarFixtureDaemon", NULL};
    execv(argv[1], args);
    perror("execv");
    return 3;
}
