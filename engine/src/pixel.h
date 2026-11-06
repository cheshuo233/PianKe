/* Pixel rules shared by more than one stage. Each is defined here and nowhere else. */
#ifndef FE_PIXEL_H
#define FE_PIXEL_H

#include <stdint.h>

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
