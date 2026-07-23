#include <jni.h>
#include <stdlib.h>
#include <string>
#include <vector>

#include "libtlsclient.h"

namespace {

std::string utf16ToUtf8(const jchar *input, jsize length) {
    std::string output;
    output.reserve(static_cast<size_t>(length) * 3);
    for (jsize i = 0; i < length; ++i) {
        uint32_t codePoint = input[i];
        if (codePoint >= 0xD800 && codePoint <= 0xDBFF && i + 1 < length) {
            const uint32_t low = input[i + 1];
            if (low >= 0xDC00 && low <= 0xDFFF) {
                codePoint = 0x10000 + ((codePoint - 0xD800) << 10) + (low - 0xDC00);
                ++i;
            }
        } else if (codePoint >= 0xDC00 && codePoint <= 0xDFFF) {
            codePoint = 0xFFFD;
        }

        if (codePoint <= 0x7F) {
            output.push_back(static_cast<char>(codePoint));
        } else if (codePoint <= 0x7FF) {
            output.push_back(static_cast<char>(0xC0 | (codePoint >> 6)));
            output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else if (codePoint <= 0xFFFF) {
            output.push_back(static_cast<char>(0xE0 | (codePoint >> 12)));
            output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        } else {
            output.push_back(static_cast<char>(0xF0 | (codePoint >> 18)));
            output.push_back(static_cast<char>(0x80 | ((codePoint >> 12) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | ((codePoint >> 6) & 0x3F)));
            output.push_back(static_cast<char>(0x80 | (codePoint & 0x3F)));
        }
    }
    return output;
}

std::vector<jchar> utf8ToUtf16(const char *input) {
    std::vector<jchar> output;
    if (input == nullptr) return output;
    const auto *bytes = reinterpret_cast<const unsigned char *>(input);
    size_t i = 0;
    while (bytes[i] != 0) {
        uint32_t codePoint = 0xFFFD;
        size_t consumed = 1;
        if (bytes[i] < 0x80) {
            codePoint = bytes[i];
        } else if ((bytes[i] & 0xE0) == 0xC0 && bytes[i + 1] != 0 && (bytes[i + 1] & 0xC0) == 0x80) {
            codePoint = ((bytes[i] & 0x1F) << 6) | (bytes[i + 1] & 0x3F);
            consumed = codePoint >= 0x80 ? 2 : 1;
            if (consumed == 1) codePoint = 0xFFFD;
        } else if ((bytes[i] & 0xF0) == 0xE0 && bytes[i + 1] != 0 && bytes[i + 2] != 0 &&
                   (bytes[i + 1] & 0xC0) == 0x80 && (bytes[i + 2] & 0xC0) == 0x80) {
            codePoint = ((bytes[i] & 0x0F) << 12) | ((bytes[i + 1] & 0x3F) << 6) | (bytes[i + 2] & 0x3F);
            consumed = codePoint >= 0x800 && !(codePoint >= 0xD800 && codePoint <= 0xDFFF) ? 3 : 1;
            if (consumed == 1) codePoint = 0xFFFD;
        } else if ((bytes[i] & 0xF8) == 0xF0 && bytes[i + 1] != 0 && bytes[i + 2] != 0 && bytes[i + 3] != 0 &&
                   (bytes[i + 1] & 0xC0) == 0x80 && (bytes[i + 2] & 0xC0) == 0x80 && (bytes[i + 3] & 0xC0) == 0x80) {
            codePoint = ((bytes[i] & 0x07) << 18) | ((bytes[i + 1] & 0x3F) << 12) |
                        ((bytes[i + 2] & 0x3F) << 6) | (bytes[i + 3] & 0x3F);
            consumed = codePoint >= 0x10000 && codePoint <= 0x10FFFF ? 4 : 1;
            if (consumed == 1) codePoint = 0xFFFD;
        }
        i += consumed;
        if (codePoint <= 0xFFFF) {
            output.push_back(static_cast<jchar>(codePoint));
        } else {
            codePoint -= 0x10000;
            output.push_back(static_cast<jchar>(0xD800 + (codePoint >> 10)));
            output.push_back(static_cast<jchar>(0xDC00 + (codePoint & 0x3FF)));
        }
    }
    return output;
}

jstring newUtf8String(JNIEnv *env, const char *input) {
    const std::vector<jchar> value = utf8ToUtf16(input);
    return env->NewString(value.data(), static_cast<jsize>(value.size()));
}

}  // namespace

extern "C" JNIEXPORT jstring JNICALL
Java_com_aggregatorx_app_engine_network_TlsClient_requestNative(
        JNIEnv *env,
        jobject,
        jstring json
) {
    if (json == nullptr) {
        return env->NewStringUTF("{\"statusCode\":0,\"headers\":{},\"body\":\"\",\"finalUrl\":\"\",\"error\":\"request JSON is null\"}");
    }

    const jchar *input = env->GetStringChars(json, nullptr);
    if (input == nullptr) {
        return env->NewStringUTF("{\"statusCode\":0,\"headers\":{},\"body\":\"\",\"finalUrl\":\"\",\"error\":\"failed to read request JSON\"}");
    }

    const std::string utf8Input = utf16ToUtf8(input, env->GetStringLength(json));
    env->ReleaseStringChars(json, input);

    char *goResult = request(const_cast<char *>(utf8Input.c_str()));

    if (goResult == nullptr) {
        return env->NewStringUTF("{\"statusCode\":0,\"headers\":{},\"body\":\"\",\"finalUrl\":\"\",\"error\":\"native tls-client returned null\"}");
    }

    jstring output = newUtf8String(env, goResult);
    free(goResult);
    return output;
}
