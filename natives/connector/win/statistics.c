#include "connector.h"
#include <windows.h>

enum {
	SYSTEM_TOTAL,
	SYSTEM_USER,
	SYSTEM_KERNEL,
	PROCESS_USER,
	PROCESS_KERNEL
};

CONNECTOR_EXPORT void JNICALL LAVA_JNI_NAME(_natives_statistics_CpuStatisticsLibrary_getSystemTimes)(JNIEnv *jni, jobject me, jlongArray valueArray) {
	jlong values[5], idle;
	FILETIME unused;

	GetSystemTimes((FILETIME*) &idle, (FILETIME*) &values[SYSTEM_KERNEL], (FILETIME*) &values[SYSTEM_USER]);
	values[SYSTEM_TOTAL] = values[SYSTEM_KERNEL] + values[SYSTEM_USER];
	values[SYSTEM_KERNEL] -= idle;

	GetProcessTimes(GetCurrentProcess(), &unused, &unused, (FILETIME*) &values[PROCESS_KERNEL], (FILETIME*) &values[PROCESS_USER]);

	(*jni)->SetLongArrayRegion(jni, valueArray, 0, sizeof(values) / sizeof(*values), values);
}
