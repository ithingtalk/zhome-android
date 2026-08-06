#!/bin/sh
find . -name '._*' -type f -print -exec rm -rf {} \;
find . -name '.DS_Store' -type f -print -exec rm -rf {} \;
# rm -rf app/gst-build-* app/.cxx app/build app/gst-android-build .gradle .idea
for dir in $(find . -type d); do
    [ -f "$dir/CMakeCache.txt" ] && rm -f "$dir/CMakeCache.txt"
    [ -d "$dir/CMakeFiles" ] && rm -rf "$dir/CMakeFiles"
    [ -f "$dir/Makefile" ] && rm -f "$dir/Makefile"
    [ -f "$dir/cmake_install.cmake" ] && rm -f "$dir/cmake_install.cmake"
    [ -f "$dir/install_manifest.txt" ] && rm -f "$dir/install_manifest.txt"
    [ -f "$dir/build.ninja" ] && rm -f "$dir/build.ninja"
    rm -f "$dir/.ninja_"*
done