package com.fc.v2.hmf;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertTrue;

import java.lang.reflect.Field;
import java.math.BigDecimal;
import java.text.SimpleDateFormat;
import java.util.ArrayList;
import java.util.Date;
import java.util.List;
import java.util.stream.Collectors;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;

import com.fc.v2.model.auto.THmfAcctCard;
import com.fc.v2.model.auto.THmfPayRow;
import com.fc.v2.model.custom.PayBookReceipt;
import com.fc.v2.service.impl.THmfPayRowServiceImpl;

/**
 * 《旧批法整批提交契约》逐条核对（与新批法同一杆秤）：
 * 空批判空回 0 不越界、超上限回 -1 一行不留、同批重发一行不加不翻倍、
 * 逐行处置（一行非法不连累合规行）、失败明细带原行号且按号升序、
 * 非法判定同源（户号空/金额不正/交存日期缺）、旧批已销行与册头行同一本户面账。
 */
class SubmitBatchContractTest {

    // 行去向
    private static final int OK = 1;
    private static final int FAIL = 2;

    private THmfPayRowServiceImpl service;
    private FakePayBookMapper books;
    private FakePayRowMapper rows;
    private FakeAcctCardMapper cards;

    @BeforeEach
    void setUp() throws Exception {
        this.service = new THmfPayRowServiceImpl();
        this.books = new FakePayBookMapper();
        this.rows = new FakePayRowMapper();
        this.cards = new FakeAcctCardMapper();
        inject("hmfPayBookMapper", books);
        inject("hmfPayRowMapper", rows);
        inject("hmfAcctCardMapper", cards);
        // 甲户应缴 14000
        card("FH-JIA", new BigDecimal("14000.00"));
    }

    private void inject(String name, Object value) throws Exception {
        Field f = THmfPayRowServiceImpl.class.getDeclaredField(name);
        f.setAccessible(true);
        f.set(service, value);
    }

    private void card(String acctNo, BigDecimal fineAmt) {
        THmfAcctCard c = new THmfAcctCard();
        c.setBillNo(acctNo);
        c.setSiteNo("JZ00");
        c.setNodeNo(1);
        c.setFineAmt(fineAmt);
        c.setDelFlag(0);
        cards.insert(c);
    }

    private Date day(String text) {
        try {
            return new SimpleDateFormat("yyyy-MM-dd").parse(text);
        } catch (Exception e) {
            throw new RuntimeException(e);
        }
    }

    private THmfPayRow row(String acct, String qty, String payDate) {
        THmfPayRow r = new THmfPayRow();
        r.setItemCode(acct);
        r.setQty(new BigDecimal(qty));
        r.setPayDate(day(payDate));
        return r;
    }

    private List<THmfPayRow> stored(String batchNo, Integer status) {
        return rows.all().stream()
                .filter(r -> batchNo.equals(r.getBatchNo()))
                .filter(r -> status == null || status.equals(r.getStatus()))
                .collect(Collectors.toList());
    }

    // ---- 空批：判空在先，回 0，不越界 ----

    @Test
    void emptyBatch_returnsZero_neverThrows() {
        assertEquals(0, service.submitBatch("B-EMPTY", new ArrayList<>()), "空批回 0，不许抛越界");
        assertEquals(0, service.submitBatch("B-NULL", null), "没行可交回 0");
        assertEquals(0, service.submitBatch("  ", java.util.Collections.singletonList(
                row("FH-JIA", "1.00", "2026-01-05"))), "空册次号回 0");
        assertEquals(0, rows.all().size(), "一行不入");
    }

    // ---- 超限：整批退回，一行不留 ----

    @Test
    void overLimit_returnsMinusOne_zeroRowKept() {
        List<THmfPayRow> big = new ArrayList<>();
        for (int i = 0; i < 501; i++) {
            big.add(row("FH-JIA", "1.00", "2026-01-05"));
        }
        assertEquals(-1, service.submitBatch("B-BIG", big), "几千行照单全收的日子没了：超限回 -1");
        assertEquals(0, rows.all().size(), "整批退回，一行不留");

        List<THmfPayRow> atMost = new ArrayList<>();
        for (int i = 0; i < 500; i++) {
            atMost.add(row("FH-JIA", "1.00", "2026-01-05"));
        }
        assertEquals(500, service.submitBatch("B-500", atMost), "顶着上限照收");
    }

    // ---- 同批重发：一行不加，不翻倍 ----

    @Test
    void resubmitSameBatch_neverDuplicates() {
        List<THmfPayRow> first = new ArrayList<>();
        first.add(row("FH-JIA", "8000.00", "2026-01-05"));
        first.add(row("FH-JIA", "100.00", "2026-01-06"));
        assertEquals(2, service.submitBatch("B-DUP", first));
        int afterFirst = rows.all().size();

        // 同一批重发了一下：回 0，数据不许翻倍
        assertEquals(0, service.submitBatch("B-DUP", first));
        assertEquals(afterFirst, rows.all().size(), "一行不加");
        assertEquals(2, stored("B-DUP", OK).size());

        // 哪怕第二遍内容变了也不加（要改走新批法撤册那套手续）
        assertEquals(0, service.submitBatch("B-DUP", java.util.Collections.singletonList(
                row("FH-JIA", "9999.00", "2026-01-07"))));
        assertEquals(afterFirst, rows.all().size());
    }

    // ---- 逐行处置：一行非法不连累合规行；失败明细带原行号 ----

    @Test
    void partialIllegal_validRowsIn_failedRowsCarryOriginalRowNo() {
        THmfPayRow noAcct = row(null, "100.00", "2026-01-06");
        THmfPayRow badQty = row("FH-JIA", "-5.00", "2026-01-08");
        THmfPayRow noDate = row("FH-JIA", "100.00", "2026-01-10");
        noDate.setPayDate(null);
        List<THmfPayRow> in = new ArrayList<>();
        in.add(row("FH-JIA", "8000.00", "2026-01-05"));   // 第1行：合法
        in.add(noAcct);                                    // 第2行：户号空
        in.add(row("FH-JIA", "6000.00", "2026-01-07"));   // 第3行：合法
        in.add(badQty);                                    // 第4行：金额不正
        in.add(noDate);                                    // 第5行：交存日期缺（与新批法同一杆秤）

        assertEquals(2, service.submitBatch("B-PART", in), "合规的两行照入，回成功行数");

        List<THmfPayRow> ok = stored("B-PART", OK);
        assertEquals(2, ok.size());
        assertEquals(1, ok.get(0).getRowNo().intValue());
        assertEquals(3, ok.get(1).getRowNo().intValue());

        // 失败明细：行号是原册第几行，不是失败行自排的序号；按号升序，缘由写明
        List<THmfPayRow> errors = service.listErrors("B-PART");
        assertEquals(3, errors.size());
        assertEquals(2, errors.get(0).getRowNo().intValue(), "原行号，白找半天的日子没了");
        assertEquals(4, errors.get(1).getRowNo().intValue());
        assertEquals(5, errors.get(2).getRowNo().intValue());
        assertNotNull(errors.get(0).getHoldReason(), "非法缘由就地写明");
        assertNotNull(errors.get(2).getHoldReason());
        // 失败行不进户面（情形 2 与挂起同值，户面只认已销 1）
        assertEquals(FAIL, errors.get(0).getStatus().intValue());
    }

    // ---- 批次号以入参为准，钉在每行上 ----

    @Test
    void batchNoComesFromParameter_stampedOnEveryRow() {
        List<THmfPayRow> in = new ArrayList<>();
        in.add(row("FH-JIA", "8000.00", "2026-01-05"));
        assertEquals(1, service.submitBatch("B-PARAM", in));
        THmfPayRow stored = rows.all().get(0);
        assertEquals("B-PARAM", stored.getBatchNo());
        assertEquals(1, stored.getRowNo().intValue());
        assertEquals(Integer.valueOf(0), stored.getLayer(), "现行册面");
        assertEquals(Integer.valueOf(0), stored.getDelFlag());
        assertEquals("2026Q1", stored.getFromSeason(), "交存日落在哪季算哪季，与新批法同钉");
    }

    // ---- 同一本户面账：旧批已销行，新册核销照计 ----

    @Test
    void oldBatchClearedRows_countTowardHouseholdLedger() {
        // 旧批法收进甲户 8000（已销）；新册同户再交 7000：8000+7000>14000，超额挂起
        assertEquals(1, service.submitBatch("B-OLD", java.util.Collections.singletonList(
                row("FH-JIA", "8000.00", "2026-01-05"))));
        PayBookReceipt rc = service.reconcilePayBook("CE-NEW", java.util.Collections.singletonList(
                row("FH-JIA", "7000.00", "2026-02-05")));
        assertEquals(2, rc.getRows().get(0).getStatus().intValue(), "旧批已销照计户面，累计超应缴即挂起");
        assertTrue(rc.getRows().get(0).getHoldReason().contains("8000"), "户面底数含旧批那笔");

        // 旧批失败行不进户面：同户交 6000，户面底数仍只 8000，合 14000 正好缴清照销
        THmfPayRow noDate = row("FH-JIA", "5000.00", "2026-01-09");
        noDate.setPayDate(null);
        assertEquals(0, service.submitBatch("B-OLD-FAIL", java.util.Collections.singletonList(noDate)));
        PayBookReceipt rc2 = service.reconcilePayBook("CE-NEW2", java.util.Collections.singletonList(
                row("FH-JIA", "6000.00", "2026-02-06")));
        assertEquals(1, rc2.getRows().get(0).getStatus().intValue(), "失败行不算户面，8000+6000=14000 照销");
    }

    // ---- 同批同户两条：都合法照入，户面累加不覆盖 ----

    @Test
    void sameAcctTwiceInOneBatch_bothRecorded_ledgerAccumulates() {
        List<THmfPayRow> in = new ArrayList<>();
        in.add(row("FH-JIA", "8000.00", "2026-01-05"));
        in.add(row("FH-JIA", "6000.00", "2026-01-06"));
        assertEquals(2, service.submitBatch("B-TWICE", in), "同户两条不违规：户面累加不覆盖");
        // 户面已累到 14000：新册同户再交一块也超额挂起——两处点同一本账
        PayBookReceipt rc = service.reconcilePayBook("CE-AFTER", java.util.Collections.singletonList(
                row("FH-JIA", "1.00", "2026-02-05")));
        assertEquals(2, rc.getRows().get(0).getStatus().intValue());
        assertTrue(service.listErrors("B-TWICE").isEmpty(), "同批同码两条不算非法行");
    }
}
