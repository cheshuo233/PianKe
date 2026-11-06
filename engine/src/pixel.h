/* Pixel rules shared by more than one stage. Each is defined here and nowhere else. */
#ifndef FE_PIXEL_H
#define FE_PIXEL_H

#include <stdint.h>

#include "film_engine.h"

/* Pixel addressing. The only place that knows an image's pixels are `channels` bytes apart (3 or 4):
 * stages step with fe_pixel_step and write bytes 0..2 only, so the alpha byte of RGBA is never touched. */
static inline uint8_t *fe_pixel(const fe_image_t *image, uint32_t x, uint32_t y)
{
    return image->pixels + (size_t)y * image->stride + (size_t)x * image->channels;
}

static inline unsigned fe_pixel_step(const fe_image_t *image)
{
    return image->channels;
}

/* Saturate to 0..255. Every stage that adds to or scales a channel ends here, so nothing wraps. */
static inline uint8_t fe_clamp_u8(int v)
{
    return (uint8_t)(v < 0 ? 0 : (v > 255 ? 255 : v));
}

/* Relative luma 0..255 (Rec.601 weights, 77+150+29 = 256). Halation's highlight test and grain's
 * midtone weight both read brightness through this one definition. */
static inline unsigned fe_luma(unsigned r, unsigned g, unsigned b)
{
    return (77u * r + 150u * g + 29u * b + 128u) >> 8;
}

#endif /* FE_PIXEL_H */
