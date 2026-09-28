package com.rover.agent.runtime.journal;

import com.rover.agent.core.journal.ResourceNote;
import com.rover.agent.core.journal.ResourceNotes;
import com.rover.agent.core.model.TaskView;
import java.util.regex.Matcher;
import java.util.regex.Pattern;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

/**
 * 运维记忆：按资源读一条已确认笔记，调查结束且有确认根因时覆盖写入。
 *
 * 不记用户偏好。笔记失败不影响已经给出的回答。
 */
public final class OpsJournal {

    private static final Logger log = LoggerFactory.getLogger(OpsJournal.class);
    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9._~/-]+");

    private final H2InvestigationLog logStore;

    private OpsJournal(H2InvestigationLog logStore) {
        this.logStore = logStore;
    }

    public static OpsJournal none() {
        return new OpsJournal(null);
    }

    public static OpsJournal of(H2InvestigationLog logStore) {
        return new OpsJournal(logStore);
    }

    /** 当前对象上次已确认的结论。对象没解析出来时，再按问题里出现的路径去对笔记。 */
    public String recall(String resourceKey, String question) {
        if (logStore == null) {
            return "";
        }
        try {
            String direct = read(resourceKey);
            if (!direct.isBlank()) {
                return direct;
            }
            if (question == null) {
                return "";
            }
            Matcher matcher = PATH.matcher(question);
            while (matcher.find()) {
                String hit = read(matcher.group());
                if (!hit.isBlank()) {
                    return hit;
                }
            }
            return "";
        } catch (RuntimeException ex) {
            log.warn("读取资源笔记失败", ex);
            return "";
        }
    }

    private String read(String resourceKey) {
        if (resourceKey == null || resourceKey.isBlank()) {
            return "";
        }
        return logStore.findNote(resourceKey).map(ResourceNotes::prompt).orElse("");
    }

    /** 有已确认根因才更新该资源的笔记。调查快照由任务仓储自己保存。 */
    public void record(TaskView task) {
        if (logStore == null) {
            return;
        }
        try {
            ResourceNote note = ResourceNotes.capture(task, System.currentTimeMillis());
            if (note != null) {
                logStore.saveNote(note);
            }
        } catch (RuntimeException ex) {
            log.warn("写入资源笔记失败", ex);
        }
    }
}
