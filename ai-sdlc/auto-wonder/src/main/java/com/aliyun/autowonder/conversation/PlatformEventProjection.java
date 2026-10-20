package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.json.JSON;
import com.aliyun.autowonder.util.RuntimeActionSanitizer;
import java.util.*;

/** Public execution details only. Original provider diagnostics remain in storage. */
public final class PlatformEventProjection {
    private PlatformEventProjection() {}
    public static String payload(String type,String json) {
        if ("thinking".equals(type) || "thinking_delta".equals(type))
            return JSON.toJSONString(Map.of("type","status","status","PROCESSING"));
        if (json==null) return null;
        if ("text".equals(type) || "text_delta".equals(type)) return json;
        try { return JSON.toJSONString(clean(JSON.parse(json),0)); }
        catch(RuntimeException ignored) {return JSON.toJSONString(Map.of("type",type,"content","[REDACTED]"));}
    }
    private static Object clean(Object value,int depth) {
        if(depth>20) return "[REDACTED]";
        if(value instanceof Map<?,?> map) {
            Map<String,Object> out=new LinkedHashMap<>();
            map.forEach((key,item)->{String field=String.valueOf(key);
                if (Set.of("type","tool","callId","toolCallId","requestId","status","kind","priority").contains(field)
                        && item instanceof String identifier && identifier.matches("[A-Za-z0-9_.:/-]{1,256}")) {
                    out.put(field,identifier); return;
                }
                out.put(field,field.toLowerCase(Locale.ROOT).matches(".*(token|password|secret|authorization|credential|api.?key|signature).*")
                        ? "[REDACTED]" : clean(item,depth+1));}); return out;
        }
        if(value instanceof List<?> list) return list.stream().map(item->clean(item,depth+1)).toList();
        if(value instanceof String text) {
            if(text.startsWith("{")||text.startsWith("[")) {
                try{return JSON.toJSONString(clean(JSON.parse(text),depth+1));}catch(RuntimeException ignored){}
            }
            return RuntimeActionSanitizer.sanitize(text,12000);
        }
        return value;
    }
    public static List<AgentConversationTurnEventDO> events(List<AgentConversationTurnEventDO> rows) {
        Map<String,List<AgentConversationTurnEventDO>> groups=new LinkedHashMap<>();
        for(var row:rows) groups.computeIfAbsent(row.getTurnId()+":"+row.getDispatchAttempt()+":"+row.getEventSeq(),k->new ArrayList<>()).add(row);
        List<AgentConversationTurnEventDO> result=new ArrayList<>();
        for(var chunks:groups.values()) {
            chunks.sort(Comparator.comparing(AgentConversationTurnEventDO::getChunkIndex));
            var head=chunks.get(0);
            if(chunks.size()<head.getChunkCount()) continue;
            String full=chunks.stream().map(AgentConversationTurnEventDO::getPayloadFragment).reduce("",String::concat);
            var projected=new AgentConversationTurnEventDO();
            projected.setId(chunks.stream().map(AgentConversationTurnEventDO::getId).max(Long::compare).orElse(head.getId()));
            projected.setConversationId(head.getConversationId()); projected.setTurnId(head.getTurnId());
            projected.setDispatchAttempt(head.getDispatchAttempt()); projected.setEventSeq(head.getEventSeq());
            projected.setChunkCount(1);projected.setChunkIndex(0);projected.setGmtCreate(head.getGmtCreate());
            projected.setEventType("thinking".equals(head.getEventType()) || "thinking_delta".equals(head.getEventType())?"status":head.getEventType());
            projected.setPayloadFragment(payload(head.getEventType(),full));result.add(projected);
        }
        result.sort(Comparator.comparing(AgentConversationTurnEventDO::getId));return result;
    }
}
