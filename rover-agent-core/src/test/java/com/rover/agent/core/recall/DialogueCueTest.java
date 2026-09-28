package com.rover.agent.core.recall;

import static org.junit.jupiter.api.Assertions.assertEquals;

import org.junit.jupiter.api.Test;

class DialogueCueTest {

    @Test
    void beginningOfThisChatPointsAtThisSession() {
        assertEquals(DialogueCue.Scope.THIS_SESSION,
                DialogueCue.scope("我一开始说的那个问题，你有什么想法吗？"));
    }

    @Test
    void yesterdayQuestionPointsAtYesterday() {
        assertEquals(DialogueCue.Scope.YESTERDAY, DialogueCue.scope("昨天的某一个问题，你怎么看？"));
    }

    @Test
    void namedFaultStaysAnInvestigation() {
        assertEquals(DialogueCue.Scope.NONE, DialogueCue.scope("昨天 /api 为什么慢？"));
        assertEquals(DialogueCue.Scope.NONE, DialogueCue.scope("昨天这个服务有没有问题"));
    }
}
