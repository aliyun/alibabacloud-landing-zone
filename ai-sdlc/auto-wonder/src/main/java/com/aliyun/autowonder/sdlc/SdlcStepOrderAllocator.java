package com.aliyun.autowonder.sdlc;

import com.aliyun.autowonder.common.error.BizException;
import com.aliyun.autowonder.common.error.ErrorCode;
import java.util.ArrayList;
import java.util.List;

/** Allocates negative INT slots while the caller holds the live parent row lock. */
final class SdlcStepOrderAllocator {
    private static final int PAGE_SIZE = 256;
    private final SdlcStepDao dao;

    SdlcStepOrderAllocator(SdlcStepDao dao) { this.dao = dao; }

    List<Integer> allocate(long sdlcId, long tenantId, int count) {
        return allocate(sdlcId, tenantId, count, Integer.MIN_VALUE);
    }

    // A smaller internal domain makes exact exhaustion testable without billions of rows.
    List<Integer> allocate(long sdlcId, long tenantId, int count, int minimum) {
        List<Integer> slots = new ArrayList<>(count);
        if (count == 0) return slots;
        Integer occupiedMinimum = dao.minNegativeOrderForUpdate(sdlcId, tenantId);
        long floor = occupiedMinimum == null ? 0L : occupiedMinimum.longValue();
        if (floor - (long) count >= minimum) {
            for (int i = 1; i <= count; i++) slots.add((int) (floor - i));
            return slots;
        }
        // Exceptional gap search: bounded memory, but dense histories can require O(history) work.
        long cursor = 0L;
        long candidate = -1L;
        while (candidate >= minimum) {
            List<Integer> page = dao.negativeOrdersForUpdate(sdlcId, tenantId, cursor, PAGE_SIZE);
            for (int occupied : page) {
                long lower = Math.max((long) minimum, (long) occupied + 1L);
                while (candidate >= lower && slots.size() < count) slots.add((int) candidate--);
                if (slots.size() == count) return slots;
                candidate = (long) occupied - 1L;
                if (candidate < minimum) break;
            }
            if (page.isEmpty()) {
                while (candidate >= minimum && slots.size() < count) slots.add((int) candidate--);
                break;
            }
            cursor = page.get(page.size() - 1).longValue();
        }
        if (slots.size() < count) throw new BizException(ErrorCode.SDLC_STEP_ORDER_EXHAUSTED);
        return slots;
    }
}
