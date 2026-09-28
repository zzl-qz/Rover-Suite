package com.rover.agent.core.recall;

import java.util.regex.Pattern;

/**
 * 寒暄不应当成「那个问题」。
 *
 * 聊天原文照常留下。只有会话标题、以及「一开始 / 昨天那场」用来点名的那一句，会跳过这类话。
 * 整句必须就是问候或道谢；句子里一旦带上路径，就不再算寒暄。
 */
public final class OpeningFilter {

    private static final int MAX_ASIDE_LENGTH = 16;

    private static final Pattern ASIDE = Pattern.compile(
            "^(?:你好|您好|嗨|哈喽|哈啰|hi|hello|hey|在吗|在么|在不在|谢谢|多谢|感谢|好的|嗯+|哦+|啊+|早|早上好|晚上好|午安|你是谁|你能做什么|你会什么|你能干什么)"
                    + "[啊呀吗呢吧!！。.~～?？\\s]*$",
            Pattern.CASE_INSENSITIVE | Pattern.UNICODE_CASE);

    private OpeningFilter() {
    }

    public static boolean aside(String text) {
        if (text == null) {
            return false;
        }
        String flat = text.replace('\n', ' ').trim();
        if (flat.isEmpty() || flat.length() > MAX_ASIDE_LENGTH || flat.indexOf('/') >= 0) {
            return false;
        }
        return ASIDE.matcher(flat).matches();
    }
}
