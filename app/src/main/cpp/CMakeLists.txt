cmake_minimum_required(VERSION 3.22)
project("protokollki_llm" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Stabil vor schnell: OpenMP und KleidiAI waren auf echten Handys nicht abgesichert, und das
# Umpacken der Gewichte (REPACK) legt eine zweite Kopie im Arbeitsspeicher an. Ohne REPACK bleiben
# die Gewichte in der eingeblendeten Datei, die Android bei Bedarf wieder freigeben kann.
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK OFF CACHE BOOL "" FORCE)

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/llama.cpp build-llama)

add_library(protokollki_llm SHARED llm_jni.cpp)
target_include_directories(protokollki_llm PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/ggml/include)
target_link_libraries(protokollki_llm llama ggml android log)
