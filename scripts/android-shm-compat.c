#define _GNU_SOURCE 1
#include <errno.h>
#include <fcntl.h>
#include <stdlib.h>
#include <sys/stat.h>
#include <sys/types.h>
#include <unistd.h>

int shm_open(const char *name, int flags, mode_t mode)
{
    char template[] = "./.pocketos-shm-XXXXXX";
    int fd;
    int saved_errno;

    (void)name;
    (void)mode;
    if ((flags & O_CREAT) == 0) {
        errno = ENOENT;
        return -1;
    }

    fd = mkstemp(template);
    if (fd < 0) {
        return -1;
    }
    if (unlink(template) == 0) {
        return fd;
    }

    saved_errno = errno;
    close(fd);
    errno = saved_errno;
    return -1;
}

int shm_unlink(const char *name)
{
    (void)name;
    return 0;
}
