#include "randomx_wrapper.h"
#include <vector>
#include <cstring>
#include <android/log.h>
#include <mutex>
#include "randomx.h"

#define LOG_TAG "RandomXEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

static randomx_cache* myCache = nullptr;
static randomx_dataset* myDataset = nullptr;
static std::mutex vmsMutex;

bool RandomXEngine::init(const std::string& key, const std::string& args) {
    std::lock_guard<std::mutex> lock(vmsMutex);
    LOGI("Initializing RandomX Engine from Source...");

    randomx_flags flags = randomx_get_flags();

    if (!myCache) {
        myCache = randomx_alloc_cache(flags);
        if (!myCache) return false;
        randomx_init_cache(myCache, key.data(), key.size());
    }

    currentKey = key;
    currentArgs = args;
    initialized = true;
    return true;
}

void RandomXEngine::prepareThreads(int count) {
    std::lock_guard<std::mutex> lock(vmsMutex);
    randomx_flags flags = randomx_get_flags();

    // VMs bereinigen falls nötig
    for (auto vm : vms) {
        if (vm) randomx_destroy_vm(vm);
    }
    vms.clear();

    // Neue VMs für jeden Thread erstellen
    for (int i = 0; i < count; ++i) {
        randomx_vm* vm = randomx_create_vm(flags, myCache, myDataset);
        vms.push_back(vm);
    }
    LOGI("%d RandomX VMs created from source build", count);
}

void RandomXEngine::hash(int threadId, const uint8_t* input, size_t inputSize, uint8_t* output) {
    randomx_vm* targetVM = nullptr;
    {
        std::lock_guard<std::mutex> lock(vmsMutex);
        if (threadId >= 0 && threadId < (int)vms.size()) {
            targetVM = vms[threadId];
        }
    }

    if (targetVM) {
        randomx_calculate_hash(targetVM, input, inputSize, output);
    }
}
