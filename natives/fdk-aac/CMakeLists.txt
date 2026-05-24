cmake_minimum_required(VERSION 3.10)

project(fdk-aac CXX)

get_filename_component(ROOT_DIR "." ABSOLUTE)

set(MAIN_DIRS "${ROOT_DIR}/libAACdec" "${ROOT_DIR}/libFDK" "${ROOT_DIR}/libSYS" "${ROOT_DIR}/libMpegTPDec" "${ROOT_DIR}/libSBRdec" "${ROOT_DIR}/libPCMutils" "${ROOT_DIR}/libArithCoding" "${ROOT_DIR}/libDRCdec" "${ROOT_DIR}/libSACdec")
set(MAIN_SOURCES "")

message(STATUS "${MAIN_DIRS}")

foreach(subdir ${MAIN_DIRS})
	file (GLOB dir_sources "${subdir}/src/*.cpp" "${subdir}/src/*.h" "${subdir}/include/*.h")
	set (MAIN_SOURCES ${MAIN_SOURCES} ${dir_sources})

	include_directories("${subdir}/include")
endforeach()

if ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "MSVC")
	add_definitions(-Dinline=__inline)
	set(CMAKE_CXX_FLAGS_RELEASE "/MT")
else()
	add_definitions(-Wno-narrowing)
	set(CMAKE_CXX_FLAGS "${CMAKE_CXX_FLAGS} -fPIC -fdata-sections -ffunction-sections")
endif()

add_library(fdk-aac STATIC ${MAIN_SOURCES})

if(${CMAKE_SYSTEM_NAME} STREQUAL "Android")
	file(MAKE_DIRECTORY "${CMAKE_CURRENT_BINARY_DIR}/android-compat/log")
	file(WRITE "${CMAKE_CURRENT_BINARY_DIR}/android-compat/log/log.h" "#pragma once\n#include <android/log.h>\n#ifndef android_errorWriteLog\n#define android_errorWriteLog(tag, subTag) __android_log_print(ANDROID_LOG_ERROR, \"fdk-aac\", \"%x:%s\", tag, subTag)\n#endif\n")
	target_include_directories(fdk-aac PRIVATE "${CMAKE_CURRENT_BINARY_DIR}/android-compat")
endif()

if (NOT "${CMAKE_CXX_COMPILER_ID}" STREQUAL "MSVC")
	# Upstream fdk-aac embeds __DATE__/__TIME__ in version metadata.
	# Zig/Clang reports that via -Wdate-time, which becomes fatal under -Werror.
	target_compile_options(fdk-aac PRIVATE -Wno-date-time)
endif()
