package com.ai.konwledgerepo.graph.node;

import com.alibaba.cloud.ai.graph.OverAllState;
import com.alibaba.cloud.ai.graph.action.NodeAction;
import org.springframework.stereotype.Component;

import java.util.Map;

/**
 * 终结占位节点：作为条件边 mapping 的目标（graph-core 校验条件边目标必须是已注册节点），
 * 其后通过 addEdge(TERMINAL, END) 到达图终态。
 */
@Component
public class TerminalNode implements NodeAction {

    @Override
    public Map<String, Object> apply(OverAllState state) throws Exception {
        return Map.of();
    }
}
