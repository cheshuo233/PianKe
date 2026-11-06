/* Date stamp stage of develop. Runs last, so the stamp is never touched by the film look. */
#ifndef FE_DATE_STAMP_H
#define FE_DATE_STAMP_H

#include "film_engine.h"

/* Draws "'YY MM DD" (orange seven-segment digits) at the bottom-right of image, in place.
 * Digit height is min(width, height)/24. Touches only pixels inside the stamp rectangle. Needs no memory. */
void fe_date_stamp_apply(const fe_date_t *date, fe_image_t *image);

#endif /* FE_DATE_STAMP_H */
