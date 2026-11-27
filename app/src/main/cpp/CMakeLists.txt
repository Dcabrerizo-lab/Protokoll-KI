cmake_minimum_required(VERSION 3.22)
project("protokollki_llm" C CXX)

set(CMAKE_CXX_STANDARD 17)
set(CMAKE_CXX_STANDARD_REQUIRED ON)

# Stabil vor schnell: OpenMP und KleidiAI waren auf echten Handys nicht abgesichert.
# Das Umpacken der Gewichte (REPACK, deutlich schneller) wird mitgebaut, aber erst zur Laufzeit
# eingeschaltet, wenn genug Arbeitsspeicher frei ist (use_extra_bufts in llm_jni.cpp).
set(GGML_OPENMP OFF CACHE BOOL "" FORCE)
set(GGML_CPU_KLEIDIAI OFF CACHE BOOL "" FORCE)
set(GGML_CPU_REPACK ON CACHE BOOL "" FORCE)

# Grafikchip (Adreno, OpenCL) als zusätzliches, abschaltbares Rechenwerk – nur arm64-Handys.
# Die OpenCL-Bibliothek kommt zur Laufzeit vom Hersteller (/vendor/lib64/libOpenCL.so). Zum Linken dient
# eine leere Platzhalter-Bibliothek (ExternalProject, wird nicht in die App gepackt); die OpenCL-Symbole
# bleiben bis zur Laufzeit offen (-z undefs).
if (ANDROID AND ANDROID_ABI STREQUAL "arm64-v8a" AND NOT PK_NO_OPENCL)
    include(ExternalProject)
    include(FetchContent)
    FetchContent_Declare(opencl_headers
            URL https://github.com/KhronosGroup/OpenCL-Headers/archive/refs/tags/v2024.10.24.tar.gz
            SOURCE_SUBDIR nur-header)
    FetchContent_MakeAvailable(opencl_headers)
    set(PK_CL_LIB ${CMAKE_BINARY_DIR}/opencl-stub/lib/libOpenCL.so)
    ExternalProject_Add(pk_opencl_stub
            SOURCE_DIR ${CMAKE_CURRENT_LIST_DIR}/opencl-stub
            CMAKE_ARGS -DCMAKE_BUILD_TYPE=Release
                       -DCMAKE_TOOLCHAIN_FILE=${CMAKE_TOOLCHAIN_FILE}
                       -DANDROID_ABI=${ANDROID_ABI}
                       -DANDROID_PLATFORM=${ANDROID_PLATFORM}
                       -DCMAKE_INSTALL_PREFIX=${CMAKE_BINARY_DIR}/opencl-stub
            BUILD_BYPRODUCTS ${PK_CL_LIB})
    set(OpenCL_INCLUDE_DIR ${opencl_headers_SOURCE_DIR} CACHE PATH "" FORCE)
    set(OpenCL_LIBRARY ${PK_CL_LIB} CACHE FILEPATH "" FORCE)
    set(GGML_OPENCL ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_EMBED_KERNELS ON CACHE BOOL "" FORCE)
    set(GGML_OPENCL_USE_ADRENO_KERNELS ON CACHE BOOL "" FORCE)
    set(PK_OPENCL ON)
endif ()

add_subdirectory(${CMAKE_CURRENT_LIST_DIR}/llama.cpp build-llama)

if (PK_OPENCL)
    add_dependencies(ggml-opencl pk_opencl_stub)
    target_link_options(ggml-opencl PRIVATE "-Wl,-z,undefs")
    # Eigener Dateiname: wird NICHT automatisch mitgeladen, sondern nur, wenn die App den Grafikchip nutzen will
    set_target_properties(ggml-opencl PROPERTIES OUTPUT_NAME "pkgpu-opencl")
endif ()

add_library(protokollki_llm SHARED llm_jni.cpp)
target_include_directories(protokollki_llm PRIVATE
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/include
        ${CMAKE_CURRENT_LIST_DIR}/llama.cpp/ggml/include)
target_link_libraries(protokollki_llm llama ggml android log)
