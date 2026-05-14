cmake_minimum_required(VERSION 4.1.2)

project("allminer")

# Importiere die vor-kompilierte librandomx.a
add_library(randomx STATIC IMPORTED)
set_target_properties(randomx PROPERTIES IMPORTED_LOCATION
    ${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/librandomx.a)

add_library(
    allminer
    SHARED
    miner-lib.cpp
    randomx_wrapper.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    allminer
    randomx
    ${log-lib}
)
