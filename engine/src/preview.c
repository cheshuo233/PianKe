/* RGB565 preview table. Owns the 565 <-> 888 conversion rules; the colour itself comes from
 * fe_lut_apply_pixel (lut.c). Table index and entries are native-endian RGB565 values
 * (r << 11 | g << 5 | b). Needs no memory: the caller owns the 128 KB table. */
#include "film_engine.h"
#include "lut.h"

#define PREVIEW_ENTRIES 65536u

/* Bit replication, so 0 -> 0 and the maximum -> 255. */
static uint8_t expand_5(unsigned v5);
static uint8_t expand_6(unsigned v6);
/* Truncation, the inverse of the expansion: an identity LUT yields an identity table. */
static uint16_t pack_565(const uint8_t rgb[3]);

static uint8_t expand_5(unsigned v5)
{
    return (uint8_t)((v5 << 3) | (v5 >> 2));
}

static uint8_t expand_6(unsigned v6)
{
    return (uint8_t)((v6 << 2) | (v6 >> 4));
}

static uint16_t pack_565(const uint8_t rgb[3])
{
    return (uint16_t)(((rgb[0] >> 3) << 11) | ((rgb[1] >> 2) << 5) | (rgb[2] >> 3));
}

fe_err_t fe_preview_table_build(const fe_film_t *film, uint16_t table[65536])
{
    if (film == NULL || table == NULL) {
        return FE_ERR_INVALID_ARG;
    }
    for (unsigned i = 0; i < PREVIEW_ENTRIES; i++) {
        uint8_t rgb[3] = {expand_5(i >> 11), expand_6((i >> 5) & 63u), expand_5(i & 31u)};
        fe_lut_apply_pixel(film, rgb, rgb);
        table[i] = pack_565(rgb);
    }
    return FE_OK;
}

void fe_preview_apply(const uint16_t table[65536], uint16_t *rgb565, size_t pixel_count)
{
    for (size_t i = 0; i < pixel_count; i++) {
        rgb565[i] = table[rgb565[i]];
    }
}
