package org.goathouse.kiosk;

/** goatprint's C core (normaliser + NBIS MINDTCT/BOZORTH3) over JNI. Call from one thread only. */
final class GoatprintCore {
    static { System.loadLibrary("goatprint"); }
    static final int IMG_W = 352, IMG_H = 608;
    /** Raw 896x432 frame → normalised 352x608 500 dpi image, or null. */
    static native byte[] normalize(byte[] raw);
    /** Normalised image → serialised GPT1 template, or null if nothing usable. */
    static native byte[] extract(byte[] img);
    /** BOZORTH3 score of probe against gallery template (negative on bad input). */
    static native int score(byte[] probe, byte[] gallery);
    /** Minutiae count of a template (negative on bad input). */
    static native int minutiae(byte[] template);
    private GoatprintCore() { }
}
