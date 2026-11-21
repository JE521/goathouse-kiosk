/* goatprint BioMini driver: open-source implementation of the Suprema BioMini
 * Slim (16d1:0407) capture protocol, from docs/PROTOCOL.md. */
#pragma once
#include <stdint.h>

#include "goatprint/transport.h"

#ifdef __cplusplus
extern "C" {
#endif

#define GP_RAW_W 896
#define GP_RAW_H 432
#define GP_RAW_SIZE (GP_RAW_W * GP_RAW_H) /* 387072, one bulk transfer */

typedef struct gp_biomini gp_biomini;

typedef struct {
    char serial[33];
    char firmware[5];   /* e.g. "V02B" */
    char model[17];     /* e.g. "SBSFU-S2..." */
    uint8_t default_cfg[15];
    /* Active area of the raw frame (from the device EEPROM, block 0x50). */
    int roi_x0, roi_y0, roi_x1, roi_y1;
} gp_device_info;

/* Normalised output: whole sensor area, 500 dpi, portrait (fingertip up).
 * The vendor SDK crops to 320x480; we keep the full area for better matching.
 * Raw pixels are not square: measured from ridge spacing (docs/PROTOCOL.md),
 * one raw pixel = 0.698 output px along raw x and 0.858 along raw y. */
#define GP_IMG_W 352
#define GP_IMG_H 608
#define GP_IMG_DPI 500
#define GP_SCALE_X 0.698
#define GP_SCALE_Y 0.858

typedef struct {
    int width, height;
    uint8_t *pixels;    /* 8-bit grey, row-major, caller frees with gp_image_free */
    int contrast;       /* stddev of the centre region, 0..127 */
    int finger_metric;  /* device's finger-detect value for this frame */
} gp_image;

gp_status gp_biomini_open(gp_biomini **dev, gp_transport *t);
void gp_biomini_close(gp_biomini *dev);  /* turns the sensor off; does not close t */
const gp_device_info *gp_biomini_info(const gp_biomini *dev);

/* Grab one raw frame at the given exposure (SDK uses 123, 160, 999). */
gp_status gp_biomini_grab(gp_biomini *dev, int exposure, uint8_t *frame /* GP_RAW_SIZE */, int *finger_metric);

/* Wait up to timeout_ms for a finger, then return the best frame of the
 * finger while it is on the sensor. GP_ERR_TIMEOUT if no finger arrived. */
gp_status gp_biomini_capture(gp_biomini *dev, unsigned timeout_ms, gp_image *out);
/* Same, but returns up to `max` (<= 8) of the best frames of one touch, best first. */
gp_status gp_biomini_capture_n(gp_biomini *dev, unsigned timeout_ms, gp_image *out, int max, int *n);

/* Sensor illumination on/off (vendor request 0xC2: 04 = on, 00 = off). */
gp_status gp_biomini_light(gp_biomini *dev, int on);
/* Blink: `times` cycles of off/on, each half lasting half_ms. The "on" half keeps
 * grabbing frames so the illumination is really lit (as during normal polling).
 * Ends with the light on, or off when end_off is set. */
gp_status gp_biomini_flash(gp_biomini *dev, int times, unsigned half_ms, int end_off);

/* Wait until the finger is lifted (for multi-sample enrollment). */
gp_status gp_biomini_wait_lift(gp_biomini *dev, unsigned timeout_ms);

/* Scale to 500 dpi and rotate a raw frame into a GP_IMG_W x GP_IMG_H image. */
gp_status gp_image_normalize(const gp_image *raw, const gp_device_info *info, gp_image *out);
void gp_image_free(gp_image *img);
int gp_image_write_pgm(const gp_image *img, const char *path);

#ifdef __cplusplus
}
#endif
