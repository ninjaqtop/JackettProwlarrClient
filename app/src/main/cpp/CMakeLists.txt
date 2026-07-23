cmake_minimum_required(VERSION 3.22.1)

project(tlsclientbridge)

add_library(tlsclient SHARED IMPORTED)
set_target_properties(tlsclient PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtlsclient.so"
)

add_library(tlsclientbridge SHARED native-lib.cpp)

target_include_directories(tlsclientbridge PRIVATE "${CMAKE_SOURCE_DIR}")

target_link_libraries(tlsclientbridge
    tlsclient
    log
)

# Optional llama.cpp Android binding. The TLS stack above is intentionally
# untouched. If libllama.so is supplied in jniLibs/arm64-v8a, Gradle will package
# it and native callers can load it; otherwise the Kotlin model manager falls
# back gracefully without breaking the debug APK build.
if(ANDROID_ABI STREQUAL "arm64-v8a" AND EXISTS "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so")
    add_library(llama_android SHARED IMPORTED)
    set_target_properties(llama_android PROPERTIES
        IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libllama.so"
    )
endif()
