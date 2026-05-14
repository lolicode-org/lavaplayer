#pragma once

#include <jni.h>

#ifdef __GNUC__
#define CONNECTOR_EXPORT __attribute__ ((visibility("default"))) JNIEXPORT
#else
#define CONNECTOR_EXPORT JNIEXPORT
#endif

#ifndef LAVA_JNI_PREFIX
#define LAVA_JNI_PREFIX com_sedmelluq_discord_lavaplayer
#endif

#define LAVA_JNI_NAME_INNER(prefix, suffix) Java_ ## prefix ## suffix
#define LAVA_JNI_NAME_EXPAND(prefix, suffix) LAVA_JNI_NAME_INNER(prefix, suffix)
#define LAVA_JNI_NAME(suffix) LAVA_JNI_NAME_EXPAND(LAVA_JNI_PREFIX, suffix)

#ifdef MSC_VER
#define CONNECTOR_IMPORT __declspec(dllimport)
#else
#define CONNECTOR_IMPORT
#endif
