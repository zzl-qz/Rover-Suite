package com.rover.agent.runtime.journal;

import com.rover.agent.core.model.AgentMessage;
import com.rover.agent.core.model.MessageRole;
import com.rover.agent.core.model.RecallChoice;
import com.rover.agent.core.model.Session;
import com.rover.agent.core.model.TaskView;
import com.rover.agent.core.recall.DialogueCue;
import com.rover.agent.core.recall.OpeningFilter;
import com.rover.agent.core.repository.AgentMessageRepository;
import com.rover.agent.core.repository.AgentSessionRepository;
import com.rover.agent.core.repository.AgentTaskRepository;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * 按会话目录点名一场旧对话。
 *
 * 给模型的只有第一句问话和那次结论。聊天原文留在库里，不整段装进上下文。
 */
public final class DialogueCards {

    public enum Kind {
        NONE, ANSWER, CHOOSE
    }

    public record Outcome(Kind kind, String text, List<RecallChoice> choices) {

        public static Outcome none() {
            return new Outcome(Kind.NONE, "", List.of());
        }

        public static Outcome answer(String text) {
            return new Outcome(Kind.ANSWER, text, List.of());
        }

        public static Outcome choose(String text, List<RecallChoice> choices) {
            return new Outcome(Kind.CHOOSE, text, List.copyOf(choices));
        }
    }

    private static final int MAX_CHOICES = 5;
    private static final int TITLE_LIMIT = 60;
    private static final DateTimeFormatter WHEN = DateTimeFormatter.ofPattern("M月d日 HH:mm");

    private final AgentSessionRepository sessions;
    private final AgentMessageRepository messages;
    private final AgentTaskRepository tasks;
    private final ZoneId zone;

    public DialogueCards(AgentSessionRepository sessions, AgentMessageRepository messages,
                         AgentTaskRepository tasks) {
        this(sessions, messages, tasks, ZoneId.systemDefault());
    }

    DialogueCards(AgentSessionRepository sessions, AgentMessageRepository messages, AgentTaskRepository tasks,
                  ZoneId zone) {
        this.sessions = sessions;
        this.messages = messages;
        this.tasks = tasks;
        this.zone = zone;
    }

    /**
     * 本场第一句具体问题。寒暄跳过；这句还在最近几条里时返回空，避免出现两次。
     */
    public String opening(String sessionId, List<AgentMessage> recent) {
        AgentMessage first = firstTopic(sessionId);
        if (first == null) {
            return "";
        }
        if (recent != null) {
            for (AgentMessage message : recent) {
                if (message != null && first.messageId().equals(message.messageId())) {
                    return "";
                }
            }
        }
        return "本场第一句：" + clip(first.content(), 500);
    }

    public Outcome recall(Session current, String question, String currentTaskId, String forcedSessionId, long now) {
        if (current == null) {
            return Outcome.none();
        }
        if (forcedSessionId != null && !forcedSessionId.isBlank()) {
            return answerOf(current.userId(), forcedSessionId, currentTaskId);
        }
        return switch (DialogueCue.scope(question)) {
            case NONE -> Outcome.none();
            case THIS_SESSION -> thisSession(current.sessionId(), currentTaskId);
            case YESTERDAY -> yesterday(current, currentTaskId, now);
        };
    }

    private Outcome thisSession(String sessionId, String currentTaskId) {
        List<AgentMessage> users = userMessages(sessionId);
        if (users.size() < 2) {
            return Outcome.none();
        }
        AgentMessage topic = firstTopic(sessionId, currentTaskId);
        if (topic == null) {
            return Outcome.answer("这场开头没有具体问题，只是寒暄。直接说路径或服务名就可以查。");
        }
        return Outcome.answer(card(sessionId, topic, currentTaskId));
    }

    private Outcome yesterday(Session current, String currentTaskId, long now) {
        LocalDate day = Instant.ofEpochMilli(now).atZone(zone).toLocalDate().minusDays(1);
        List<Hit> hits = new ArrayList<>();
        for (Session session : sessions.findByUserId(current.userId())) {
            AgentMessage first = firstOn(session.sessionId(), day);
            if (first == null) {
                continue;
            }
            hits.add(new Hit(session.sessionId(), first));
        }
        hits.sort(Comparator.comparingLong((Hit hit) -> hit.first.createdAtMillis()).reversed());
        if (hits.isEmpty()) {
            return Outcome.answer("昨天没有找到对得上的调查。可以说一下路径或服务名，我按现网再查。");
        }
        if (hits.size() == 1) {
            Hit only = hits.get(0);
            return Outcome.answer(card(only.sessionId, only.first, currentTaskId));
        }
        List<RecallChoice> choices = new ArrayList<>();
        int limit = Math.min(MAX_CHOICES, hits.size());
        for (int i = 0; i < limit; i++) {
            Hit hit = hits.get(i);
            choices.add(new RecallChoice(label(hit.first), hit.sessionId));
        }
        String text = hits.size() > MAX_CHOICES
                ? "昨天有好几场，这里是较近的五场。点一场继续，没有把对话原文放进来。"
                : "昨天有好几场对得上。点一场继续，没有把对话原文放进来。";
        return Outcome.choose(text, choices);
    }

    private Outcome answerOf(String userId, String sessionId, String currentTaskId) {
        Session session = sessions.find(sessionId).orElse(null);
        if (session == null || (userId != null && !userId.equals(session.userId()))) {
            return Outcome.answer("没有找到这场对话。");
        }
        AgentMessage first = firstTopic(sessionId);
        if (first == null) {
            return Outcome.answer("这场对话里没有具体问题，开头只是寒暄。");
        }
        return Outcome.answer(card(sessionId, first, currentTaskId));
    }

    private String card(String sessionId, AgentMessage first, String currentTaskId) {
        StringBuilder text = new StringBuilder();
        text.append("你指的是这一场（").append(label(first)).append("）。");
        String conclusion = conclusion(sessionId, currentTaskId);
        if (conclusion.isBlank()) {
            text.append("这场没有记下调查结论。");
        } else {
            text.append("当时的结论是：").append(clip(conclusion, 500)).append("。");
        }
        text.append("这是那次记下的内容，不是整段对话；当前状态要重新查才算数。");
        return text.toString();
    }

    private String conclusion(String sessionId, String currentTaskId) {
        for (TaskView task : tasks.recentBySession(sessionId, 20)) {
            if (task.taskId().equals(currentTaskId) || task.result() == null || task.result().summary() == null) {
                continue;
            }
            String summary = task.result().summary().trim();
            if (!summary.isBlank()) {
                return summary;
            }
        }
        return "";
    }

    /** 某一天里第一句具体问题。整天都是寒暄的场次不进目录。 */
    private AgentMessage firstOn(String sessionId, LocalDate day) {
        AgentMessage first = null;
        for (AgentMessage message : userMessages(sessionId)) {
            if (OpeningFilter.aside(message.content())) {
                continue;
            }
            LocalDate messageDay = Instant.ofEpochMilli(message.createdAtMillis()).atZone(zone).toLocalDate();
            if (!messageDay.equals(day)) {
                continue;
            }
            if (first == null || message.createdAtMillis() < first.createdAtMillis()) {
                first = message;
            }
        }
        return first;
    }

    private AgentMessage firstTopic(String sessionId) {
        return firstTopic(sessionId, null);
    }

    /** 跳过寒暄，也跳过正在问「一开始那个问题」的这一句。 */
    private AgentMessage firstTopic(String sessionId, String skipTaskId) {
        for (AgentMessage message : userMessages(sessionId)) {
            if (skipTaskId != null && skipTaskId.equals(message.relatedTaskId())) {
                continue;
            }
            if (!OpeningFilter.aside(message.content())) {
                return message;
            }
        }
        return null;
    }

    private List<AgentMessage> userMessages(String sessionId) {
        List<AgentMessage> found = new ArrayList<>();
        for (AgentMessage message : messages.bySession(sessionId)) {
            if (message.role() == MessageRole.USER) {
                found.add(message);
            }
        }
        found.sort(Comparator.comparingLong(AgentMessage::createdAtMillis));
        return found;
    }

    private String label(AgentMessage message) {
        String when = WHEN.format(Instant.ofEpochMilli(message.createdAtMillis()).atZone(zone));
        return when + " · " + clip(message.content(), TITLE_LIMIT);
    }

    private static String clip(String text, int max) {
        if (text == null) {
            return "";
        }
        String flat = text.replace('\n', ' ').trim();
        return flat.length() <= max ? flat : flat.substring(0, max);
    }

    private record Hit(String sessionId, AgentMessage first) {
    }
}
