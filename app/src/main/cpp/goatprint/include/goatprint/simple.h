/* goatprint simple API: a small, flat, handle-based C ABI over the driver and
 * matcher, for bindings (Python ctypes in goatprintd, JNI on Android). */
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

typedef struct gp_scanner gp_scanner;

/* Open the first scanner (fd < 0) or wrap an Android USB fd (fd >= 0). */
int gp_scanner_open(gp_scanner **s, int fd);
void gp_scanner_close(gp_scanner *s);
/* NUL-terminated serial / firmware strings owned by the handle. */
const char *gp_scanner_serial(gp_scanner *s);
const char *gp_scanner_firmware(gp_scanner *s);

/* Wait up to timeout_ms for a finger. On success writes up to max_probes
 * serialized GPT1 templates (from the sharpest frames) into buf, each prefixed
 * with a little-endian u16 length; returns the number of templates, 0 on
 * timeout, negative gp_status on error. */
int gp_scanner_capture(gp_scanner *s, unsigned timeout_ms, int max_probes, uint8_t *buf, size_t cap, size_t *used);
/* Turn the sensor light on (1) or off (0). */
int gp_scanner_light(gp_scanner *s, int on);
/* Blink the light `times` times (half_ms per half cycle); end off if end_off. */
int gp_scanner_flash(gp_scanner *s, int times, unsigned half_ms, int end_off);
/* Returns 1 once the finger has been lifted, 0 on timeout, negative on error. */
int gp_scanner_wait_lift(gp_scanner *s, unsigned timeout_ms);
/* Writes the normalised image of the next finger (GP_IMG_W*GP_IMG_H bytes). */
int gp_scanner_capture_image(gp_scanner *s, unsigned timeout_ms, uint8_t *pixels, size_t cap, int *w, int *h);

/* BOZORTH3 score of one serialized template against another (negative on bad input). */
int gp_score(const uint8_t *probe, size_t probe_len, const uint8_t *gallery, size_t gallery_len);
/* Minutiae count of a serialized template. */
int gp_template_minutiae(const uint8_t *t, size_t len);

int gp_threshold_normal(void);
int gp_threshold_secure(void);
const char *gp_error_string(int status);

#ifdef __cplusplus
}
#endif
