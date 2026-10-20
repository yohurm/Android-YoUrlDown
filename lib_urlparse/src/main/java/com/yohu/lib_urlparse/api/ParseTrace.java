package com.yohu.lib_urlparse.api;

import java.util.ArrayList;
import java.util.Collections;
import java.util.List;

/**
 * 解析尝试链：**结构化证据**。
 *
 * <p>用途有两个，都很实际：
 * <ol>
 *   <li>排查"为什么这个链接解析失败"时，不必翻日志才知道试过哪些通道、各自什么原因；</li>
 *   <li>产出可对外展示的成功/失败证据（"哪个通道成的、耗时多少"）。</li>
 * </ol>
 *
 * <p>隐私边界：{@link Attempt#detail()} 只放可公开的信息（状态码、长度、命中的规则名），
 * 不得放响应正文或凭据。
 */
public final class ParseTrace {

    /** 单次尝试的结局。 */
    public enum Verdict {
        /** 成功产出结果。 */
        OK,
        /** 尝试了但失败。 */
        FAILED,
        /** 认领了但被跳过（例如缺少注入服务）。 */
        SKIPPED
    }

    /** 一次通道尝试。 */
    public static final class Attempt {
        private final String channel;
        private final Verdict verdict;
        private final FailureReason reason;
        private final long elapsedMs;
        private final String detail;

        Attempt(String channel, Verdict verdict, FailureReason reason, long elapsedMs, String detail) {
            this.channel = channel;
            this.verdict = verdict;
            this.reason = reason;
            this.elapsedMs = elapsedMs;
            this.detail = detail;
        }

        public String channel() {
            return channel;
        }

        public Verdict verdict() {
            return verdict;
        }

        /** 失败原因；{@link Verdict#OK} 时为 {@code null}。 */
        public FailureReason reason() {
            return reason;
        }

        public long elapsedMs() {
            return elapsedMs;
        }

        /** 一句话细节（可为空）；不得含响应正文。 */
        public String detail() {
            return detail;
        }

        @Override
        public String toString() {
            return channel + ' ' + verdict + (reason == null ? "" : "(" + reason + ")")
                    + ' ' + elapsedMs + "ms";
        }
    }

    private final String resolvedUrl;
    private final List<Attempt> attempts;

    private ParseTrace(Builder b) {
        this.resolvedUrl = b.resolvedUrl;
        this.attempts = Collections.unmodifiableList(new ArrayList<>(b.attempts));
    }

    public static Builder builder() {
        return new Builder();
    }

    /** 短链展开后的最终 URL；未展开时为 {@code null}。 */
    public String resolvedUrl() {
        return resolvedUrl;
    }

    /** 按发生顺序记录的尝试。 */
    public List<Attempt> attempts() {
        return attempts;
    }

    /** 便于日志阅读的紧凑串。 */
    public String summarize() {
        if (attempts.isEmpty()) {
            return "(no attempt)";
        }
        StringBuilder sb = new StringBuilder();
        for (int i = 0; i < attempts.size(); i++) {
            if (i > 0) {
                sb.append(" -> ");
            }
            sb.append(attempts.get(i).channel);
            Attempt a = attempts.get(i);
            if (a.verdict() != Verdict.OK) {
                sb.append('[').append(a.reason == null ? a.verdict : a.reason).append(']');
            }
        }
        return sb.toString();
    }

    /** 构造器。 */
    public static final class Builder {
        private String resolvedUrl;
        private final List<Attempt> attempts = new ArrayList<>();

        public Builder resolvedUrl(String v) {
            this.resolvedUrl = v;
            return this;
        }

        public Builder attempt(String channel, Verdict verdict, FailureReason reason,
                              long elapsedMs, String detail) {
            this.attempts.add(new Attempt(channel, verdict, reason, elapsedMs, detail));
            return this;
        }

        public ParseTrace build() {
            return new ParseTrace(this);
        }
    }
}
