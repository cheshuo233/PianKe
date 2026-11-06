/* film_engine: films.bin catalog, RGB565 preview table, photo development.
 * Pure C11. No ESP-IDF headers, no global state, not thread-safe (call from one task). */
#ifndef FILM_ENGINE_H
#define FILM_ENGINE_H

#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

/* Empty on the device; the PC build defines it as __declspec(dllexport). */
#ifndef FE_API
#define FE_API
#endif

typedef enum { FE_OK = 0, FE_ERR_INVALID_ARG, FE_ERR_NO_MEM, FE_ERR_BAD_FILMS } fe_err_t;

typedef struct {
    const char    *id;
    const char    *name;
    float          grain_strength;    /* 0..1 */
    float          grain_size;        /* px */
    float          halation_strength; /* 0..1 */
    uint8_t        halation_radius;
    uint8_t        halation_tint[3];
    uint8_t        lut_size;
    const uint8_t *lut;               /* points straight into the films.bin data (memory mapped), not copied */
} fe_film_t;

typedef struct fe_catalog fe_catalog_t;

typedef struct { uint16_t year; uint8_t month; uint8_t day; } fe_date_t;

typedef struct {
    uint8_t *rgb;                     /* RGB888, processed in place */
    uint16_t width, height;
    uint32_t stride;                  /* bytes per row */
} fe_image_t;

typedef struct {
    void *(*alloc)(size_t size);      /* PSRAM on the device; malloc on the PC */
    void  (*release)(void *ptr);
} fe_allocator_t;

typedef struct {
    const fe_film_t *film;            /* NULL: no film (no-filter mode), only the date stamp is added */
    const fe_date_t *date;            /* NULL: no date is printed */
    uint32_t         seed;            /* random seed of the grain */
} fe_develop_params_t;

FE_API fe_err_t         fe_catalog_open(const void *blob, size_t size, const fe_allocator_t *alloc, fe_catalog_t **out);
FE_API size_t           fe_catalog_count(const fe_catalog_t *catalog);
FE_API const fe_film_t *fe_catalog_film(const fe_catalog_t *catalog, size_t index);
FE_API int              fe_catalog_find(const fe_catalog_t *catalog, const char *id); /* -1 if not found */
FE_API void             fe_catalog_close(fe_catalog_t *catalog);

FE_API fe_err_t fe_preview_table_build(const fe_film_t *film, uint16_t table[65536]);
FE_API void     fe_preview_apply(const uint16_t table[65536], uint16_t *rgb565, size_t pixel_count);

FE_API fe_err_t fe_develop(const fe_develop_params_t *params, fe_image_t *image, const fe_allocator_t *alloc);

#ifdef __cplusplus
}
#endif

#endif /* FILM_ENGINE_H */
