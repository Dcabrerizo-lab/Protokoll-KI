# Leere Platzhalter-Bibliothek „libOpenCL.so“ nur zum Linken. Zur Laufzeit gilt die Bibliothek des
# Handy-Herstellers (/vendor/lib64/libOpenCL.so); diese hier wird nicht in die App gepackt.
cmake_minimum_required(VERSION 3.22)
project(pk_opencl_stub C)
add_library(OpenCL SHARED stub.c)
set_target_properties(OpenCL PROPERTIES NO_SONAME OFF)
target_link_options(OpenCL PRIVATE "-Wl,-soname,libOpenCL.so")
install(TARGETS OpenCL LIBRARY DESTINATION lib)
