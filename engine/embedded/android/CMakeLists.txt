# Flowstead — on-device engine: the JNI bridge linked against the Mono runtime prepared by
# prepare.sh (out/). Built by the app's externalNativeBuild when out/ exists.
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(flowstead_host C)

set(OUT ${CMAKE_CURRENT_LIST_DIR}/out)
add_library(monosgen SHARED IMPORTED)
set_target_properties(monosgen PROPERTIES IMPORTED_LOCATION ${OUT}/jniLibs/${ANDROID_ABI}/libmonosgen-2.0.so)

add_library(flowstead_host SHARED ${CMAKE_CURRENT_LIST_DIR}/../flowstead_host.c ${CMAKE_CURRENT_LIST_DIR}/jni_bridge.c)
target_include_directories(flowstead_host PRIVATE ${CMAKE_CURRENT_LIST_DIR}/.. ${OUT}/include/mono-2.0)
target_compile_options(flowstead_host PRIVATE -Wall -Werror -O2)
target_link_libraries(flowstead_host monosgen log)
# Android 15+ devices use 16 KB pages.
target_link_options(flowstead_host PRIVATE "-Wl,-z,max-page-size=16384")
