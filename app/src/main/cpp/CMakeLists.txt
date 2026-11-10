cmake_minimum_required(VERSION 3.22.1)
project(eta_whisper C CXX)

# whisper.cpp 在构建期按固定标签拉取，不把第三方源码放进仓库；升级只改 GIT_TAG。
set(WHISPER_BUILD_TESTS OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_EXAMPLES OFF CACHE BOOL "" FORCE)
set(WHISPER_BUILD_SERVER OFF CACHE BOOL "" FORCE)
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_NATIVE OFF CACHE BOOL "" FORCE)
set(BUILD_SHARED_LIBS OFF CACHE BOOL "" FORCE)

include(FetchContent)
FetchContent_Declare(
  whisper
  GIT_REPOSITORY https://github.com/ggml-org/whisper.cpp.git
  GIT_TAG v1.9.5
  GIT_SHALLOW TRUE
)
FetchContent_MakeAvailable(whisper)

add_library(eta_whisper SHARED eta_whisper_jni.cpp)
target_compile_features(eta_whisper PRIVATE cxx_std_17)
target_include_directories(eta_whisper PRIVATE ${whisper_SOURCE_DIR}/include)
target_link_libraries(eta_whisper PRIVATE whisper log android)
