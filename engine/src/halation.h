/* Halation stage of develop. Runs first, on the untouched sensor colours. */
#ifndef FE_HALATION_H
#define FE_HALATION_H

#include "film_engine.h"

/* Adds the film's halation glow to image in place (any width and height >= 1). Returns before
 * allocating when film->halation_strength is 0. Without highlights the map is zero, so the glow
 * adds nothing and the image is unchanged. FE_ERR_NO_MEM if the temporary map buffers cannot be
 * allocated (the image is then untouched). */
fe_err_t fe_halation_apply(const fe_film_t *film, fe_image_t *image, const fe_allocator_t *alloc);

#endif /* FE_HALATION_H */
