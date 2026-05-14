#ifndef RANDOMX_WRAPPER_H
#define RANDOMX_WRAPPER_H

#include <jni.h>
#include <string>
#include <vector>

struct randomx_vm;

class RandomXEngine {
public:
    static RandomXEngine& getInstance() {
        static RandomXEngine instance;
        return instance;
    }

    bool init(const std::string& key, const std::string& args);
    void hash(int threadId, const uint8_t* input, size_t inputSize, uint8_t* output);
    bool isReady() const { return initialized; }
    void prepareThreads(int count);

private:
    RandomXEngine() : initialized(false) {}
    bool initialized;
    std::string currentKey;
    std::string currentArgs;
    std::vector<randomx_vm*> vms;
};

#endif
