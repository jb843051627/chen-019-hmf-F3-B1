package com.fc.v2.service.impl;

import java.math.BigDecimal;
import java.util.HashMap;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;

import com.baomidou.mybatisplus.core.conditions.query.QueryWrapper;
import com.fc.v2.mapper.auto.THmfPayBookMapper;
import com.fc.v2.mapper.auto.THmfPayRowMapper;
import com.fc.v2.model.auto.THmfPayBook;
import com.fc.v2.model.auto.THmfPayRow;

/**
 * 户面台账口径的唯一出处（核销累计与列支余额两处共用，不许各写一套）。
 *
 * 一行交存进不进户面，只看这一处：已销账(1)、未删、现行册面（旧批法没写过层的行按现行算）；
 * 行挂着册头的，册须在册（已撤册的行随册作废）；没册头的是旧批法收进的行，照计不误。
 * 挂起行、往期层行不进户面。本行到帐金额是算钱的底子，两处点同一本账，账才对得拢。
 *
 * @author fuce
 * @date 2026-10-02
 */
final class HmfHouseholdRules {

    /** 行去向：已销账（只有这一种行进户面） */
    private static final int ROW_CLEARED = 1;
    /** 所在层：现行册面 */
    private static final int LAYER_CURRENT = 0;
    /** 册情形：在册 */
    private static final int BOOK_ACTIVE = 0;

    private HmfHouseholdRules() {
    }

    /**
     * 点各户号户面已收合计：已销、未删、现行层的行，按户号累加不覆盖。
     * 已撤册的行随册作废不算；无册头的旧批法行照计。bookMapper 给 null 时认不到册头情形，
     * 只计无册头的旧批行（与列支侧旧读法一致）。
     */
    static Map<String, BigDecimal> clearedTotalsByAcct(THmfPayRowMapper rowMapper,
                                                       THmfPayBookMapper bookMapper) {
        Set<Long> activeBookIds = new HashSet<>();
        if (bookMapper != null) {
            for (THmfPayBook b : bookMapper.selectList(new QueryWrapper<THmfPayBook>()
                    .eq("status", BOOK_ACTIVE).eq("del_flag", 0))) {
                activeBookIds.add(b.getId());
            }
        }
        Map<String, BigDecimal> totals = new HashMap<>();
        for (THmfPayRow r : rowMapper.selectList(new QueryWrapper<THmfPayRow>()
                .eq("status", ROW_CLEARED).eq("del_flag", 0))) {
            if (!countsTowardLedger(r, activeBookIds)) {
                continue;
            }
            totals.merge(r.getItemCode().trim(), r.getQty(), BigDecimal::add);
        }
        return totals;
    }

    /** 单行进不进户面，只认这一杆秤（含义见类头）。 */
    static boolean countsTowardLedger(THmfPayRow r, Set<Long> activeBookIds) {
        if (r.getStatus() == null || r.getStatus() != ROW_CLEARED) {
            return false;
        }
        if (r.getDelFlag() != null && r.getDelFlag() != 0) {
            return false;
        }
        // 往期层只供翻，不牵户面；旧批法没写过层的行（null）按现行算
        if (r.getLayer() != null && r.getLayer() != LAYER_CURRENT) {
            return false;
        }
        if (r.getItemCode() == null || r.getItemCode().trim().isEmpty() || r.getQty() == null) {
            return false;
        }
        // 有册头的行随册情形走（已撤不算）；无册头的旧批行照计
        return r.getBookId() == null || activeBookIds.contains(r.getBookId());
    }

    /** 在册册头 id 集（撤册的册其行随册作废，不进户面）。 */
    static Set<Long> activeBookIds(THmfPayBookMapper bookMapper) {
        Set<Long> ids = new HashSet<>();
        if (bookMapper != null) {
            List<THmfPayBook> books = bookMapper.selectList(new QueryWrapper<THmfPayBook>()
                    .eq("status", BOOK_ACTIVE).eq("del_flag", 0));
            for (THmfPayBook b : books) {
                ids.add(b.getId());
            }
        }
        return ids;
    }
}
