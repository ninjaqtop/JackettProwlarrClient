package com.aggregatorx.apq.engine.network

/**
 * JNI binding shim for the shipped prebuilt bridge (libtlsclientbridge.so).
 *
 * The prebuilt bridge exports exactly one JNI entry point:
 *
 *     Java_com_aggregatorx_apq_engine_network_TlsClient_requestNative
 *
 * i.e. it was compiled for the class `com.aggregatorx.apq.engine.network.TlsClient`,
 * not for the app's own `com.aggregatorx.app...` package. The JVM binds a native
 * method by that mangled name, so the app-side `TlsClient` could never resolve it
 * (UnsatisfiedLinkError on the first native request). This object carries the exact
 * class name the bridge was built for, so the shipped binary binds unmodified.
 *
 * Never call this directly — use [com.aggregatorx.app.engine.network.TlsClient],
 * which loads the native pair in the right order and handles failures.
 */
object TlsClient {
    external fun requestNative(json: String): String
}
