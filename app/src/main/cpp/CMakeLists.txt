cmake_minimum_required(VERSION 3.22)
project("protokollki_llm" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

if(ANDROID_ABI STREQUAL "arm64-v8a")
    set(GGML_CPU_KLEIDIAI ON CACHE BOOL "" FORCE)
    set(GGML_OPENMP ON CACHE BOOL "" FORCE)
endif()

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/llama.cpp build-llama)

add_library(protokollki_llm SHARED llm_jni.cpp)
target_include_directories(protokollki_llm PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/ggml/include)
target_link_libraries(protokollki_llm llama ggml android log)
