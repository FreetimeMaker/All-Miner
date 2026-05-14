#include <jni.h>
#include <string>
#include <chrono>
#include <thread>
#include <vector>
#include "randomx_wrapper.h"

extern "C" JNIEXPORT jboolean JNICALL
Java_com_freetime_allminer_MinerViewModel_initRandomX(
        JNIEnv* env,
        jobject /* this */,
        jstring key,
        jstring args) {
    const char* nativeKey = env->GetStringUTFChars(key, nullptr);
    const char* nativeArgs = env->GetStringUTFChars(args, nullptr);

    bool success = RandomXEngine::getInstance().init(nativeKey, nativeArgs);

    env->ReleaseStringUTFChars(key, nativeKey);
    env->ReleaseStringUTFChars(args, nativeArgs);
    return static_cast<jboolean>(success);
}

extern "C" JNIEXPORT void JNICALL
Java_com_freetime_allminer_MinerViewModel_prepareThreads(
        JNIEnv* env,
        jobject /* this */,
        jint count) {
    RandomXEngine::getInstance().prepareThreads(count);
}

extern "C" JNIEXPORT jlong JNICALL
Java_com_freetime_allminer_MinerViewModel_performRandomXHash(
        JNIEnv* env,
        jobject /* this */,
        jint threadId,
        jbyteArray inputData) {

    if (!RandomXEngine::getInstance().isReady()) return 0;

    jsize len = env->GetArrayLength(inputData);
    jbyte* body = env->GetByteArrayElements(inputData, nullptr);

    uint8_t output[32];
    RandomXEngine::getInstance().hash(threadId, reinterpret_cast<uint8_t*>(body), len, output);

    env->ReleaseByteArrayElements(inputData, body, JNI_ABORT);

    return 1;
}

extern "C" JNIEXPORT jstring JNICALL
Java_com_freetime_allminer_MinerViewModel_getEngineVersion(
        JNIEnv* env,
        jobject /* this */) {
    return env->NewStringUTF("RandomX v1.1.10 (Multi-Threaded)");
}
