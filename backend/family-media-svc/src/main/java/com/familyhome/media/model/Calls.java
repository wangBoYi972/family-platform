package com.familyhome.media.model;

/** 通话状态机与取值的唯一事实来源，避免各层散落字符串字面量。 */
public final class Calls {
    private Calls() {}

    public static final String MODE_DIRECT = "DIRECT";
    public static final String MODE_GROUP = "GROUP";

    public static final String KIND_AUDIO = "AUDIO";
    public static final String KIND_VIDEO = "VIDEO";

    public static final String STATUS_RINGING = "RINGING";
    public static final String STATUS_ACTIVE = "ACTIVE";
    public static final String STATUS_ENDED = "ENDED";

    public static final String STATE_INVITED = "INVITED";
    public static final String STATE_JOINED = "JOINED";
    public static final String STATE_LEFT = "LEFT";
    public static final String STATE_REJECTED = "REJECTED";
    public static final String STATE_MISSED = "MISSED";

    public static final String REASON_REJECTED = "REJECTED";
    public static final String REASON_NO_ANSWER = "NO_ANSWER";
    public static final String REASON_HANGUP = "HANGUP";
    public static final String REASON_LEFT = "LEFT";
    public static final String REASON_ERROR = "ERROR";

    /** 录音提交后待转写。 */
    public static final String RECORDING_UPLOADED = "UPLOADED";
    /** 正在逐段转写并生成纪要。 */
    public static final String RECORDING_TRANSCRIBING = "TRANSCRIBING";
    /** 纪要可读。 */
    public static final String RECORDING_READY = "READY";
    /** 转写或生成失败，可重试。 */
    public static final String RECORDING_FAILED = "FAILED";
}
