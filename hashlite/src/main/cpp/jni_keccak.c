/*
 * jni_keccak.c —— NativeKeccak 的 JNI 胶水层
 *
 * 命名对应 Kotlin：
 *   io.github.xiaokun19.hashlite.core.NativeKeccak
 *   即 Java_io_github_xiaokun19_hashlite_core_NativeKeccak_<method>
 *
 * 设计：调用方（Kotlin）持有句柄（long），上下文是 C 侧 malloc 的 kc_ctx，
 * finish() 之后由 Kotlin 侧调用 destroy() 释放；单次哈希被中途抛弃时最多泄漏
 * sizeof(kc_ctx)（约 200 字节），这在批量/取消场景下可忽略（已在文档里注明）。
 */
#include <jni.h>
#include <stdint.h>
#include <stdlib.h>

#include "keccak_native.h"

#define JFN(fn) Java_io_github_xiaokun19_hashlite_core_NativeKeccak_##fn

JNIEXPORT jboolean JNICALL JFN(haveSha3Ext)(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return kc_have_sha3ext() ? JNI_TRUE : JNI_FALSE;
}

JNIEXPORT jstring JNICALL JFN(variantName)(JNIEnv *env, jobject thiz) {
    (void)thiz;
    return (*env)->NewStringUTF(env, kc_variant_name());
}

JNIEXPORT void JNICALL JFN(setVariant)(JNIEnv *env, jobject thiz, jboolean cext) {
    (void)env;
    (void)thiz;
    kc_set_variant(cext == JNI_TRUE);
}

JNIEXPORT void JNICALL JFN(chooseByBench)(JNIEnv *env, jobject thiz, jint mib) {
    (void)env;
    (void)thiz;
    if (mib > 0) kc_choose_variant_by_bench((size_t)mib * 1024 * 1024);
}

JNIEXPORT jint JNICALL JFN(selfTest)(JNIEnv *env, jobject thiz) {
    (void)env;
    (void)thiz;
    return (jint)kc_self_test();
}

JNIEXPORT jdoubleArray JNICALL JFN(bench)(JNIEnv *env, jobject thiz, jint mib) {
    (void)thiz;
    double plain = 0, cext = 0;
    if (mib > 0) kc_bench(&plain, &cext, (size_t)mib * 1024 * 1024);
    jdoubleArray arr = (*env)->NewDoubleArray(env, 2);
    if (arr) {
        jdouble v[2];
        v[0] = plain;
        v[1] = cext;
        (*env)->SetDoubleArrayRegion(env, arr, 0, 2, v);
    }
    return arr;
}

JNIEXPORT jlong JNICALL JFN(create)(JNIEnv *env, jobject thiz, jint rate) {
    (void)env;
    (void)thiz;
    kc_ctx *ctx = (kc_ctx *)malloc(sizeof(kc_ctx));
    if (!ctx) return 0;
    kc_init(ctx, (size_t)rate);
    return (jlong)(intptr_t)ctx;
}

JNIEXPORT void JNICALL JFN(destroy)(JNIEnv *env, jobject thiz, jlong handle) {
    (void)env;
    (void)thiz;
    free((void *)(intptr_t)handle);
}

JNIEXPORT void JNICALL JFN(updateArray)(JNIEnv *env, jobject thiz, jlong handle,
                                        jbyteArray data, jint offset, jint length) {
    (void)thiz;
    kc_ctx *ctx = (kc_ctx *)(intptr_t)handle;
    if (!ctx || !data || length <= 0) return;
    jbyte *p = (*env)->GetPrimitiveArrayCritical(env, data, NULL);
    if (p) {
        kc_update(ctx, (const unsigned char *)p + offset, (size_t)length);
        (*env)->ReleasePrimitiveArrayCritical(env, data, p, JNI_ABORT);
    }
}

/* 引擎里的缓冲都是 direct ByteBuffer，直接拿地址、零拷贝 */
JNIEXPORT void JNICALL JFN(updateDirect)(JNIEnv *env, jobject thiz, jlong handle,
                                         jobject buffer, jint position, jint length) {
    (void)thiz;
    kc_ctx *ctx = (kc_ctx *)(intptr_t)handle;
    if (!ctx || !buffer || length <= 0) return;
    void *base = (*env)->GetDirectBufferAddress(env, buffer);
    if (base) kc_update(ctx, (const unsigned char *)base + position, (size_t)length);
}

JNIEXPORT jbyteArray JNICALL JFN(finish)(JNIEnv *env, jobject thiz, jlong handle,
                                         jint outLen, jint domain) {
    (void)thiz;
    kc_ctx *ctx = (kc_ctx *)(intptr_t)handle;
    jbyteArray out = (*env)->NewByteArray(env, outLen);
    if (!ctx || !out || outLen <= 0 || outLen > 128) return out;
    unsigned char tmp[128];
    kc_final(ctx, tmp, (size_t)outLen, (unsigned char)domain);
    (*env)->SetByteArrayRegion(env, out, 0, outLen, (const jbyte *)tmp);
    return out;
}