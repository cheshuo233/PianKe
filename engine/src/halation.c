/* Halation: bright areas bleed a tinted glow. Owns the highlight threshold and the 96x96 working
 * resolution; the film only supplies strength, radius (in 96x96 pixels) and tint.
 * Pipeline: gather highlights -> average down to a map whose long side is THUMB_LONG cells and whose
 * short side keeps the image aspect (D1: square cells, so the glow is round) -> blur -> add back,
 * bilinearly upscaled, tinted and scaled by strength, saturating at 255. Works for any image size >= 1x1;
 * the 8x8 cell is the 768x768 case. */
#include "halation.h"
#include "pixel.h"

#define THUMB_LONG 96u /* map cells along the image's long side */
/* Engine-wide, not per film: only luma at or above this takes part (>= 204, i.e. 0.8 of full scale).
 * A highlight's energy ramps from 0 at the threshold to 255 at white, so the glow has no hard edge. */
#define HIGHLIGHT_THRESHOLD 204u

/* The energy map: w x h cells, w * h <= THUMB_LONG^2. Cells are square: the long side has THUMB_LONG
 * cells, the short side round(THUMB_LONG * short / long), at least 1 (D1). */
typedef struct {
    uint32_t w, h;
} thumb_dims_t;
static thumb_dims_t thumb_dims(const fe_image_t *image);

/* Bilinear sampling of an `cells`-wide map along one image axis of n pixels: sample i sits at map
 * coordinate (i + 0.5) * cells / n - 0.5, kept in Q8 and clamped to the map. Stepped from one pixel
 * to the next without a division per pixel. */
typedef struct {
    uint32_t n;      /* image pixels along the axis */
    uint32_t cells;  /* map cells along the axis */
    uint32_t q, r;   /* quotient and remainder of ((2i + 1) * cells * 128) / n */
    uint32_t dq, dr; /* the same for the step 2 * cells * 128 */
} axis_t;
static void axis_init(axis_t *axis, uint32_t n, uint32_t cells);
static void axis_next(axis_t *axis);
/* Map cells i0 and i1 (= i0 + 1, or i0 at the far edge) and the weight of i1 in 0..255, for the current pixel. */
static void axis_sample(const axis_t *axis, uint32_t *i0, uint32_t *i1, uint32_t *frac);

/* Average image down to the energy map (dims.w x dims.h); pixels below the threshold count as 0. */
static void gather_highlights(const fe_image_t *image, thumb_dims_t dims, uint8_t *thumb);

/* Box blur of the map, radius 0..255 in map cells (the window is clipped to the map, light that
 * leaves the map is lost); scratch is another dims.w * dims.h bytes. */
static void blur_thumb(uint8_t *thumb, uint8_t *scratch, thumb_dims_t dims, unsigned radius);

/* Add tint * glow * strength to every pixel (map sampled bilinearly), saturating. */
static void composite_glow(fe_image_t *image, const uint8_t *thumb, thumb_dims_t dims, const fe_film_t *film);

static thumb_dims_t thumb_dims(const fe_image_t *image)
{
    (void)image; /* PHASE B */
    thumb_dims_t dims = {THUMB_LONG, THUMB_LONG};
    return dims;
}

static void axis_init(axis_t *axis, uint32_t n, uint32_t cells)
{
    const uint32_t start = cells * 128u, step = 2u * cells * 128u;
    axis->n = n;
    axis->cells = cells;
    axis->q = start / n;
    axis->r = start % n;
    axis->dq = step / n;
    axis->dr = step % n;
}

static void axis_next(axis_t *axis)
{
    axis->q += axis->dq;
    axis->r += axis->dr;
    if (axis->r >= axis->n) {
        axis->r -= axis->n;
        axis->q++;
    }
}

static void axis_sample(const axis_t *axis, uint32_t *i0, uint32_t *i1, uint32_t *frac)
{
    const uint32_t last = (axis->cells - 1u) * 256u;
    uint32_t pos = axis->q > 128u ? axis->q - 128u : 0u;
    if (pos > last) {
        pos = last;
    }
    *i0 = pos >> 8;
    *i1 = *i0 + 1u < axis->cells ? *i0 + 1u : *i0;
    *frac = pos & 255u;
}

static void gather_highlights(const fe_image_t *image, thumb_dims_t dims, uint8_t *thumb)
{
    (void)image; /* PHASE B */
    (void)dims;
    (void)thumb;
}

static void blur_thumb(uint8_t *thumb, uint8_t *scratch, thumb_dims_t dims, unsigned radius)
{
    (void)thumb; /* PHASE B */
    (void)scratch;
    (void)dims;
    (void)radius;
}

static void composite_glow(fe_image_t *image, const uint8_t *thumb, thumb_dims_t dims, const fe_film_t *film)
{
    (void)image; /* PHASE B */
    (void)thumb;
    (void)dims;
    (void)film;
}

fe_err_t fe_halation_apply(const fe_film_t *film, fe_image_t *image, const fe_allocator_t *alloc)
{
    if (!(film->halation_strength > 0.0f)) {
        return FE_OK;
    }
    const thumb_dims_t dims = thumb_dims(image);
    const size_t cells = (size_t)dims.w * dims.h;
    uint8_t *buffers = alloc->alloc(2u * cells);
    if (buffers == NULL) {
        return FE_ERR_NO_MEM;
    }
    uint8_t *thumb = buffers, *scratch = buffers + cells;
    gather_highlights(image, dims, thumb);
    blur_thumb(thumb, scratch, dims, film->halation_radius);
    composite_glow(image, thumb, dims, film);
    alloc->release(buffers);
    return FE_OK;
}
