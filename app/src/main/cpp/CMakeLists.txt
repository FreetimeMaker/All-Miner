cmake_minimum_required(VERSION 3.22.1)

project("allminer")

# Check if RandomX directory is empty or missing
if(NOT EXISTS "${CMAKE_CURRENT_SOURCE_DIR}/RandomX/CMakeLists.txt")
    message(FATAL_ERROR "RandomX source directory is missing or empty!
    Please run: git submodule update --init --recursive
    Or clone it manually into app/src/main/cpp/RandomX")
endif()

# Add RandomX as a subdirectory
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
