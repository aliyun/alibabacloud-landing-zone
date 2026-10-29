package com.aliyun.autowonder.conversation;

import com.aliyun.autowonder.conversation.dto.*;
import org.junit.jupiter.api.Test;
import static org.junit.jupiter.api.Assertions.*;

class ConversationQuestionHistoryTest {
    @Test void bothDetailContractsExposeOptionalSettledQuestionHistory() {
        assertDoesNotThrow(() -> PlatformConversationVO.class.getDeclaredField("questionHistory"));
        assertDoesNotThrow(() -> ClarificationConversationVO.class.getDeclaredField("questionHistory"));
    }
    @Test void settledReadIsBoundedAndDoesNotChangeReliableAnswerProjection() throws Exception {
        var config=new org.apache.ibatis.session.Configuration();
        try(var in=getClass().getResourceAsStream("/mapping/AgentConversationElicitationDao.xml")) {
            new org.apache.ibatis.builder.xml.XMLMapperBuilder(in,config,"mapping/AgentConversationElicitationDao.xml",config.getSqlFragments()).parse();
        }
        String prefix=AgentConversationElicitationDao.class.getName()+".";
        var sql=config.getMappedStatement(prefix+"listQuestionHistory").getBoundSql(java.util.Map.of("tenantId",1L,"conversationId",10L));
        String text=sql.getSql().replaceAll("\\s+"," ");
        assertTrue(text.contains("tenant_id = ? AND conversation_id = ?"));
        assertTrue(text.contains("status IN ('EXPIRED','CANCELED','ANSWERED','DECLINED')"));
        assertTrue(text.contains("ORDER BY id DESC LIMIT 100"));
        String old=config.getMappedStatement(prefix+"listAnswerDeliveries").getBoundSql(java.util.Map.of()).getSql();
        assertTrue(old.contains("answer_id IS NOT NULL AND status IN ('ANSWERED','DECLINED')"));
        assertTrue(old.contains("ORDER BY id DESC LIMIT 100"));
    }

    @Test void settledHistoryPreservesQuestionAndActualAnswerVerbatim() {
        var dao=org.mockito.Mockito.mock(AgentConversationElicitationDao.class);
        var row=new AgentConversationElicitationDO();row.setRequestId("original");row.setTurnId(12L);
        row.setStatus("EXPIRED");row.setMessage("Original question");row.setSchemaJson("{\"order\":1}");
        row.setAnswerJson("{\"kept\":\"verbatim\"}");
        org.mockito.Mockito.when(dao.listQuestionHistory(1L,10L)).thenReturn(java.util.List.of(row));
        var service=new ConversationElicitationService(dao,null,null,null,null);
        var vo=ClarificationElicitationVO.fromDelivery(service.listQuestionHistory(1,10).getFirst());
        assertEquals("EXPIRED",vo.getStatus());assertEquals(12L,vo.getTurnId());
        assertEquals(row.getMessage(),vo.getMessage());assertEquals(row.getSchemaJson(),vo.getRequestedSchema());
        assertEquals(row.getAnswerJson(),vo.getAnswerJson());
    }

    @Test void optionalHistoryIsAbsentOnListDtosAndFailuresAreNotEmptyHistory() {
        var mapper=new com.fasterxml.jackson.databind.ObjectMapper();
        assertFalse(mapper.valueToTree(PlatformConversationVO.builder().build()).has("questionHistory"));
        assertFalse(mapper.valueToTree(ClarificationConversationVO.builder().build()).has("questionHistory"));
        var dao=org.mockito.Mockito.mock(AgentConversationElicitationDao.class);
        org.mockito.Mockito.when(dao.listQuestionHistory(1L,10L)).thenThrow(new IllegalStateException("read failed"));
        assertThrows(IllegalStateException.class,()->new ConversationElicitationService(dao,null,null,null,null).listQuestionHistory(1,10));
    }
}
