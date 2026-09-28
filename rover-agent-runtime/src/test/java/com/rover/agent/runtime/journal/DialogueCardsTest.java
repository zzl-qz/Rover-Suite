package com.rover.agent.runtime.journal;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertTrue;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.Confidence;
import com.rover.agent.core.model.InvestigationReport;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.ResourceTarget;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.SessionStatus;
import com.rover.agent.core.model.TaskStatus;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.runtime.repository.InMemoryAgentMessageRepository;
import com.rover.agent.runtime.repository.InMemoryAgentSessionRepository;
import com.rover.agent.runtime.repository.InMemoryAgentTaskRepository;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import org.junit.jupiter.api.Test;

class DialogueCardsTest {

    private static final ZoneId ZONE = ZoneId.of("Asia/Shanghai");

    @Test
    void yesterdayWithTwoChatsListsTitlesOnly() {
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(20);
        InMemoryAgentMessageRepository messages = new InMemoryAgentMessageRepository(50);
        InMemoryAgentTaskRepository tasks = new InMemoryAgentTaskRepository(20);
        long yesterday = LocalDate.now(ZONE).minusDays(1).atTime(16, 40).atZone(ZONE).toInstant().toEpochMilli();
        save(sessions, messages, tasks, "s-route", "为什么 /not-a-route 调用失败？", "该路径未命中任何路由", yesterday);
        save(sessions, messages, tasks, "s-slow", "demo-service 是不是有点慢", "窗口内没有足够样本", yesterday + 60_000);
        Session current = session("s-now", yesterday + 86_400_000L);
        sessions.save(current);

        DialogueCards.Outcome outcome = new DialogueCards(sessions, messages, tasks, ZONE)
                .recall(current, "昨天的某一个问题，你怎么看？", "t-now", null, yesterday + 86_400_000L);

        assertEquals(DialogueCards.Kind.CHOOSE, outcome.kind());
        assertEquals(2, outcome.choices().size());
        assertEquals("s-slow", outcome.choices().get(0).value());
        assertTrue(outcome.choices().get(0).label().contains("demo-service"));
        assertFalse(outcome.text().contains("未命中任何路由"));
        assertFalse(outcome.text().contains("足够样本"));
    }

    @Test
    void oneYesterdayChatReturnsThatConclusionOnly() {
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(20);
        InMemoryAgentMessageRepository messages = new InMemoryAgentMessageRepository(50);
        InMemoryAgentTaskRepository tasks = new InMemoryAgentTaskRepository(20);
        long yesterday = LocalDate.now(ZONE).minusDays(1).atTime(9, 5).atZone(ZONE).toInstant().toEpochMilli();
        save(sessions, messages, tasks, "s-route", "为什么 /not-a-route 调用失败？", "该路径未命中任何路由", yesterday);
        Session current = session("s-now", yesterday + 86_400_000L);
        sessions.save(current);

        DialogueCards.Outcome outcome = new DialogueCards(sessions, messages, tasks, ZONE)
                .recall(current, "昨天问的那个问题你怎么看", "t-now", null, yesterday + 86_400_000L);

        assertEquals(DialogueCards.Kind.ANSWER, outcome.kind());
        assertTrue(outcome.text().contains("该路径未命中任何路由"));
        assertTrue(outcome.choices().isEmpty());
    }

    @Test
    void greetingIsNotTheProblemBeingRecalled() {
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(20);
        InMemoryAgentMessageRepository messages = new InMemoryAgentMessageRepository(50);
        InMemoryAgentTaskRepository tasks = new InMemoryAgentTaskRepository(20);
        long yesterday = LocalDate.now(ZONE).minusDays(1).atTime(11, 0).atZone(ZONE).toInstant().toEpochMilli();
        Session chat = session("s-chat", yesterday);
        sessions.save(chat);
        messages.save(new AgentMessage("m-hi", "s-chat", MessageRole.USER, "你好啊", yesterday, "t-hi"));
        messages.save(new AgentMessage("m-ask", "s-chat", MessageRole.USER, "为什么 /not-a-route 调用失败？",
                yesterday + 60_000, "t-ask"));
        tasks.save(new TaskView("t-ask", "s-chat", null, TaskStatus.COMPLETED, null, "",
                ResourceTarget.unknown(), "为什么 /not-a-route 调用失败？", yesterday + 60_000, yesterday + 60_000,
                List.of(), new InvestigationReport("该路径未命中任何路由", Confidence.MEDIUM, List.of(), List.of(), List.of(), null),
                null, null));
        messages.save(new AgentMessage("m-only-hi", "s-hi", MessageRole.USER, "你好", yesterday + 120_000, "t-only-hi"));
        sessions.save(session("s-hi", yesterday + 120_000));

        DialogueCards cards = new DialogueCards(sessions, messages, tasks, ZONE);
        DialogueCards.Outcome yesterdayOutcome = cards.recall(session("s-now", yesterday + 86_400_000L),
                "昨天的某一个问题，你怎么看？", "t-now", null, yesterday + 86_400_000L);
        assertEquals(DialogueCards.Kind.ANSWER, yesterdayOutcome.kind());
        assertTrue(yesterdayOutcome.text().contains("/not-a-route"));
        assertFalse(yesterdayOutcome.text().contains("你好"));

        DialogueCards.Outcome here = cards.recall(chat, "我一开始说的那个问题，你有什么想法？", "t-now", null, yesterday + 180_000);
        assertTrue(here.text().contains("/not-a-route"));
        assertFalse(here.text().startsWith("你指的是这一场（") && here.text().contains("你好啊"));
    }

    @Test
    void openingIsOmittedWhenTheFirstLineIsStillInView() {
        InMemoryAgentSessionRepository sessions = new InMemoryAgentSessionRepository(20);
        InMemoryAgentMessageRepository messages = new InMemoryAgentMessageRepository(50);
        AgentMessage first = new AgentMessage("m1", "s1", MessageRole.USER, "第一句", 1L, "t1");
        messages.save(first);
        DialogueCards cards = new DialogueCards(sessions, messages, new InMemoryAgentTaskRepository(10), ZONE);

        assertEquals("", cards.opening("s1", List.of(first)));
        assertEquals("本场第一句：第一句", cards.opening("s1", List.of()));
    }

    private static void save(InMemoryAgentSessionRepository sessions, InMemoryAgentMessageRepository messages,
                             InMemoryAgentTaskRepository tasks, String sessionId, String asked, String conclusion,
                             long at) {
        sessions.save(session(sessionId, at));
        messages.save(new AgentMessage("m-" + sessionId, sessionId, MessageRole.USER, asked, at, "t-" + sessionId));
        tasks.save(new TaskView("t-" + sessionId, sessionId, null, TaskStatus.COMPLETED, null, "",
                ResourceTarget.unknown(), asked, at, at, List.of(),
                new InvestigationReport(conclusion, Confidence.MEDIUM, List.of(), List.of(), List.of(), null),
                null, null));
    }

    private static Session session(String sessionId, long at) {
        return new Session(sessionId, "admin", "标题", null, SessionStatus.ACTIVE, at, at, List.of());
    }
}
