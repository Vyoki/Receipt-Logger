# On-device AI reader: llama.cpp (pinned, see app/build.gradle.kts "llamaCppTag") + libmtmd, CPU only.
cmake_minimum_required(VERSION 3.22.1)
project(receipt_ai C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

set(LLAMA_DIR "${CMAKE_CURRENT_LIST_DIR}/../../../../third_party/llama.cpp")
if (NOT EXISTS "${LLAMA_DIR}/CMakeLists.txt")
    message(FATAL_ERROR "llama.cpp sources missing in third_party/llama.cpp: run ./gradlew :app:fetchLlamaCpp")
endif()

# Shared libraries so ggml can pick the fastest CPU variant for the phone at run time.
set(BUILD_SHARED_LIBS      ON  CACHE BOOL "" FORCE)
set(GGML_BACKEND_DL        ON  CACHE BOOL "" FORCE)
set(GGML_CPU_ALL_VARIANTS  ON  CACHE BOOL "" FORCE)
set(GGML_NATIVE            OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP            OFF CACHE BOOL "" FORCE)
set(GGML_LLAMAFILE         OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_COMMON     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TESTS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_TOOLS      OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_EXAMPLES   OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_SERVER     OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_APP        OFF CACHE BOOL "" FORCE)
set(LLAMA_BUILD_MTMD       ON  CACHE BOOL "" FORCE)
set(LLAMA_OPENSSL          OFF CACHE BOOL "" FORCE)
set(MTMD_VIDEO             OFF CACHE BOOL "" FORCE)

add_subdirectory("${LLAMA_DIR}" llama-build)

add_library(receipt_ai SHARED receipt_ai.cpp jni_bridge.cpp)
target_include_directories(receipt_ai PRIVATE "${LLAMA_DIR}/include" "${LLAMA_DIR}/ggml/include" "${LLAMA_DIR}/tools/mtmd")
target_link_libraries(receipt_ai PRIVATE llama mtmd ggml android log)
