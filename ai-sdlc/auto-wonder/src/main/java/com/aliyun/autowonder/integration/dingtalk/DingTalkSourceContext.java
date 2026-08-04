package com.aliyun.autowonder.integration.dingtalk;

import com.alibaba.fastjson.JSONArray;
import com.alibaba.fastjson.JSONObject;
import java.util.ArrayList;
import java.util.List;

public class DingTalkSourceContext {

    private String conversationId;
    private String conversationTitle;
    private String conversationType;
    private String senderId;
    private String senderNick;
    private String senderStaffId;
    private String senderCorpId;
    private List<InboundBotMessage.AtUser> atUsers = List.of();
    private String robotCode;
    private String msgId;
    private String msgType;
    private String sessionWebhook;
    private Long sessionWebhookExpiredTime;
    private Long createAt;

    public DingTalkSourceContext() {}

    public static DingTalkSourceContext from(InboundBotMessage msg) {
        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setConversationId(msg.conversationId());
        context.setConversationTitle(msg.conversationTitle());
        context.setConversationType(msg.conversationType());
        context.setSenderId(msg.senderId());
        context.setSenderNick(msg.senderNick());
        context.setSenderStaffId(msg.senderStaffId());
        context.setSenderCorpId(msg.senderCorpId());
        context.setAtUsers(msg.atUsers());
        context.setRobotCode(msg.robotCode());
        context.setMsgId(msg.msgId());
        context.setMsgType(msg.msgType());
        context.setSessionWebhook(msg.sessionWebhook());
        context.setSessionWebhookExpiredTime(msg.sessionWebhookExpiredTime());
        context.setCreateAt(msg.createAt());
        return context;
    }

    public String toJson() {
        JSONObject json = new JSONObject();
        json.put("conversationId", conversationId);
        json.put("conversationTitle", conversationTitle);
        json.put("conversationType", conversationType);
        json.put("senderId", senderId);
        json.put("senderNick", senderNick);
        json.put("senderStaffId", senderStaffId);
        json.put("senderCorpId", senderCorpId);
        JSONArray users = new JSONArray();
        if (atUsers != null) {
            for (InboundBotMessage.AtUser user : atUsers) {
                JSONObject userJson = new JSONObject();
                userJson.put("dingtalkId", user.dingtalkId());
                userJson.put("staffId", user.staffId());
                users.add(userJson);
            }
        }
        json.put("atUsers", users);
        json.put("robotCode", robotCode);
        json.put("msgId", msgId);
        json.put("msgType", msgType);
        json.put("sessionWebhook", sessionWebhook);
        json.put("sessionWebhookExpiredTime", sessionWebhookExpiredTime);
        json.put("createAt", createAt);
        return json.toJSONString();
    }

    public static DingTalkSourceContext parse(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        JSONObject object = JSONObject.parseObject(json);
        DingTalkSourceContext context = new DingTalkSourceContext();
        context.setConversationId(object.getString("conversationId"));
        context.setConversationTitle(object.getString("conversationTitle"));
        context.setConversationType(object.getString("conversationType"));
        context.setSenderId(object.getString("senderId"));
        context.setSenderNick(object.getString("senderNick"));
        context.setSenderStaffId(object.getString("senderStaffId"));
        context.setSenderCorpId(object.getString("senderCorpId"));
        context.setAtUsers(parseAtUsers(object.getJSONArray("atUsers")));
        context.setRobotCode(object.getString("robotCode"));
        context.setMsgId(object.getString("msgId"));
        context.setMsgType(object.getString("msgType"));
        context.setSessionWebhook(object.getString("sessionWebhook"));
        context.setSessionWebhookExpiredTime(object.getLong("sessionWebhookExpiredTime"));
        context.setCreateAt(object.getLong("createAt"));
        return context;
    }

    private static List<InboundBotMessage.AtUser> parseAtUsers(JSONArray array) {
        if (array == null || array.isEmpty()) {
            return List.of();
        }
        List<InboundBotMessage.AtUser> users = new ArrayList<>(array.size());
        for (int i = 0; i < array.size(); i++) {
            JSONObject user = array.getJSONObject(i);
            users.add(new InboundBotMessage.AtUser(
                    user.getString("dingtalkId"),
                    user.getString("staffId")));
        }
        return List.copyOf(users);
    }

    public String getConversationId() {
        return conversationId;
    }

    public void setConversationId(String conversationId) {
        this.conversationId = conversationId;
    }

    public String getConversationTitle() {
        return conversationTitle;
    }

    public void setConversationTitle(String conversationTitle) {
        this.conversationTitle = conversationTitle;
    }

    public String getConversationType() {
        return conversationType;
    }

    public void setConversationType(String conversationType) {
        this.conversationType = conversationType;
    }

    public String getSenderId() {
        return senderId;
    }

    public void setSenderId(String senderId) {
        this.senderId = senderId;
    }

    public String getSenderNick() {
        return senderNick;
    }

    public void setSenderNick(String senderNick) {
        this.senderNick = senderNick;
    }

    public String getSenderStaffId() {
        return senderStaffId;
    }

    public void setSenderStaffId(String senderStaffId) {
        this.senderStaffId = senderStaffId;
    }

    public String getSenderCorpId() {
        return senderCorpId;
    }

    public void setSenderCorpId(String senderCorpId) {
        this.senderCorpId = senderCorpId;
    }

    public List<InboundBotMessage.AtUser> getAtUsers() {
        return atUsers;
    }

    public void setAtUsers(List<InboundBotMessage.AtUser> atUsers) {
        this.atUsers = atUsers == null ? List.of() : List.copyOf(atUsers);
    }

    public String getRobotCode() {
        return robotCode;
    }

    public void setRobotCode(String robotCode) {
        this.robotCode = robotCode;
    }

    public String getMsgId() {
        return msgId;
    }

    public void setMsgId(String msgId) {
        this.msgId = msgId;
    }

    public String getMsgType() {
        return msgType;
    }

    public void setMsgType(String msgType) {
        this.msgType = msgType;
    }

    public String getSessionWebhook() {
        return sessionWebhook;
    }

    public void setSessionWebhook(String sessionWebhook) {
        this.sessionWebhook = sessionWebhook;
    }

    public Long getSessionWebhookExpiredTime() {
        return sessionWebhookExpiredTime;
    }

    public void setSessionWebhookExpiredTime(Long sessionWebhookExpiredTime) {
        this.sessionWebhookExpiredTime = sessionWebhookExpiredTime;
    }

    public Long getCreateAt() {
        return createAt;
    }

    public void setCreateAt(Long createAt) {
        this.createAt = createAt;
    }
}
