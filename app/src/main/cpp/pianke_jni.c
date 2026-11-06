/* JNI glue between app.pianke.film.FilmEngine and the fe_* C API.
 * Converts only: fe_err_t -> EngineException (throw_engine), and the user-setting -> film-parameter
 * rule (apply_settings). */
#include <android/asset_manager.h>
#include <android/asset_manager_jni.h>
#include <android/bitmap.h>
#include <jni.h>
#include <stdlib.h>

#include "film_engine.h"

/* What `open` returns as a jlong: the asset must stay open while the catalog points into its buffer. */
typedef struct {
    AAsset       *asset;
    fe_catalog_t *catalog;
} engine_t;

static const fe_allocator_t ALLOC = {malloc, free};

/* The only place where fe_err_t becomes EngineException (spec 6 row 20). */
static void throw_engine(JNIEnv *env, fe_err_t code)
{
    jclass cls = (*env)->FindClass(env, "app/pianke/film/EngineException");
    if (cls == NULL) {
        return; /* NoClassDefFoundError is already pending */
    }
    jmethodID ctor = (*env)->GetMethodID(env, cls, "<init>", "(I)V");
    if (ctor == NULL) {
        return;
    }
    jobject ex = (*env)->NewObject(env, cls, ctor, (jint)code);
    if (ex != NULL) {
        (*env)->Throw(env, (jthrowable)ex);
    }
}

/* The only place where user settings change film parameters (spec 6 row 9): a copy of the film with
 * grain_strength *= grain_scale, and halation_strength = 0 when halation is off. */
static fe_film_t apply_settings(const fe_film_t *film, float grain_scale, jboolean halation)
{
    fe_film_t out = *film;
    out.grain_strength *= grain_scale;
    if (!halation) {
        out.halation_strength = 0.0f;
    }
    return out;
}

/* Opens assets/films.bin with AAsset_getBuffer (zero copy; needs the asset packed uncompressed). A missing
 * asset or buffer is EngineException(FE_ERR_BAD_FILMS), so FilmCatalog.load sees one failure kind. */
JNIEXPORT jlong JNICALL Java_app_pianke_film_FilmEngine_open(JNIEnv *env, jobject self, jobject assets)
{
    (void)self;
    AAssetManager *mgr = AAssetManager_fromJava(env, assets);
    AAsset *asset = mgr != NULL ? AAssetManager_open(mgr, "films.bin", AASSET_MODE_BUFFER) : NULL;
    const void *buf = asset != NULL ? AAsset_getBuffer(asset) : NULL;
    if (buf == NULL) {
        if (asset != NULL) {
            AAsset_close(asset);
        }
        throw_engine(env, FE_ERR_BAD_FILMS);
        return 0;
    }
    engine_t *eng = malloc(sizeof *eng);
    if (eng == NULL) {
        AAsset_close(asset);
        throw_engine(env, FE_ERR_NO_MEM);
        return 0;
    }
    fe_err_t err = fe_catalog_open(buf, (size_t)AAsset_getLength(asset), &ALLOC, &eng->catalog);
    if (err != FE_OK) {
        free(eng);
        AAsset_close(asset);
        throw_engine(env, err);
        return 0;
    }
    eng->asset = asset;
    return (jlong)(intptr_t)eng;
}

JNIEXPORT jobjectArray JNICALL Java_app_pianke_film_FilmEngine_ids(JNIEnv *env, jobject self, jlong handle)
{
    (void)self;
    const engine_t *eng = (const engine_t *)(intptr_t)handle;
    jsize count = (jsize)fe_catalog_count(eng->catalog);
    jclass string_cls = (*env)->FindClass(env, "java/lang/String");
    if (string_cls == NULL) {
        return NULL;
    }
    jobjectArray arr = (*env)->NewObjectArray(env, count, string_cls, NULL);
    if (arr == NULL) {
        return NULL;
    }
    for (jsize i = 0; i < count; i++) {
        jstring s = (*env)->NewStringUTF(env, fe_catalog_film(eng->catalog, (size_t)i)->id);
        if (s == NULL) {
            return NULL;
        }
        (*env)->SetObjectArrayElement(env, arr, i, s);
        (*env)->DeleteLocalRef(env, s);
    }
    return arr;
}

JNIEXPORT jint JNICALL Java_app_pianke_film_FilmEngine_lutSize(JNIEnv *env, jobject self, jlong handle)
{
    (void)env;
    (void)self;
    const engine_t *eng = (const engine_t *)(intptr_t)handle;
    return fe_catalog_film(eng->catalog, 0)->lut_size;
}

/* Returns NewDirectByteBuffer(...).asReadOnlyBuffer(): the memory is the read-only asset mapping, and a
 * read-only view of a direct buffer is still direct. FilmCatalog.lut passes it through. */
JNIEXPORT jobject JNICALL Java_app_pianke_film_FilmEngine_lut(JNIEnv *env, jobject self, jlong handle, jint index)
{
    (void)self;
    const engine_t *eng = (const engine_t *)(intptr_t)handle;
    const fe_film_t *film = fe_catalog_film(eng->catalog, (size_t)index);
    jlong bytes = (jlong)film->lut_size * film->lut_size * film->lut_size * 3;
    jobject buf = (*env)->NewDirectByteBuffer(env, (void *)film->lut, bytes);
    if (buf == NULL) {
        return NULL;
    }
    jclass cls = (*env)->GetObjectClass(env, buf);
    jmethodID ro = (*env)->GetMethodID(env, cls, "asReadOnlyBuffer", "()Ljava/nio/ByteBuffer;");
    if (ro == NULL) {
        return NULL;
    }
    return (*env)->CallObjectMethod(env, buf, ro);
}

JNIEXPORT void JNICALL Java_app_pianke_film_FilmEngine_develop(JNIEnv *env, jobject self, jlong handle, jint index,
                                                               jfloat grain_scale, jboolean halation, jint date_ymd,
                                                               jint seed, jobject bitmap)
{
    (void)self;
    const engine_t *eng = (const engine_t *)(intptr_t)handle;
    AndroidBitmapInfo info;
    void *pixels = NULL;
    if (AndroidBitmap_getInfo(env, bitmap, &info) != ANDROID_BITMAP_RESULT_SUCCESS ||
        info.format != ANDROID_BITMAP_FORMAT_RGBA_8888 || info.width > UINT16_MAX || info.height > UINT16_MAX ||
        AndroidBitmap_lockPixels(env, bitmap, &pixels) != ANDROID_BITMAP_RESULT_SUCCESS) {
        throw_engine(env, FE_ERR_INVALID_ARG);
        return;
    }
    fe_film_t film = apply_settings(fe_catalog_film(eng->catalog, (size_t)index), grain_scale, halation);
    /* dateYmd is year*10000 + month*100 + day; 0 = no date (defined in FilmEngine.kt). */
    fe_date_t date = {(uint16_t)(date_ymd / 10000), (uint8_t)(date_ymd / 100 % 100), (uint8_t)(date_ymd % 100)};
    fe_develop_params_t params = {&film, date_ymd != 0 ? &date : NULL, (uint32_t)seed};
    fe_image_t image = {(uint8_t *)pixels, (uint16_t)info.width, (uint16_t)info.height, info.stride, 4};
    fe_err_t err = fe_develop(&params, &image, &ALLOC);
    AndroidBitmap_unlockPixels(env, bitmap);
    if (err != FE_OK) {
        throw_engine(env, err);
    }
}
