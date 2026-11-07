cmake_minimum_required(VERSION 3.22.1)

project(tlsclientbridge LANGUAGES CXX)

# libtlsclient.so is built FROM SOURCE (app/src/main/go/tlsclient, bogdanfinn/tls-client)
# by scripts/build_tlsclient_android.sh before Gradle runs. That script writes the
# library into jniLibs/<abi>/ (packaged by AGP) and the cgo header into
# generated/<abi>/. Nothing prebuilt is checked in.
set(TLSCLIENT_SO "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtlsclient.so")
set(TLSCLIENT_INCLUDE "${CMAKE_SOURCE_DIR}/generated/${ANDROID_ABI}")

if(NOT EXISTS "${TLSCLIENT_SO}" OR NOT EXISTS "${TLSCLIENT_INCLUDE}/libtlsclient.h")
    message(FATAL_ERROR
        "libtlsclient for ${ANDROID_ABI} has not been built. Run "
        "scripts/build_tlsclient_android.sh ${ANDROID_ABI} first (needs Go + the Android NDK).")
endif()

add_library(tlsclient SHARED IMPORTED)
set_target_properties(tlsclient PROPERTIES IMPORTED_LOCATION "${TLSCLIENT_SO}")

add_library(tlsclientbridge SHARED native-lib.cpp)
target_include_directories(tlsclientbridge PRIVATE "${TLSCLIENT_INCLUDE}")
target_compile_options(tlsclientbridge PRIVATE -Wall -fvisibility=hidden)
# 16 KB page compatibility (Android 15+ devices with 16 KB pages).
target_link_options(tlsclientbridge PRIVATE
    "-Wl,-z,max-page-size=16384"
    "-Wl,-z,common-page-size=16384")
target_link_libraries(tlsclientbridge PRIVATE tlsclient log)
