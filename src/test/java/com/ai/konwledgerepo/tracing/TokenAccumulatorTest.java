package com.ai.konwledgerepo.tracing;

import io.opentelemetry.api.trace.Span;
import io.opentelemetry.api.trace.StatusCode;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.ai.chat.metadata.Usage;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.atomic.AtomicLongArray;

import static org.junit.jupiter.api.Assertions.assertArrayEquals;
import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertNull;
import static org.junit.jupiter.api.Assertions.assertSame;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

/**
 * TokenAccumulator 单元测试：生命周期、单/多类型累加、并发无丢更新、span 写入与清理。
 * <p>
 * 核心回归 {@link #accumulate_concurrentNoLostUpdate}：并行子线程（虚拟线程 per-task）并发累加同一 type，
 * {@link AtomicLongArray#addAndGet} 保证不丢失更新，totals == N × perCall。
 */
class TokenAccumulatorTest {

    private static final ExecutorService EXEC = Executors.newVirtualThreadPerTaskExecutor();

    @AfterEach
    void cleanup() {
        TokenAccumulator.clear();
    }

    @Test
    void begin_setsAccumulator_snapshotReturnsIt() {
        assertNull(TokenAccumulator.snapshot(), "begin 前无累加器");
        TokenAccumulator.begin();
        try {
            Map<String, AtomicLongArray> snap = TokenAccumulator.snapshot();
            assertNotNull(snap, "begin 后有累加器");
            assertTrue(snap.isEmpty(), "新累加器为空");
        } finally {
            TokenAccumulator.clear();
        }
        assertNull(TokenAccumulator.snapshot(), "clear 后无累加器");
    }

    @Test
    void accumulate_singleType_totalsCorrect() {
        TokenAccumulator.begin();
        try {
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(10, 20, 30));
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(5, 7, 12));
            long[] t = TokenAccumulator.totals();
            assertArrayEquals(new long[]{15, 27, 42}, t);
        } finally {
            TokenAccumulator.clear();
        }
    }

    @Test
    void accumulate_multipleTypes_isolatedByType() {
        TokenAccumulator.begin();
        try {
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(10, 20, 30));
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_VISION, usage(1, 2, 3));
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_EMBEDDING, usage(4, 0, 4));
            long[] t = TokenAccumulator.totals();
            assertArrayEquals(new long[]{15, 22, 37}, t);
        } finally {
            TokenAccumulator.clear();
        }
    }

    @Test
    void accumulate_unknownTypeFallsBackToUnknownKey() {
        TokenAccumulator.begin();
        try {
            TokenAccumulator.accumulate(null, usage(1, 1, 2));
            TokenAccumulator.accumulate("  ", usage(2, 2, 4));
            assertArrayEquals(new long[]{3, 3, 6}, TokenAccumulator.totals());
        } finally {
            TokenAccumulator.clear();
        }
    }

    @Test
    void accumulate_noAccumulator_isNoop() {
        // 无 begin 时累加不应抛异常、不影响后续
        TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(10, 20, 30));
        assertNull(TokenAccumulator.snapshot());
        assertArrayEquals(new long[]{0, 0, 0}, TokenAccumulator.totals());
    }

    @Test
    void accumulate_nullUsage_isNoop() {
        TokenAccumulator.begin();
        try {
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, null);
            assertArrayEquals(new long[]{0, 0, 0}, TokenAccumulator.totals());
        } finally {
            TokenAccumulator.clear();
        }
    }

    /**
     * 核心回归：并发累加不丢更新。
     * 模拟真实链路：主线程 begin + snapshot，N 个并行子线程通过 restore 恢复同一 map 后并发 accumulate 同一 type，
     * 主线程 totals 应等于 N × perCall（AtomicLongArray.addAndGet 保证原子）。
     */
    @Test
    void accumulate_concurrentNoLostUpdate() throws Exception {
        int threads = 64;
        long perIn = 3, perOut = 5, perTotal = 8;
        TokenAccumulator.begin();
        try {
            Map<String, AtomicLongArray> snap = TokenAccumulator.snapshot();
            assertNotNull(snap);
            List<CompletableFuture<Void>> futures = new ArrayList<>(threads);
            for (int i = 0; i < threads; i++) {
                futures.add(CompletableFuture.runAsync(() -> {
                    // 子线程恢复主线程快照的同一 map 引用（与 ContextPropagator.wrapSupplier 一致）
                    TokenAccumulator.restore(snap);
                    try {
                        TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(perIn, perOut, perTotal));
                    } finally {
                        TokenAccumulator.clear();
                    }
                }, EXEC));
            }
            CompletableFuture.allOf(futures.toArray(new CompletableFuture[0])).join();
            long[] t = TokenAccumulator.totals();
            assertArrayEquals(new long[]{threads * perIn, threads * perOut, threads * perTotal}, t,
                    "并发累加不应丢失更新");
            // 并行写入的是同一 type，map 中只有一条记录
            assertEquals(1, snap.size(), "同 type 应合并为单条记录");
        } finally {
            TokenAccumulator.clear();
        }
    }

    @Test
    void restore_nullMap_removesAccumulator() {
        TokenAccumulator.begin();
        assertNotNull(TokenAccumulator.snapshot());
        TokenAccumulator.restore(null);
        assertNull(TokenAccumulator.snapshot(), "restore(null) 应移除累加器");
    }

    @Test
    void flushToSpan_writesAttributesAndClears() {
        Span span = mock(Span.class);
        TokenAccumulator.begin();
        try {
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(10, 20, 30));
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_VISION, usage(1, 2, 3));
            TokenAccumulator.flushToSpan(span);
            // 各类型明细（按类型名键，顺序无关）
            verify(span).setAttribute("qa.llm.tokens.text.input", 10L);
            verify(span).setAttribute("qa.llm.tokens.text.output", 20L);
            verify(span).setAttribute("qa.llm.tokens.text.total", 30L);
            verify(span).setAttribute("qa.llm.tokens.vision.input", 1L);
            verify(span).setAttribute("qa.llm.tokens.vision.output", 2L);
            verify(span).setAttribute("qa.llm.tokens.vision.total", 3L);
            // 汇总
            verify(span).setAttribute("qa.llm.input_tokens", 11L);
            verify(span).setAttribute("qa.llm.output_tokens", 22L);
            verify(span).setAttribute("qa.llm.total_tokens", 33L);
            // qa.llm.types 的拼接顺序随 ConcurrentHashMap 迭代序（非确定），只校验两类都出现
            org.mockito.ArgumentCaptor<String> typesCaptor = org.mockito.ArgumentCaptor.forClass(String.class);
            verify(span).setAttribute(eq("qa.llm.types"), typesCaptor.capture());
            String types = typesCaptor.getValue();
            assertTrue(types.contains("text") && types.contains("vision"),
                    "qa.llm.types 应包含 text 与 vision（实际=" + types + "）");
            // flush 后清理
            assertNull(TokenAccumulator.snapshot(), "flushToSpan 后应清理累加器");
        } finally {
            TokenAccumulator.clear();
        }
    }

    @Test
    void flushToSpan_nullSpan_skipsButStillClears() {
        TokenAccumulator.begin();
        try {
            TokenAccumulator.accumulate(TokenAccumulator.TYPE_TEXT, usage(1, 1, 2));
            TokenAccumulator.flushToSpan(null);
            assertNull(TokenAccumulator.snapshot(), "null span 时仍应清理累加器");
        } finally {
            TokenAccumulator.clear();
        }
    }

    @Test
    void flushToSpan_noAccumulator_isNoop() {
        Span span = mock(Span.class);
        TokenAccumulator.flushToSpan(span);
        verify(span, never()).setAttribute(org.mockito.ArgumentMatchers.anyString(),
                org.mockito.ArgumentMatchers.anyLong());
    }

    private static Usage usage(long in, long out, long total) {
        return new Usage() {
            @Override
            public Integer getPromptTokens() {
                return (int) in;
            }

            @Override
            public Integer getCompletionTokens() {
                return (int) out;
            }

            @Override
            public Integer getTotalTokens() {
                return (int) total;
            }

            @Override
            public Object getNativeUsage() {
                return null;
            }
        };
    }
}
