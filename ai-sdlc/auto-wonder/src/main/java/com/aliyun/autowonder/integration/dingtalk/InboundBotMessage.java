package com.aliyun.autowonder.integration.dingtalk;

import java.util.List;
import java.util.Objects;

public record InboundBotMessage(
        String conversationId,
        String conversationTitle,
        String conversationType,
        String senderId,
        String senderNick,
        String senderStaffId,
        String senderCorpId,
        List<AtUser> atUsers,
        Boolean inAtList,
        String chatbotCorpId,
        String chatbotUserId,
        String robotCode,
        String textContent,
        String msgId,
        String msgType,
        String sessionWebhook,
        Long sessionWebhookExpiredTime,
        Long createAt,
        String rawPayload) {

    public InboundBotMessage {
        atUsers = atUsers == null ? List.of() : List.copyOf(atUsers);
    }

    public InboundBotMessage(String conversationId, String senderId,
            String robotCode, String textContent, String msgId) {
        this(conversationId, null, null, senderId, null, senderId, null, List.of(),
                null, null, null, robotCode, textContent, msgId, "text", null, null, null, null);
    }

    public static final class AtUser {
        private String dingtalkId;
        private String staffId;

        public AtUser() {}

        public AtUser(String dingtalkId, String staffId) {
            this.dingtalkId = dingtalkId;
            this.staffId = staffId;
        }

        public String getDingtalkId() {
            return dingtalkId;
        }

        public void setDingtalkId(String dingtalkId) {
            this.dingtalkId = dingtalkId;
        }

        public String getStaffId() {
            return staffId;
        }

        public void setStaffId(String staffId) {
            this.staffId = staffId;
        }

        public String dingtalkId() {
            return dingtalkId;
        }

        public String staffId() {
            return staffId;
        }

        @Override
        public boolean equals(Object o) {
            if (this == o) {
                return true;
            }
            if (!(o instanceof AtUser atUser)) {
                return false;
            }
            return Objects.equals(dingtalkId, atUser.dingtalkId)
                    && Objects.equals(staffId, atUser.staffId);
        }

        @Override
        public int hashCode() {
            return Objects.hash(dingtalkId, staffId);
        }
    }

    public boolean isTextMessage() {
        return "text".equalsIgnoreCase(msgType);
    }
}
