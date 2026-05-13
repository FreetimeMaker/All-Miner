#include <jni.h>
#include <string>
#include <chrono>
#include <thread>
#include <vector>
#include "randomx_wrapper.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freetime_allminer_MainActivity_00024MinerViewModel_initRandomX(
        JNIEnv* env,
        jobject /* this */,
        jstring key) {
    const char* nativeKey = env->GetStringUTFChars(key, nullptr);
    bool success = RandomXEngine::getInstance().init(nativeKey);
    env->ReleaseStringUTFChars(key, nativeKey);
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_freetime_allminer_MainActivity_00024MinerViewModel_performRandomXHash(
        JNIEnv* env,
        jobject /* this */,
        jbyteArray inputData) {

    if (!RandomXEngine::getInstance().isReady()) return 0;

    jsize len = env->GetArrayLength(inputData);
    jbyte* body = env->GetByteArrayElements(inputData, nullptr);

    uint8_t output[32];
    RandomXEngine::getInstance().hash(reinterpret_cast<uint8_t*>(body), len, output);

    env->ReleaseByteArrayElements(inputData, body, JNI_ABORT);

    // Wir geben "1" zurück, um einen erfolgreichen Hash anzuzeigen
    return 1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freetime_allminer_MainActivity_00024MinerViewModel_getEngineVersion(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("RandomX v1.1.10 (Native Wrapper)");
}
