/* 3D LUT application: the only tetrahedral interpolation in the engine. Used by the preview table
 * (one pixel at a time) and by develop (whole image). */
#ifndef FE_LUT_H
#define FE_LUT_H

#include "film_engine.h"

/* One RGB888 pixel through film->lut (film->lut_size^3 RGB888, index (b*N + g)*N + r). in and out may alias. */
void fe_lut_apply_pixel(const fe_film_t *film, const uint8_t in[3], uint8_t out[3]);

/* Every pixel of image, in place. Needs no memory. */
void fe_lut_apply_image(const fe_film_t *film, fe_image_t *image);

#endif /* FE_LUT_H */
