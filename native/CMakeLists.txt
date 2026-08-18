cmake_minimum_required(VERSION 3.22)
project(dumlcore LANGUAGES CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Produces libduml-core.so for each ABI configured in app/build.gradle.kts
# (arm64-v8a, armeabi-v7a — the ABIs DJI smart controllers ship).
add_library(duml-core SHARED
    duml_core.cpp
    duss_bus.cpp
    jni_bridge.cpp
)

find_library(log-lib log)
target_link_libraries(duml-core ${log-lib})

target_compile_options(duml-core PRIVATE -O2 -fvisibility=hidden -Wall)
