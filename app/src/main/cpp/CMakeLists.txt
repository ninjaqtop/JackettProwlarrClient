cmake_minimum_required(VERSION 3.22.1)

project(tlsclientbridge)

add_library(tlsclient SHARED IMPORTED)
set_target_properties(tlsclient PROPERTIES
    IMPORTED_LOCATION "${CMAKE_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libtlsclient.so"
)

# libtlsclientbridge is shipped as a prebuilt in jniLibs (the exact binary
# from Jonathan's build) instead of being compiled here from native-lib.cpp.
# native-lib.cpp remains in the repo as the bridge's source of record.

