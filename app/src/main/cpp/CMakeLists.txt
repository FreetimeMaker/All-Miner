cmake_minimum_required(VERSION 3.22.1)

project("allminer")

# RandomX Quellcode als Subdirectory hinzufügen
add_subdirectory(RandomX)

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

target_include_directories(
    allminer
    PRIVATE
    ${CMAKE_CURRENT_SOURCE_DIR}/RandomX/src
)

target_link_libraries(
    allminer
    randomx
    ${log-lib}
)
