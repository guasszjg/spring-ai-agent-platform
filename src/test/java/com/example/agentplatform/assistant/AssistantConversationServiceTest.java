package com.example.agentplatform.assistant;

import com.example.agentplatform.model.AssistantConversation;
import com.example.agentplatform.model.UserRole;
import com.example.agentplatform.repository.AssistantActionRepository;
import com.example.agentplatform.repository.AssistantConversationRepository;
import com.example.agentplatform.repository.AssistantMessageRepository;
import com.example.agentplatform.security.CurrentActor;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.InjectMocks;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import java.util.Optional;

import static org.junit.jupiter.api.Assertions.assertEquals;
import static org.junit.jupiter.api.Assertions.assertFalse;
import static org.junit.jupiter.api.Assertions.assertNotNull;
import static org.junit.jupiter.api.Assertions.assertThrows;
import static org.junit.jupiter.api.Assertions.assertTrue;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

@ExtendWith(MockitoExtension.class)
class AssistantConversationServiceTest {

    @Mock AssistantConversationRepository conversationRepository;
    @Mock AssistantMessageRepository messageRepository;
    @Mock AssistantActionRepository actionRepository;
    @InjectMocks AssistantConversationService service;

    private final CurrentActor alice = new CurrentActor("u-alice", "alice", UserRole.DEVELOPER);

    @Test
    void otherUsersConversationCannotBeContinued() {
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull("asc-bob", "u-alice")).thenReturn(Optional.empty());

        assertThrows(IllegalArgumentException.class, () -> service.openOrCreate("asc-bob", "hi", alice));
        assertTrue(service.detail("asc-bob", alice).isEmpty());
        assertFalse(service.delete("asc-bob", alice));
        verify(messageRepository, never()).clearContents(any());
    }

    @Test
    void deleteClearsContentButKeepsRowsForUsageStatistics() {
        AssistantConversation conversation = new AssistantConversation();
        conversation.setId("asc-1");
        conversation.setUserId("u-alice");
        conversation.setTitle("我的智能体有哪些");
        when(conversationRepository.findByIdAndUserIdAndDeletedAtIsNull("asc-1", "u-alice")).thenReturn(Optional.of(conversation));

        assertTrue(service.delete("asc-1", alice));

        verify(messageRepository).clearContents("asc-1");
        verify(actionRepository).clearForConversation("asc-1");
        verify(conversationRepository, never()).delete(any());
        assertNotNull(conversation.getDeletedAt());
        assertEquals(null, conversation.getTitle());
    }

    @Test
    void newConversationBelongsToCurrentUserWithShortTitle() {
        when(conversationRepository.save(any())).thenAnswer(inv -> inv.getArgument(0));

        AssistantConversation created = service.openOrCreate(null, "  我想做一个售后客服机器人，\n需要准备哪些资料，怎么绑定知识库比较好？ ", alice);

        assertEquals("u-alice", created.getUserId());
        assertEquals(AssistantConversationService.TITLE_MAX + 1, created.getTitle().length());
        assertTrue(created.getTitle().startsWith("我想做一个售后客服机器人， 需要"));
    }
}
