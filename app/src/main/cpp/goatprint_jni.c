/* JNI bridge to goatprint's C core: normalise a raw BioMini frame, extract a GPT1 template, score two templates.
 * USB stays in Java (GoatprintReader); this is the same normaliser and NBIS matcher the Linux daemon uses, so
 * templates and scores are identical across nodes. All entry points are called from one thread. */
#include <jni.h>
#include <stdlib.h>
#include <string.h>

#include "goatprint/match.h"

JNIEXPORT jbyteArray JNICALL Java_org_goathouse_kiosk_GoatprintCore_normalize(JNIEnv *env, jclass cls, jbyteArray raw) {
    (void)cls;
    if ((*env)->GetArrayLength(env, raw) != GP_RAW_SIZE) return NULL;
    jbyte *px = (*env)->GetByteArrayElements(env, raw, NULL);
    gp_image in = {GP_RAW_W, GP_RAW_H, (uint8_t *)px, 0, 0}, out = {0};
    gp_status s = gp_image_normalize(&in, NULL, &out);
    (*env)->ReleaseByteArrayElements(env, raw, px, JNI_ABORT);
    if (s) return NULL;
    jbyteArray r = (*env)->NewByteArray(env, GP_IMG_W * GP_IMG_H);
    (*env)->SetByteArrayRegion(env, r, 0, GP_IMG_W * GP_IMG_H, (const jbyte *)out.pixels);
    free(out.pixels);
    return r;
}

/* Normalised 352x608 image -> serialised GPT1 template, or null. */
JNIEXPORT jbyteArray JNICALL Java_org_goathouse_kiosk_GoatprintCore_extract(JNIEnv *env, jclass cls, jbyteArray img) {
    (void)cls;
    if ((*env)->GetArrayLength(env, img) != GP_IMG_W * GP_IMG_H) return NULL;
    static gp_template t;
    static uint8_t buf[GP_TEMPLATE_MAX];
    jbyte *px = (*env)->GetByteArrayElements(env, img, NULL);
    gp_image in = {GP_IMG_W, GP_IMG_H, (uint8_t *)px, 0, 0};
    gp_status s = gp_extract(&in, &t);
    (*env)->ReleaseByteArrayElements(env, img, px, JNI_ABORT);
    if (s || t.count <= 0) return NULL;
    size_t n = gp_template_serialize(&t, buf, sizeof buf);
    if (!n) return NULL;
    jbyteArray r = (*env)->NewByteArray(env, (jsize)n);
    (*env)->SetByteArrayRegion(env, r, 0, (jsize)n, (const jbyte *)buf);
    return r;
}

JNIEXPORT jint JNICALL Java_org_goathouse_kiosk_GoatprintCore_score(JNIEnv *env, jclass cls, jbyteArray p, jbyteArray g) {
    (void)cls;
    static gp_template a, b;
    jsize pl = (*env)->GetArrayLength(env, p), gl = (*env)->GetArrayLength(env, g);
    jbyte *pp = (*env)->GetByteArrayElements(env, p, NULL), *gg = (*env)->GetByteArrayElements(env, g, NULL);
    int bad = gp_template_parse(&a, (const uint8_t *)pp, (size_t)pl) || gp_template_parse(&b, (const uint8_t *)gg, (size_t)gl);
    (*env)->ReleaseByteArrayElements(env, p, pp, JNI_ABORT);
    (*env)->ReleaseByteArrayElements(env, g, gg, JNI_ABORT);
    return bad ? GP_ERR_ARG : gp_match(&a, &b);
}

JNIEXPORT jint JNICALL Java_org_goathouse_kiosk_GoatprintCore_minutiae(JNIEnv *env, jclass cls, jbyteArray t) {
    (void)cls;
    static gp_template x;
    jsize l = (*env)->GetArrayLength(env, t);
    jbyte *p = (*env)->GetByteArrayElements(env, t, NULL);
    int bad = gp_template_parse(&x, (const uint8_t *)p, (size_t)l);
    (*env)->ReleaseByteArrayElements(env, t, p, JNI_ABORT);
    return bad ? GP_ERR_ARG : x.count;
}
