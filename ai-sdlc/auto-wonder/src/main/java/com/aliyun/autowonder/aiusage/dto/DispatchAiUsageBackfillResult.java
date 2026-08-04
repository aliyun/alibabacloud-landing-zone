package com.aliyun.autowonder.aiusage.dto;

import lombok.Getter;

@Getter
public class DispatchAiUsageBackfillResult {
    private int scanned;
    private int succeeded;
    private int skipped;
    private int failed;

    public void scanned() { scanned++; }
    public void succeeded() { succeeded++; }
    public void skipped() { skipped++; }
    public void failed() { failed++; }
}
