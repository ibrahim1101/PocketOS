#include <jni.h>

extern "C" JNIEXPORT jstring JNICALL
Java_com_pocketos_app_NativeVmBridge_nativeStatus(JNIEnv* env, jobject /* instance */) {
    return env->NewStringUTF("JNI_BRIDGE_READY;QEMU_NOT_LINKED");
}
