cmake_minimum_required(VERSION 3.22)
project(zhome_native)

# Path to pre-compiled static libraries
set(CLIBS_DIR "${CMAKE_SOURCE_DIR}/../../../../clibs")

add_library(zhome_native SHARED p2p_jni.cpp)

# Align to 16 KB page size for Android 15+ compatibility
target_link_options(zhome_native PRIVATE "-Wl,-z,max-page-size=16384")

target_include_directories(zhome_native PRIVATE
    ${CLIBS_DIR}/include
)

# Import all static libraries from clibs/lib/
# Order matters for static linking: dependents first, then dependencies
set(STATIC_LIBS
    ip2p
    juice
    lsquic
    ssl
    crypto
    s2n
    uv
    aws-crt-cpp
    aws-c-mqtt
    aws-c-http
    aws-c-io
    aws-c-event-stream
    aws-c-s3
    aws-c-auth
    aws-c-cal
    aws-c-compression
    aws-c-sdkutils
    aws-c-common
    aws-checksums
)

foreach(LIB ${STATIC_LIBS})
    add_library(${LIB} STATIC IMPORTED)
    if(LIB STREQUAL "ip2p")
        set_target_properties(${LIB} PROPERTIES IMPORTED_LOCATION
            ${CLIBS_DIR}/lib/libip2p.a)
    else()
        set_target_properties(${LIB} PROPERTIES IMPORTED_LOCATION
            ${CLIBS_DIR}/lib/lib${LIB}.a)
    endif()
endforeach()

target_link_libraries(zhome_native
    ${STATIC_LIBS}
    android
    log
    z
)
