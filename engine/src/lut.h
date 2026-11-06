/* 3D LUT application: the only tetrahedral interpolation in the engine. */
#ifndef FE_LUT_H
#define FE_LUT_H

#include "film_engine.h"

/* Every pixel of image, in place, through film->lut (film->lut_size^3 RGB888, index (b*N + g)*N + r).
 * Writes bytes 0..2 of each pixel. Needs no memory. */
void fe_lut_apply_image(const fe_film_t *film, fe_image_t *image);

#endif /* FE_LUT_H */
