#include <jni.h>
#include <stdlib.h>

#include "libtlsclient.h"

extern "C" JNIEXPORT jstring JNICALL
Java_com_aggregatorx_app_engine_network_TlsClient_requestNative(
        JNIEnv *env,
        jobject,
        jstring json
) {
    if (json == nullptr) {
        return env->NewStringUTF("{\"statusCode\":0,\"headers\":{},\"body\":\"\",\"finalUrl\":\"\",\"error\":\"request JSON is null\"}");
    }

    const char *input = env->GetStringUTFChars(json, nullptr);
    if (input == nullptr) {
        return env->NewStringUTF("{\"statusCode\":0,\"headers\":{},\"body\":\"\",\"finalUrl\":\"\",\"error\":\"failed to read request JSON\"}");
    }

    char *goResult = request(const_cast<char *>(input));
    env->ReleaseStringUTFChars(json, input);

    if (goResult == nullptr) {
        return env->NewStringUTF("{\"statusCode\":0,\"headers\":{},\"body\":\"\",\"finalUrl\":\"\",\"error\":\"native tls-client returned null\"}");
    }

    jstring output = env->NewStringUTF(goResult);
    free(goResult);
    return output;
}
