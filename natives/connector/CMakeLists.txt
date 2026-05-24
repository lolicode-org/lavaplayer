cmake_minimum_required(VERSION 3.10)
project(connector C)

set(LAVA_JNI_PREFIX "org_lolicode_lavaplayer" CACHE STRING
	"JNI package prefix for lavaplayer native classes, using JNI-mangled underscores")

if(${CMAKE_SYSTEM_NAME} STREQUAL "Windows")
	include_directories("$ENV{JAVA_HOME}/include/win32")
	set(STATISTICS_SOURCE_OS "win")
elseif(${CMAKE_SYSTEM_NAME} STREQUAL "Android")
	# Android/Bionic is a distinct package target, but the CPU statistics JNI uses the same /proc files as Linux.
	set(STATISTICS_SOURCE_OS "linux")
elseif(${CMAKE_SYSTEM_NAME} STREQUAL "Linux")
	include_directories("$ENV{JAVA_HOME}/include/linux")
	set(STATISTICS_SOURCE_OS "linux")
else()
	include_directories("$ENV{JAVA_HOME}/include/darwin")
	set(STATISTICS_SOURCE_OS "darwin")
endif()

if ("${CMAKE_CXX_COMPILER_ID}" STREQUAL "MSVC")
	add_definitions(-Dinline=__inline)

	set(COMPILER_FLAGS "")
	set(COMPILER_FLAGS_DEBUG "/MTd")
	set(COMPILER_FLAGS_RELEASE "/Ox /MT")
elseif(${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
	set(COMPILER_FLAGS "-fvisibility=hidden -fdata-sections -ffunction-sections")
	set(COMPILER_FLAGS_DEBUG "-g")
	set(COMPILER_FLAGS_RELEASE "-O3 -DNDEBUG")
	set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,-dead_strip")
else()
	set(COMPILER_FLAGS "-Wall -Wextra -Werror -Wno-unused-parameter -Wno-unused-function -fvisibility=hidden -fdata-sections -ffunction-sections")
	set(COMPILER_FLAGS_DEBUG "-g")
	set(COMPILER_FLAGS_RELEASE "-O3 -DNDEBUG")
	# --exclude-libs,ALL is GNU ld only; lld (Windows cross, musl/Zig) does not support it
	if ("${CMAKE_C_COMPILER_ID}" STREQUAL "GNU")
		set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--exclude-libs,ALL -Wl,--strip-all")
	else()
		set(CMAKE_SHARED_LINKER_FLAGS "${CMAKE_SHARED_LINKER_FLAGS} -Wl,--gc-sections -Wl,--strip-all")
	endif()
endif()

set(CMAKE_C_FLAGS_DEBUG "${CMAKE_C_FLAGS_DEBUG} ${COMPILER_FLAGS} ${COMPILER_FLAGS_DEBUG}")
set(CMAKE_C_FLAGS_RELEASE "${CMAKE_C_FLAGS_RELEASE} ${COMPILER_FLAGS} ${COMPILER_FLAGS_RELEASE}")
set(CMAKE_CXX_FLAGS_DEBUG "${CMAKE_CXX_FLAGS_DEBUG} ${COMPILER_FLAGS} ${COMPILER_FLAGS_DEBUG}")
set(CMAKE_CXX_FLAGS_RELEASE "${CMAKE_CXX_FLAGS_RELEASE} ${COMPILER_FLAGS} ${COMPILER_FLAGS_RELEASE}")

if(NOT "${CMAKE_SYSTEM_NAME}" STREQUAL "Android")
	include_directories("$ENV{JAVA_HOME}/include/")
endif()

include_directories("${samplerate_SOURCE_DIR}/include")
include_directories("../opus/opus/include")
include_directories("../fdk-aac/libAACdec/include")
include_directories("../fdk-aac/libSYS/include")
include_directories("../vorbis/libvorbis/include")
include_directories("../vorbis/libogg/include")
link_directories("../libs/64")

if (DEFINED ENV{DIST_DIR})
	set(CMAKE_LIBRARY_OUTPUT_DIRECTORY "$ENV{DIST_DIR}")
	set(CMAKE_RUNTIME_OUTPUT_DIRECTORY_RELEASE "$ENV{DIST_DIR}")
endif()

if(${CMAKE_SYSTEM_NAME} MATCHES "Darwin")
	add_library(connector SHARED samplerate.c fdk-aac.c vorbis.c opus.c mpg123.c)
else()
        add_library(connector SHARED samplerate.c fdk-aac.c vorbis.c opus.c mpg123.c ${STATISTICS_SOURCE_OS}/statistics.c)
endif()

target_include_directories(connector PRIVATE "${CMAKE_CURRENT_SOURCE_DIR}")

if(${CMAKE_SYSTEM_NAME} STREQUAL "Windows")
	# Output connector.dll, not libconnector.dll (MinGW adds lib prefix by default)
	set_target_properties(connector PROPERTIES PREFIX "")
	# mpg123 uses PathIsRelativeW/PathIsUNCW/PathCombineW from shlwapi.dll
	target_link_libraries(connector samplerate fdk-aac vorbis ogg opus mpg123 shlwapi)
else()
	target_link_libraries(connector samplerate fdk-aac vorbis ogg opus mpg123)
endif()

target_compile_definitions(connector PRIVATE LAVA_JNI_PREFIX=${LAVA_JNI_PREFIX})
