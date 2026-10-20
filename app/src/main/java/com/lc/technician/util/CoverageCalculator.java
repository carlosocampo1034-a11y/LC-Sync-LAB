package com.lc.technician.util;

import com.lc.technician.model.CoverageTypeEntity;

public class CoverageCalculator {

    public static class Result {
        public double totalOriginal;
        public double deductible;
        public double coveragePct;
        public double totalCliente;
        public double totalInsurer;
        public boolean hasCoverage;
    }

    // Formula: totalCliente = deductible + (total - deductible) * (1 - coveragePercentage/100)
    //          totalInsurer  = (total - deductible) * (coveragePercentage/100)
    public static Result calcular(CoverageTypeEntity cov, double total) {
        Result r = new Result();
        r.totalOriginal = total;
        if (cov == null || "NONE".equals(cov.code) || cov.coveragePercentage <= 0) {
            r.hasCoverage = false;
            r.deductible = 0;
            r.coveragePct = 0;
            r.totalCliente = total;
            r.totalInsurer = 0;
            return r;
        }
        r.hasCoverage = true;
        r.deductible = Math.max(0, Math.min(cov.deductibleAmount, total));
        double base = total - r.deductible;
        r.coveragePct = Math.max(0, Math.min(cov.coveragePercentage, 100));
        r.totalInsurer = base * (r.coveragePct / 100.0);
        r.totalCliente = total - r.totalInsurer;
        return r;
    }
}
