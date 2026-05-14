#include "randomx_wrapper.h"
#include <vector>
#include <cstring>
#include <android/log.h>
#include <mutex>

#define LOG_TAG "RandomXEngine"
#define LOGI(...) __android_log_print(ANDROID_LOG_INFO, LOG_TAG, __VA_ARGS__)
#define LOGE(...) __android_log_print(ANDROID_LOG_ERROR, LOG_TAG, __VA_ARGS__)

typedef enum {
  RANDOMX_FLAG_DEFAULT = 0,
  RANDOMX_FLAG_FULL_MEM = 1,
  RANDOMX_FLAG_JIT = 2,
  RANDOMX_FLAG_SECURE = 4,
  RANDOMX_FLAG_HARD_AES = 64
} randomx_flags;

struct randomx_cache;
struct randomx_dataset;
struct randomx_vm;

extern "C" {
    randomx_flags randomx_get_flags();
    randomx_cache* randomx_alloc_cache(randomx_flags flags);
    void randomx_init_cache(randomx_cache* cache, const void* key, size_t keySize);
    void randomx_release_cache(randomx_cache* cache);
    randomx_dataset* randomx_alloc_dataset(randomx_flags flags);
    unsigned long randomx_dataset_item_count();
    void randomx_init_dataset(randomx_dataset* dataset, randomx_cache* cache, unsigned long startItem, unsigned long itemCount);
    void randomx_release_dataset(randomx_dataset* dataset);
    randomx_vm* randomx_create_vm(randomx_flags flags, randomx_cache* cache, randomx_dataset* dataset);
    void randomx_destroy_vm(randomx_vm* vm);
    void randomx_calculate_hash(randomx_vm* vm, const void* input, size_t inputSize, void* output);
}

static randomx_cache* myCache = nullptr;
static randomx_dataset* myDataset = nullptr;
static std::mutex vmsMutex;

bool RandomXEngine::init(const std::string& key, const std::string& args) {
    std::lock_guard<std::mutex> lock(vmsMutex);
    LOGI("Initializing RandomX Engine...");

    randomx_flags flags = randomx_get_flags();

    if (!myCache) {
        myCache = randomx_alloc_cache(flags);
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
    LOGI("%d RandomX VMs created", count);
}

void RandomXEngine::hash(int threadId, const uint8_t* input, size_t inputSize, uint8_t* output) {
    randomx_vm* targetVM = nullptr;
    {
        std::lock_guard<std::mutex> lock(vmsMutex);
        if (threadId >= 0 && threadId < vms.size()) {
            targetVM = vms[threadId];
        }
    }

    if (targetVM) {
        randomx_calculate_hash(targetVM, input, inputSize, output);
    }
}
