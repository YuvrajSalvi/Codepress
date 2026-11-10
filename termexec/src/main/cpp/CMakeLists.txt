cmake_minimum_required(VERSION 3.22.1)
project(codepress_pty LANGUAGES CXX)
add_library(codepress-pty SHARED pty.cpp)
find_library(log-lib log)
target_link_libraries(codepress-pty ${log-lib})
# Android 15+ devices use 16 KB memory pages.
target_link_options(codepress-pty PRIVATE -Wl,-z,max-page-size=16384)
