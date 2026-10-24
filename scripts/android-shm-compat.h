#ifndef POCKETOS_ANDROID_SHM_COMPAT_H
#define POCKETOS_ANDROID_SHM_COMPAT_H

#include <sys/stat.h>
#include <sys/types.h>

int shm_open(const char *name, int flags, mode_t mode);
int shm_unlink(const char *name);

#endif
