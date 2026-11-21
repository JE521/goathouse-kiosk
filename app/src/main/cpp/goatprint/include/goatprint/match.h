/* goatprint matching: NBIS MINDTCT minutiae extraction + BOZORTH3 matching.
 *
 * Template format "GPT1" (little-endian):
 *   "GPT1" | u16 count | u16 reserved | count x { i16 x, i16 y, i16 theta_deg, u8 quality, u8 type }
 * Coordinates are NIST/ANSI style (origin bottom-left) on a 500 dpi image.
 */
#pragma once
#include <stddef.h>
#include <stdint.h>

#include "goatprint/biomini.h"

#ifdef __cplusplus
extern "C" {
#endif

#define GP_MAX_MINUTIAE 150
#define GP_TEMPLATE_MAX (8 + GP_MAX_MINUTIAE * 8)

typedef struct {
    int16_t x, y, theta;
    uint8_t quality, type; /* type: 1 ridge ending, 2 bifurcation */
} gp_minutia;

typedef struct {
    int count;
    gp_minutia m[GP_MAX_MINUTIAE];
} gp_template;

/* BOZORTH3 score thresholds (see docs/MATCHING.md for the measurements).
 * NORMAL: identify people for scenes/logs. SECURE: locks and alarms. */
#define GP_MATCH_THRESHOLD 30
#define GP_MATCH_THRESHOLD_SECURE 45

/* image must be a normalised 500 dpi image (gp_image_normalize). */
gp_status gp_extract(const gp_image *img, gp_template *out);

/* BOZORTH3 score; >= GP_MATCH_THRESHOLD is a match. */
int gp_match(const gp_template *probe, const gp_template *gallery);
/* Best score of probe against n gallery templates; *best_index gets the winner. */
int gp_match_best(const gp_template *probe, const gp_template *gallery, int n, int *best_index);

size_t gp_template_serialize(const gp_template *t, uint8_t *buf, size_t cap);
gp_status gp_template_parse(gp_template *t, const uint8_t *buf, size_t len);

#ifdef __cplusplus
}
#endif
