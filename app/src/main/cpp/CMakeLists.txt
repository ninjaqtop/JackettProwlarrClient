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
