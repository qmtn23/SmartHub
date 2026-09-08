package com.hmdp.service.memory;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.fasterxml.jackson.databind.node.ObjectNode;
import com.hmdp.config.TaskMemoryProperties;
import org.junit.jupiter.api.Test;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import static org.junit.jupiter.api.Assertions.*;

class TaskMemoryMergerTest {
    final ObjectMapper json = new ObjectMapper();
    final TaskMemoryMerger merger = new TaskMemoryMerger(json, new TaskMemoryProperties());
    final LocalDateTime now = LocalDateTime.of(2026,9,8,12,0);
    List<Map<String,Object>> messages(long id, String time) {
        return List.of(Map.of("messageId", id, "senderType", "USER", "createTime", time));
    }
    JsonNode diff(String taskId, long sourceId, JsonNode patch) {
        ObjectNode root = json.createObjectNode();
        ObjectNode op = root.putArray("operations").addObject();
        if (taskId != null) op.put("taskId",taskId);
        op.putArray("sourceMessageIds").add(sourceId);
        op.set("patch",patch);
        return root;
    }
    ObjectNode initial() {
        ObjectNode patch = json.createObjectNode().put("intent","订单123退款");
        patch.putArray("constraints").add("原路退回");
        patch.putArray("openQuestions").add("是否符合条件");
        return merger.merge(merger.empty(),diff(null,1,patch),messages(1,now.toString()),now);
    }
    @Test void fieldPatchPreservesUnchangedFactsAndExplicitlyClearsResolvedQuestion() {
        ObjectNode before = initial();
        String id = before.path("tasks").get(0).path("taskId").asText();
        ObjectNode patch = json.createObjectNode();
        patch.putArray("openQuestions");
        ObjectNode after = merger.merge(before,diff(id,2,patch),messages(2,now.plusMinutes(1).toString()),now);
        JsonNode task = after.path("tasks").get(0);
        assertEquals("原路退回",task.path("constraints").get(0).asText());
        assertTrue(task.path("openQuestions").isEmpty());
        assertEquals(1,before.path("tasks").get(0).path("openQuestions").size());
        assertEquals("USER",task.path("fieldEvidence").path("openQuestions").path("sources").get(0).path("senderType").asText());
    }
    @Test void unknownSourcesAndForeignTaskIdsCannotBeMerged() {
        assertThrows(IllegalArgumentException.class,()->merger.merge(initial(),diff("foreign",1,
                json.createObjectNode().put("status","RESOLVED")),messages(1,now.toString()),now));
        assertThrows(IllegalArgumentException.class,()->merger.merge(initial(),diff(null,999,
                json.createObjectNode().put("intent","invented")),messages(1,now.toString()),now));
    }
    @Test void profileAndInvalidStatusesAreRejected() {
        assertThrows(IllegalArgumentException.class,()->merger.merge(initial(),diff(null,1,
                json.createObjectNode().put("intent","test").put("profile","not supported")),messages(1,now.toString()),now));
        assertThrows(IllegalArgumentException.class,()->merger.merge(initial(),diff(null,1,
                json.createObjectNode().put("intent","test").put("status","REFUNDED")),messages(1,now.toString()),now));
    }
    @Test void lateOlderMessageDoesNotOverwriteNewerField() {
        ObjectNode before = initial();
        String id = before.path("tasks").get(0).path("taskId").asText();
        ObjectNode after = merger.merge(before,diff(id,2,json.createObjectNode().put("intent","older goal")),
                messages(2,now.minusHours(1).toString()),now);
        assertEquals("订单123退款",after.path("tasks").get(0).path("intent").asText());
    }
    @Test void expiredTasksLeaveEmptyStructuredMemoryAndEvidenceStaysOutOfExtraction() {
        ObjectNode before = initial();
        assertTrue(merger.prune(before,now.plusDays(31)).path("tasks").isEmpty());
        assertFalse(merger.extractionView(before).path("tasks").get(0).has("fieldEvidence"));
        assertTrue(before.path("tasks").get(0).has("fieldEvidence"));
    }
    @Test void taskCountAndActiveCountAreBounded() {
        ObjectNode value = merger.empty();
        for(int i=0;i<12;i++) value=merger.merge(value,diff(null,1,json.createObjectNode().put("intent","task "+i)),messages(1,now.toString()),now);
        assertEquals(5,value.path("tasks").size());
    }
}
