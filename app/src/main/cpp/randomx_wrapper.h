#ifndef RANDOMX_WRAPPER_H
#define RANDOMX_WRAPPER_H

#include <jni.h>
#include <string>

// Diese Header würden normalerweise von der RandomX-Library kommen
// Wir definieren hier die Schnittstellen, die wir in miner-lib.cpp nutzen

class RandomXEngine {
public:
    static RandomXEngine& getInstance() {
        static RandomXEngine instance;
        return instance;
    }

    bool init(const std::string& key);
    void hash(const uint8_t* input, size_t inputSize, uint8_t* output);
    bool isReady() const { return initialized; }

private:
    RandomXEngine() : initialized(false) {}
    bool initialized;
    std::string currentKey;
};

#endif
