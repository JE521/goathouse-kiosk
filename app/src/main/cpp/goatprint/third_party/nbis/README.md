# NBIS (vendored)

MINDTCT (minutiae extraction) and BOZORTH3 (matching) from NIST Biometric Image
Software. Developed by NIST employees; **public domain** (see file headers).

Taken from libfprint's trimmed copy (`libfprint/nbis/`, which strips unused
NBIS parts and applies small fixes). glib calls are mapped back to libc by
`include/nbis-helpers.h`, so there is no glib dependency.
