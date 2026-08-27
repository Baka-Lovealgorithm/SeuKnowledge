package com.ai.konwledgerepo.common;

/**
 * 用户主动停止生成时抛出的异常，携带已累积的部分答案供落库。
 */
public class GenerationCancelledException extends RuntimeException {

    private final String partial;

    public GenerationCancelledException(String partial) {
        super("用户停止生成");
        this.partial = partial == null ? "" : partial;
    }

    public String getPartial() {
        return partial;
    }
}