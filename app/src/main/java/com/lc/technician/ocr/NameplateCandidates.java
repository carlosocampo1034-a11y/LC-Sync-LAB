package com.lc.technician.ocr;

import java.io.Serializable;

/**
 * POJO holding the raw OCR text plus every field the NameplateParser
 * was able to detect with reasonable confidence.
 *
 * null means "not found / not confident" — never an empty string fabricated
 * from noise. The NameplateReviewActivity shows these values to the
 * technician for confirmation before anything is written to the DB.
 */
public class NameplateCandidates implements Serializable {
    public String manufacturer;
    public String model;
    public String serial;
    public String tons;
    public String refrigerant;
    public String voltage;
    public String phase;
    public String seer;
    public String manufactureDate;
    /** Full raw text returned by ML Kit — useful for debugging in the review screen. */
    public String rawText;
}
