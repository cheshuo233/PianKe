/* films.bin reader (spec K1). The only C code that knows the file's byte offsets, and the only place
 * that turns its quantized fields (0..255, size x16) into the floats of fe_film_t. The parameter
 * bytes are passed on unchecked (each stage clamps the value it uses); only what could make the
 * engine read outside the blob is validated here.
 * Every bound is checked against the blob size before the bytes are read, and written without an
 * addition or multiplication that could wrap: size_t may be only 32 bits (32-bit targets). */
#include <stdint.h>
#include <string.h>

#include "film_engine.h"

/* films.bin layout, little endian. */
enum {
    HDR_SIZE = 16,
    HDR_MAGIC = 0,      /* "FILM" */
    HDR_VERSION = 4,    /* u16, must be 1 */
    HDR_FILM_COUNT = 6, /* u16, must be > 0 */
    HDR_LUT_SIZE = 8,   /* u8 */

    ENT_SIZE = 68,
    ENT_ID = 0,    /* char[24], NUL terminated */
    ENT_ID_LEN = 24,
    ENT_NAME = 24, /* char[32], NUL terminated */
    ENT_NAME_LEN = 32,
    ENT_GRAIN_STRENGTH = 56,
    ENT_GRAIN_SIZE_Q4 = 57,
    ENT_HALATION_STRENGTH = 58,
    ENT_HALATION_RADIUS = 59,
    ENT_HALATION_TINT = 60, /* u8[3] */
    ENT_LUT_OFFSET = 64     /* u32, from the start of the file */
};

struct fe_catalog {
    fe_allocator_t alloc;   /* kept for fe_catalog_close */
    size_t         count;
    fe_film_t     *films;   /* count entries, in the same allocation, right after this struct */
};

/* count is a u16, so the one allocation (struct + count films) cannot wrap even a 32-bit size_t. */
_Static_assert(sizeof(struct fe_catalog) + 65535u * sizeof(fe_film_t) <= (size_t)0xFFFFFFFFu,
               "catalog allocation size must fit in 32 bits");

static uint16_t rd_u16(const uint8_t *p)
{
    return (uint16_t)(p[0] | (p[1] << 8));
}

static uint32_t rd_u32(const uint8_t *p)
{
    return (uint32_t)p[0] | ((uint32_t)p[1] << 8) | ((uint32_t)p[2] << 16) | ((uint32_t)p[3] << 24);
}

/* Validates the header (magic "FILM", version 1, film_count > 0, lut_size >= 2 so interpolation always
 * has a neighbour node to read) and that the entry table fits in the blob. FE_ERR_BAD_FILMS otherwise. */
static fe_err_t parse_header(const uint8_t *blob, size_t size, size_t *film_count, uint8_t *lut_size)
{
    if (size < HDR_SIZE || memcmp(blob + HDR_MAGIC, "FILM", 4) != 0 || rd_u16(blob + HDR_VERSION) != 1) {
        return FE_ERR_BAD_FILMS;
    }
    const size_t count = rd_u16(blob + HDR_FILM_COUNT);
    const uint8_t lut = blob[HDR_LUT_SIZE];
    /* count entries fit behind the header: count * ENT_SIZE <= size - HDR_SIZE. */
    if (count == 0 || lut < 2 || count > (size - HDR_SIZE) / ENT_SIZE) {
        return FE_ERR_BAD_FILMS;
    }
    *film_count = count;
    *lut_size = lut;
    return FE_OK;
}

/* Validates entry `index` (id and name NUL terminated, so they are safe C strings; LUT inside the blob,
 * checked as `lut_bytes <= size && offset <= size - lut_bytes`) and fills *film. */
static fe_err_t parse_entry(const uint8_t *blob, size_t size, size_t index, uint8_t lut_size, fe_film_t *film)
{
    const uint8_t *e = blob + HDR_SIZE + index * ENT_SIZE; /* index < count: inside the table checked above */
    if (memchr(e + ENT_ID, 0, ENT_ID_LEN) == NULL || memchr(e + ENT_NAME, 0, ENT_NAME_LEN) == NULL) {
        return FE_ERR_BAD_FILMS;
    }
    const size_t lut_bytes = (size_t)lut_size * lut_size * lut_size * 3u; /* <= 255^3 * 3 < 2^32 */
    const uint32_t lut_offset = rd_u32(e + ENT_LUT_OFFSET);
    if (lut_bytes > size || lut_offset > size - lut_bytes) {
        return FE_ERR_BAD_FILMS;
    }
    film->id = (const char *)(e + ENT_ID);
    film->name = (const char *)(e + ENT_NAME);
    film->grain_strength = e[ENT_GRAIN_STRENGTH] / 255.0f;
    film->grain_size = e[ENT_GRAIN_SIZE_Q4] / 16.0f;
    film->halation_strength = e[ENT_HALATION_STRENGTH] / 255.0f;
    film->halation_radius = e[ENT_HALATION_RADIUS];
    memcpy(film->halation_tint, e + ENT_HALATION_TINT, 3);
    film->lut_size = lut_size;
    film->lut = blob + lut_offset;
    return FE_OK;
}

fe_err_t fe_catalog_open(const void *blob, size_t size, const fe_allocator_t *alloc, fe_catalog_t **out)
{
    if (blob == NULL || alloc == NULL || alloc->alloc == NULL || alloc->release == NULL || out == NULL) {
        return FE_ERR_INVALID_ARG;
    }
    *out = NULL;
    size_t count;
    uint8_t lut_size;
    fe_err_t err = parse_header((const uint8_t *)blob, size, &count, &lut_size);
    if (err != FE_OK) {
        return err;
    }
    struct fe_catalog *catalog = alloc->alloc(sizeof(*catalog) + count * sizeof(fe_film_t));
    if (catalog == NULL) {
        return FE_ERR_NO_MEM;
    }
    catalog->alloc = *alloc;
    catalog->count = count;
    catalog->films = (fe_film_t *)(catalog + 1);
    for (size_t i = 0; i < count; i++) {
        err = parse_entry((const uint8_t *)blob, size, i, lut_size, &catalog->films[i]);
        if (err != FE_OK) {
            alloc->release(catalog);
            return err;
        }
    }
    *out = catalog;
    return FE_OK;
}

size_t fe_catalog_count(const fe_catalog_t *catalog)
{
    return catalog != NULL ? catalog->count : 0;
}

const fe_film_t *fe_catalog_film(const fe_catalog_t *catalog, size_t index)
{
    return (catalog != NULL && index < catalog->count) ? &catalog->films[index] : NULL;
}

int fe_catalog_find(const fe_catalog_t *catalog, const char *id)
{
    if (catalog == NULL || id == NULL) {
        return -1;
    }
    for (size_t i = 0; i < catalog->count; i++) {
        if (strcmp(catalog->films[i].id, id) == 0) {
            return (int)i;
        }
    }
    return -1;
}

void fe_catalog_close(fe_catalog_t *catalog)
{
    if (catalog != NULL) {
        void (*release)(void *) = catalog->alloc.release;
        release(catalog);
    }
}
