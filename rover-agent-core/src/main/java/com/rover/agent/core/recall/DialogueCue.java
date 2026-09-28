package com.rover.agent.core.recall;

import java.util.List;
import java.util.regex.Pattern;

/**
 * 判断这句话是在指以前的对话，还是在报一个新故障。
 *
 * 只认一小撮词。句子里既有具体路径、又在说慢或失败，就当成新故障，不翻昨天的聊天。
 */
public final class DialogueCue {

    public enum Scope {
        /** 按新问题调查，不点名旧对话。 */
        NONE,
        /** 本场更早的那句问话。 */
        THIS_SESSION,
        /** 昨天的某一场。 */
        YESTERDAY
    }

    private static final Pattern PATH = Pattern.compile("/[A-Za-z0-9][A-Za-z0-9._~/-]*");

    private static final List<String> THIS_SESSION = List.of("一开始", "刚才说", "刚才那个", "这个话题", "我刚才");
    private static final List<String> YESTERDAY = List.of("昨天", "上次", "前几天", "之前那场");
    private static final List<String> POINTER = List.of(
            "那个问题", "某一个问题", "哪个问题", "哪一场", "还记得", "有什么想法", "你怎么看", "怎么看", "说的");
    private static final List<String> SYMPTOM = List.of("慢", "失败", "超时", "报错", "挂了", "异常", "错误");

    private DialogueCue() {
    }

    public static Scope scope(String question) {
        String text = question == null ? "" : question.trim();
        if (text.isBlank()) {
            return Scope.NONE;
        }
        if (containsAny(text, THIS_SESSION)) {
            return Scope.THIS_SESSION;
        }
        if (!containsAny(text, YESTERDAY) || !containsAny(text, POINTER)) {
            return Scope.NONE;
        }
        boolean namedFault = PATH.matcher(text).find() && containsAny(text, SYMPTOM);
        boolean askingAboutTheChat = containsAny(text, List.of("那个问题", "还记得", "有什么想法", "你怎么看", "怎么看", "说的"));
        if (namedFault && !askingAboutTheChat) {
            return Scope.NONE;
        }
        return Scope.YESTERDAY;
    }

    private static boolean containsAny(String text, List<String> words) {
        for (String word : words) {
            if (text.contains(word)) {
                return true;
            }
        }
        return false;
    }
}
