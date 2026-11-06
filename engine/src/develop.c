/* fe_develop: argument checks and the fixed stage order. Holds no image rule of its own;
 * each stage is a module (halation.c, lut.c, grain.c, date_stamp.c).
 * Which stages run is decided here, once: film == NULL skips halation, LUT and grain; date == NULL
 * skips the date stamp. Order is the rule: halation -> 3D LUT -> grain -> date stamp. */
#include "date_stamp.h"
#include "grain.h"
#include "halation.h"
#include "lut.h"

fe_err_t fe_develop(const fe_develop_params_t *params, fe_image_t *image, const fe_allocator_t *alloc)
{
    if (params == NULL || image == NULL || image->pixels == NULL || alloc == NULL || alloc->alloc == NULL ||
        alloc->release == NULL || image->width == 0 || image->height == 0 ||
        (image->channels != 3 && image->channels != 4) || image->stride < (uint32_t)image->channels * image->width) {
        return FE_ERR_INVALID_ARG;
    }
    const fe_film_t *film = params->film;
    if (film != NULL) {
        fe_err_t err = fe_halation_apply(film, image, alloc);
        if (err != FE_OK) {
            return err;
        }
        fe_lut_apply_image(film, image);
        err = fe_grain_apply(film, params->seed, image, alloc);
        if (err != FE_OK) {
            return err;
        }
    }
    if (params->date != NULL) {
        fe_date_stamp_apply(params->date, image);
    }
    return FE_OK;
}
