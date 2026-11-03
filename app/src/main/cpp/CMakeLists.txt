# Builds libvikey.so from the vendored Vikey engine.
#
# The engine is plain C++17 with no Android dependencies (see engine/), so the same
# sources compile on the host — tools/parity/harness.cpp does exactly that for testing.
cmake_minimum_required(VERSION 3.22.1)
project(miniqwerty LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(ENGINE ${CMAKE_CURRENT_SOURCE_DIR}/engine)

add_library(vikey SHARED
    vikey_jni.cpp
    ${ENGINE}/Engine.cpp
    ${ENGINE}/Vietnamese.cpp
    ${ENGINE}/Driver.cpp)

target_include_directories(vikey PRIVATE ${ENGINE} ${ENGINE}/platforms)
target_compile_options(vikey PRIVATE -w -O2 -fvisibility=hidden)
target_link_libraries(vikey log)

# 16 KB memory pages (Android 15+ devices; required by Google Play): align the ELF segments
target_link_options(vikey PRIVATE "-Wl,-z,max-page-size=16384")
