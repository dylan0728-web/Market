
cmake_minimum_required(VERSION 3.22.1)

project(macreader)

add_library(
    macreader
    SHARED
    macreader.cpp
)

find_library(
    log-lib
    log
)

target_link_libraries(
    macreader
    ${log-lib}
)
