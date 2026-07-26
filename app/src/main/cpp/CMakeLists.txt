cmake_minimum_required(VERSION 3.22.1)
project(majsoulmax_native C)

set(CMAKE_C_STANDARD 11)

# ---------------------------------------------------------------------------
# hev-socks5-tunnel is built out-of-band by scripts/build-tun2socks.sh (ndk-build
# against the submodule) and dropped into jniLibs. We only link against it here.
#
# When it is absent the shim still compiles, but every entry point returns
# TUN2SOCKS_UNAVAILABLE so the Kotlin layer can report a clean, actionable error
# instead of the app dying inside System.loadLibrary.
# ---------------------------------------------------------------------------
set(HEV_LIB "${CMAKE_CURRENT_SOURCE_DIR}/../jniLibs/${ANDROID_ABI}/libhev-socks5-tunnel.so")

# EXISTS is evaluated at configure time and cached, so without this a build done
# before build-tun2socks.sh would bake in the stub and never reconfigure once the
# library appeared.
set_property(DIRECTORY APPEND PROPERTY CMAKE_CONFIGURE_DEPENDS "${HEV_LIB}")

add_library(tun2socks SHARED tun2socks_jni.c)

if(EXISTS "${HEV_LIB}")
    message(STATUS "majsoulmax: linking prebuilt tunnel ${HEV_LIB}")
    add_library(hev-socks5-tunnel SHARED IMPORTED)
    set_target_properties(hev-socks5-tunnel PROPERTIES IMPORTED_LOCATION "${HEV_LIB}")
    target_compile_definitions(tun2socks PRIVATE HAVE_HEV_TUNNEL=1)
    target_link_libraries(tun2socks hev-socks5-tunnel log)
else()
    message(WARNING
        "majsoulmax: libhev-socks5-tunnel.so missing for ${ANDROID_ABI} - "
        "run ./scripts/build-tun2socks.sh. Building a stub; VPN mode will refuse to start.")
    target_link_libraries(tun2socks log)
endif()
