cmake_minimum_required(VERSION 3.22.1)

project("allminer")

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
    ${log-lib}
)
