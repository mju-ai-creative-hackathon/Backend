package com.example.backend.shortform.render;

import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.ai.AiRenderException;
import com.example.backend.shortform.ai.HttpAiRenderClient;
import com.example.backend.shortform.ai.ShootingHint;
import com.example.backend.shortform.edit.DiaryEditPlanner;
import com.example.backend.shortform.edit.EditPlan;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.core.io.ByteArrayResource;

import java.util.List;
import java.util.UUID;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.*;

class ConfiguredRenderClientTests {
    private final HttpAiRenderClient remote = mock(HttpAiRenderClient.class);
    private final LocalVideoRenderer local = mock(LocalVideoRenderer.class);
    private final DiaryEditPlanner planner = mock(DiaryEditPlanner.class);

    private AiRenderClient.RenderSubmission request() {
        return new AiRenderClient.RenderSubmission(UUID.randomUUID(), UUID.randomUUID(), "VLOG", "CALM", null,
                List.of(new AiRenderClient.Source(UUID.randomUUID(), null, 0, "image/png", new ByteArrayResource(new byte[]{1}))),
                List.of(new ShootingHint(7L, "서울숲", "PHOTO", "공원 전경", "전체 풍경을 촬영", 5)))
                .withTargetDuration(22).withProjectTitle("서울 하루");
    }
    private EditPlan plan() {
        return new EditPlan("daily_diary", "1.0", 1080, 1920, 30, 3, List.of(),
                new EditPlan.Audio(true, true, null), "grounded editing instructions", List.of());
    }

    @Test void explicitLocalRequestWorksWhenDefaultRemoteIsUnconfigured() {
        var client = new ConfiguredRenderClient(remote, local, planner, "remote", false);
        when(local.isAvailable()).thenReturn(true);
        assertFalse(client.isConfigured());
        assertTrue(client.isConfigured("local"));
        var request = request().withRenderProvider("local");
        var plan = plan();
        when(planner.plan(request, 22, "서울 하루")).thenReturn(plan);
        assertEquals("local:" + request.projectId() + ":" + request.jobId(), client.submit(request));
        verify(local).render(request, plan);
        verify(remote, never()).submit(any());
    }

    @Test void legacyRemoteSubmissionPassesSameObjectWithoutInspectionOrPlanning() {
        var client = new ConfiguredRenderClient(remote, local, planner, "remote", false);
        var request = request();
        when(remote.submit(request)).thenReturn("remote-existing-job");
        assertEquals("remote-existing-job", client.submit(request));
        var captured = ArgumentCaptor.forClass(AiRenderClient.RenderSubmission.class);
        verify(remote).submit(captured.capture());
        assertSame(request, captured.getValue());
        verifyNoInteractions(local, planner);
    }

    @Test void enhancedRemoteAddsPlanAndPreservesCourseHintsAndSelectedSettings() {
        var client = new ConfiguredRenderClient(remote, local, planner, "remote", true);
        var request = request().withRenderProvider("remote");
        var plan = plan();
        when(planner.plan(request, 22, "서울 하루")).thenReturn(plan);
        when(remote.submit(any())).thenReturn("remote-enhanced-job");
        assertEquals("remote-enhanced-job", client.submit(request));
        var captured = ArgumentCaptor.forClass(AiRenderClient.RenderSubmission.class);
        verify(remote).submit(captured.capture());
        var outgoing = captured.getValue();
        assertSame(plan, outgoing.editPlan());
        assertEquals(request.shootingHints(), outgoing.shootingHints());
        assertEquals(request.sources(), outgoing.sources());
        assertEquals(22, outgoing.targetDurationSec());
        assertEquals("서울 하루", outgoing.projectTitle());
        assertEquals("remote", outgoing.renderProvider());
        verifyNoInteractions(local);
    }

    @Test void localPollReturnsAuthenticatedOwnerRoutesWhenOutputFilesExist() {
        var client = new ConfiguredRenderClient(remote, local, planner, "remote", false);
        UUID projectId = UUID.randomUUID(), jobId = UUID.randomUUID();
        when(local.video(jobId)).thenReturn(new ByteArrayResource(new byte[]{1}));
        when(local.thumbnail(jobId)).thenReturn(new ByteArrayResource(new byte[]{2}));
        var result = client.poll("local:" + projectId + ":" + jobId);
        assertEquals("SUCCEEDED", result.status());
        assertEquals("/api/shortforms/" + projectId + "/renders/" + jobId + "/video", result.videoUrl());
        assertEquals("/api/shortforms/" + projectId + "/renders/" + jobId + "/thumbnail", result.thumbnailUrl());
        verify(local).video(jobId); verify(local).thumbnail(jobId);
        verifyNoInteractions(remote, planner);
    }

    @Test void remotePollDelegatesWithoutAccessingLocalFiles() {
        var client = new ConfiguredRenderClient(remote, local, planner, "local", false);
        var expected = new AiRenderClient.RenderResult("PROCESSING", null, null);
        when(remote.poll("remote-job-1")).thenReturn(expected);
        assertSame(expected, client.poll("remote-job-1"));
        verifyNoInteractions(local, planner);
    }

    @Test void unsupportedLocalMusicFailsBeforePlanningOrRendering() {
        var client = new ConfiguredRenderClient(remote, local, planner, "remote", false);
        var original = request();
        var withMusic = new AiRenderClient.RenderSubmission(original.jobId(), original.projectId(), original.style(),
                original.mood(), "unknown-music", original.sources()).withRenderProvider("local");
        var error = assertThrows(AiRenderException.class, () -> client.submit(withMusic));
        assertEquals("LOCAL_MUSIC_UNSUPPORTED", error.getCode());
        assertFalse(error.isRetryable());
        verifyNoInteractions(local, planner, remote);
    }

    @Test void missingLocalOutputDoesNotBecomeSuccessfulResult() {
        var client = new ConfiguredRenderClient(remote, local, planner, "remote", false);
        UUID projectId = UUID.randomUUID(), jobId = UUID.randomUUID();
        when(local.video(jobId)).thenThrow(ApiException.notFound("missing"));
        var error = assertThrows(AiRenderException.class, () -> client.poll("local:" + projectId + ":" + jobId));
        assertEquals("LOCAL_OUTPUT_MISSING", error.getCode());
        assertFalse(error.isRetryable());
        verifyNoInteractions(remote, planner);
    }
}
