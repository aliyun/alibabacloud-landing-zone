package com.aliyun.autowonder.debuglog;

import java.util.regex.Pattern;

/**
 * {@code debug_log} 的 DB 列宽消毒集群（S11 自审建议，从 {@link DebugLogService} 纯移动抽出）：
 * 入库前按列宽收敛脏值的纯函数——不含日志、不含策略判断，方法语义与 reason token 全部未变。
 *
 * <p>带日志与白名单策略的 {@code sanitizeChannel}（含 {@code ALLOWED_UPLOAD_CHANNELS}）刻意留在
 * {@link DebugLogService}：那里需要 dispatchId 上下文与 warn 决策。日志行防注入的
 * {@code loggedChannel}（含 {@code MAX_LOGGED_CHANNEL_CHARS}）也已回移 {@link DebugLogService}
 * （S12 评审 advisory：那是「日志行防注入」不是「列宽消毒」），只复用本类的
 * {@link #truncateAndStripControl} 截断原语——本类范围还原为纯列宽消毒。
 *
 * <p>存在理由：脏值会让整条 update/insert 抛 DataIntegrityViolation，把本已收敛的 status 一起丢——
 * best-effort 辅助路径不得因脏列值拖垮主收尾。S9/S10 评审确立的消毒向量
 * （{@code DebugLogServiceResultReportTest#sha256Vectors} 等）经服务方法间接钉住本类行为。
 */
final class DebugLogColumnSanitizer {

    /** error_message 列宽 VARCHAR(1024)，MySQL 按 code point 计数；截断必须 code-point 安全（S9）。 */
    private static final int MAX_ERROR_MESSAGE_CHARS = 1024;

    /**
     * sha256 列宽 VARCHAR(80)（V049：存裸 64 位 hex，也可能吸收 {@code sha256:} 前缀形态，
     * 先例 dispatch_recovery_checkpoint.sha256）。脏值会让整条 update/insert 抛
     * DataIntegrityViolation 连 status 收敛一起丢，故入库前走 {@link #sanitizeSha256}。
     */
    private static final int MAX_SHA256_CHARS = 80;

    /**
     * 裸 sha256 hex 形态（协议契约：64 位十六进制，大小写均可，透传不做归一）。S11 评审 M7：
     * 与签发端点 {@link DebugLogUploadController} 的 400 校验共用本常量（原先两处各持一份
     * 语义等价的正则——{@code matches()} 下锚定与否同义，现显式保持 {@code ^...$} 锚定一致）。
     */
    static final Pattern SHA256_HEX = Pattern.compile("^[0-9a-fA-F]{64}$");

    /** 上游可能携带的前缀形态，剥掉后是裸 hex 才接受。 */
    private static final String SHA256_PREFIX = "sha256:";

    private DebugLogColumnSanitizer() {
    }

    /**
     * sha256 入库前消毒（S10 Important#1），两个消费点共用：TASK_RESULT 报告
     * （{@link DebugLogService#recordTaskResultReport}）与中转 filesMetadata
     * （{@link DebugLogService#recordRelayUpload}）。
     * 规则：null/空白 → null（列可空）；裸 64 位 hex → 原样（大小写不归一，与签发端点
     * {@link #SHA256_HEX} 校验后的透传行为一致）；带 {@code sha256:} 前缀
     * 且剥掉后是裸 hex → 剥前缀；其他一律 {@link #truncateAndStripControl} 到
     * {@value #MAX_SHA256_CHARS}（{@code DebugLogService#loggedChannel} 同款手法）。
     *
     * <p>必须在写库前收敛：sha256 是 VARCHAR(80)，脏值会让整条 updateOnResult/insert 抛
     * DataIntegrityViolation，把本已收敛的 status 一起丢——best-effort 辅助路径不得因脏 sha256
     * 拖垮主收尾。与 {@code DebugLogService#sanitizeChannel} 的「丢弃 + warn」刻意不同：此处降级
     * 保存且不告警，因为 sha256 只用于校验与排障，残值仍比 null 有信息量，消毒后的值本身就是痕迹，
     * 且不影响 status 收敛。签发路径不经此 helper——端点已用同一 {@link #SHA256_HEX} 正则把非法值挡成 400。
     */
    static String sanitizeSha256(String sha256) {
        if (sha256 == null || sha256.isBlank()) {
            return null;
        }
        if (SHA256_HEX.matcher(sha256).matches()) {
            return sha256;
        }
        if (sha256.startsWith(SHA256_PREFIX)) {
            String bare = sha256.substring(SHA256_PREFIX.length());
            if (SHA256_HEX.matcher(bare).matches()) {
                return bare;
            }
        }
        return truncateAndStripControl(sha256, MAX_SHA256_CHARS);
    }

    /**
     * 按 code point 截断到 {@code maxCodePoints}，并把 ISO 控制字符（\n\r\t 等）逐个替换为 '-'：
     * 绝不切断代理对产生悬空 high surrogate（{@code offsetByCodePoints} 同款思路见
     * {@link #truncateErrorMessage}；替换手法参照 {@link DebugLogObjectNamer#sanitizeRoleCode}
     * 对非法字符的处理先例）。包内可见：除本类的列宽消毒外，还供
     * {@code DebugLogService#loggedChannel}（日志行防注入，S12 评审 advisory 回移）复用。
     */
    static String truncateAndStripControl(String value, int maxCodePoints) {
        StringBuilder sb = new StringBuilder(Math.min(value.length(), maxCodePoints));
        int kept = 0;
        for (int i = 0; i < value.length() && kept < maxCodePoints; ) {
            int codePoint = value.codePointAt(i);
            i += Character.charCount(codePoint);
            kept++;
            sb.appendCodePoint(Character.isISOControl(codePoint) ? '-' : codePoint);
        }
        return sb.toString();
    }

    /**
     * error 截断到 {@value #MAX_ERROR_MESSAGE_CHARS}，code-point 安全（协调者决策 S9-3：
     * 不用裸 substring——那会切断代理对产生悬空 high surrogate）。MySQL VARCHAR(1024) 按
     * code point 计数，astral 字符算 1：仅当 code point 数超限才截，UTF-16 长度超限不截。
     */
    static String truncateErrorMessage(String error) {
        if (error == null || error.codePointCount(0, error.length()) <= MAX_ERROR_MESSAGE_CHARS) {
            return error;
        }
        return error.substring(0, error.offsetByCodePoints(0, MAX_ERROR_MESSAGE_CHARS));
    }
}
