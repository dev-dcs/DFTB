cmake_minimum_required(VERSION 3.22.1)
project(dftb_native LANGUAGES CXX)

# libdftb_native.so: thin JNI helper for filesystem facts the JVM cannot report accurately
# (statvfs on arbitrary mount points, lstat with full mode bits and SELinux context, fsync,
# block-device size via ioctl). Everything heavy (archiving, crypto) stays in Kotlin or in the
# bundled CLI tools; this library deliberately has no third-party dependencies.
add_library(dftb_native SHARED dftb_native.cpp)

target_compile_features(dftb_native PRIVATE cxx_std_20)
target_compile_options(dftb_native PRIVATE -Wall -Wextra -Werror=return-type -fno-exceptions -fno-rtti)

find_library(log-lib log)
target_link_libraries(dftb_native PRIVATE ${log-lib})

# dftbtool: a tiny, dependency-free C program packaged as libdftbtool.so. Android only extracts and
# makes executable files named lib*.so from the APK, so a runnable binary must adopt that name; the
# PREFIX/SUFFIX/OUTPUT_NAME overrides below turn the "dftbtool" executable into "libdftbtool.so"
# while keeping it a real PIE executable (not a shared library). It proves the extract-and-exec
# provisioning pipeline on-device and serves as a fallback SHA-256 hasher / file copier. Links libc
# only. The existing dftb_native library above is untouched.
enable_language(C)
add_executable(dftbtool dftbtool.c)
set_target_properties(dftbtool PROPERTIES PREFIX "lib" SUFFIX ".so" OUTPUT_NAME "dftbtool")
target_compile_options(dftbtool PRIVATE -std=c11 -Wall -Wextra -Werror=return-type -fPIE)
target_link_options(dftbtool PRIVATE -pie)
