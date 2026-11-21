/* goatprint transport: thin layer over libusb so the same driver code runs on
 * Linux (libusb enumerates) and Android (Java opens the device via UsbManager
 * and hands us a file descriptor, which libusb wraps). */
#pragma once
#include <stddef.h>
#include <stdint.h>

#ifdef __cplusplus
extern "C" {
#endif

#define GP_SUPREMA_VID 0x16d1

typedef struct gp_transport gp_transport;

typedef enum {
    GP_OK = 0,
    GP_ERR_NOT_FOUND = -1,
    GP_ERR_ACCESS = -2,
    GP_ERR_IO = -3,
    GP_ERR_TIMEOUT = -4,
    GP_ERR_NOMEM = -5,
    GP_ERR_ARG = -6,
    GP_ERR_BUSY = -7,
} gp_status;

typedef struct {
    uint16_t vid, pid;
    uint8_t ep_out, ep_in;
} gp_usb_info;

/* Linux: open the first Suprema device (pid 0 = any known pid). */
gp_status gp_transport_open(gp_transport **t, uint16_t pid);
/* Android: wrap an fd from UsbDeviceConnection.getFileDescriptor(). */
gp_status gp_transport_open_fd(gp_transport **t, int fd);
void gp_transport_close(gp_transport *t);
const gp_usb_info *gp_transport_info(const gp_transport *t);

/* Bulk transfers on the scanner's OUT/IN endpoints. *done = bytes moved. */
gp_status gp_bulk_out(gp_transport *t, const uint8_t *buf, size_t len, size_t *done, unsigned timeout_ms);
gp_status gp_bulk_in(gp_transport *t, uint8_t *buf, size_t len, size_t *done, unsigned timeout_ms);
/* Control transfer; returns bytes transferred or negative gp_status. */
int gp_control(gp_transport *t, uint8_t bmRequestType, uint8_t bRequest, uint16_t wValue,
               uint16_t wIndex, uint8_t *data, uint16_t wLength, unsigned timeout_ms);

/* Set GP_TRACE=1 in the environment to hexdump every transfer to stderr. */
const char *gp_strerror(gp_status s);

#ifdef __cplusplus
}
#endif
