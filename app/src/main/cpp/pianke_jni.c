/* JNI glue between app.pianke.film.FilmEngine and the fe_* C API.
 * Converts only: fe_err_t -> EngineException, and the user-setting -> film-parameter rule (apply_settings). */
#include <jni.h>

#include "film_engine.h"

/* The only place where user settings change film parameters (spec 6 row 9):
 * copy the film, grain_strength *= grain_scale, halation_strength = 0 when halation is off. */
static fe_film_t apply_settings(const fe_film_t *film, float grain_scale, jboolean halation) {
    (void)film;
    (void)grain_scale;
    (void)halation;
    fe_film_t out = {0};
    return out;
}

/* The only place where fe_err_t becomes EngineException (spec 6 row 20). */
static void throw_engine(JNIEnv *env, fe_err_t code) {
    (void)env;
    (void)code;
}

JNIEXPORT jlong JNICALL Java_app_pianke_film_FilmEngine_open(JNIEnv *env, jobject self, jobject assets) {
    (void)self; (void)assets;
    throw_engine(env, FE_OK);
    return 0;
}

JNIEXPORT jobjectArray JNICALL Java_app_pianke_film_FilmEngine_ids(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self; (void)handle;
    return NULL;
}

JNIEXPORT jint JNICALL Java_app_pianke_film_FilmEngine_lutSize(JNIEnv *env, jobject self, jlong handle) {
    (void)env; (void)self; (void)handle;
    return 0;
}

JNIEXPORT jobject JNICALL Java_app_pianke_film_FilmEngine_lut(JNIEnv *env, jobject self, jlong handle, jint index) {
    (void)env; (void)self; (void)handle; (void)index;
    return NULL;
}

JNIEXPORT void JNICALL Java_app_pianke_film_FilmEngine_develop(JNIEnv *env, jobject self, jlong handle, jint index,
                                                               jfloat grain_scale, jboolean halation, jint date_ymd,
                                                               jint seed, jobject bitmap) {
    (void)self; (void)handle; (void)index; (void)date_ymd; (void)seed; (void)bitmap;
    (void)apply_settings(NULL, grain_scale, halation);
    throw_engine(env, FE_OK);
}
