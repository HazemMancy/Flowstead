# ThermoDock — on-device engine: the JNI bridge linked against the Mono runtime prepared by
# prepare.sh (out/). Built by the app's externalNativeBuild when out/ exists.
# SPDX-License-Identifier: GPL-3.0-or-later
cmake_minimum_required(VERSION 3.22.1)
project(thermodock_host C)

set(OUT ${CMAKE_CURRENT_LIST_DIR}/out)
add_library(monosgen SHARED IMPORTED)
set_target_properties(monosgen PROPERTIES IMPORTED_LOCATION ${OUT}/jniLibs/${ANDROID_ABI}/libmonosgen-2.0.so)

add_library(thermodock_host SHARED ${CMAKE_CURRENT_LIST_DIR}/../thermodock_host.c ${CMAKE_CURRENT_LIST_DIR}/jni_bridge.c)
target_include_directories(thermodock_host PRIVATE ${CMAKE_CURRENT_LIST_DIR}/.. ${OUT}/include/mono-2.0)
target_compile_options(thermodock_host PRIVATE -Wall -Werror -O2)
target_link_libraries(thermodock_host monosgen log)
# Android 15+ devices use 16 KB pages.
target_link_options(thermodock_host PRIVATE "-Wl,-z,max-page-size=16384")

# Stand-in for IPOPT (see engine/native/ipopt_stub.c): DWSIM P/Invokes "Ipopt39".
add_library(Ipopt39 SHARED ${CMAKE_CURRENT_LIST_DIR}/../../native/ipopt_stub.c)
target_compile_options(Ipopt39 PRIVATE -Wall -Werror -O2)
target_link_options(Ipopt39 PRIVATE "-Wl,-z,max-page-size=16384")
