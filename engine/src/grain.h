/* Grain stage of develop. Runs after the LUT. */
#ifndef FE_GRAIN_H
#define FE_GRAIN_H

#include "film_engine.h"

/* Adds monochrome grain to image in place (grain_size is in pixels of a 768 short side, scaled to the image). Returns before allocating when film->grain_strength is 0.
 * FE_ERR_NO_MEM if the temporary texture cannot be allocated (the image is then untouched). */
fe_err_t fe_grain_apply(const fe_film_t *film, uint32_t seed, fe_image_t *image, const fe_allocator_t *alloc);

#endif /* FE_GRAIN_H */
