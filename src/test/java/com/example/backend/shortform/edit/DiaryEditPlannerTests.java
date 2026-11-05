package com.example.backend.shortform.edit;

import com.example.backend.shortform.ai.AiRenderClient;
import org.junit.jupiter.api.Test;
import org.springframework.core.io.ByteArrayResource;

import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;

import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.*;

class DiaryEditPlannerTests {
    private final MediaInspector inspector = mock(MediaInspector.class);
    private final DiaryEditPlanner planner = new DiaryEditPlanner(inspector);

    private AiRenderClient.RenderSubmission submission(boolean video) {
        var sources = new ArrayList<AiRenderClient.Source>();
        for (int i = 0; i < 5; i++) {
            sources.add(new AiRenderClient.Source(UUID.randomUUID(), null, i,
                    video ? "video/mp4" : "image/png", new ByteArrayResource(new byte[]{(byte) i})));
        }
        return new AiRenderClient.RenderSubmission(UUID.randomUUID(), UUID.randomUUID(), "VLOG", "CALM", null, sources);
    }

    private void media(double seconds, boolean video) {
        when(inspector.inspect(any(), anyString())).thenReturn(
                new MediaInspector.MediaInfo(seconds, 1920, 1080, video, video));
    }

    @Test void repeatedPlanningPreservesSelectedOrderAndIsDeterministic() {
        media(0, false);
        var request = submission(false);
        var first = planner.plan(request, 35);
        var second = planner.plan(request, 35);
        assertEquals(first.cuts(), second.cuts());
        assertEquals(request.sources().stream().map(AiRenderClient.Source::assetId).toList(),
                first.cuts().stream().map(EditPlan.Cut::sourceId).toList());
        assertEquals(1080, first.width());
        assertEquals(1920, first.height());
        assertEquals(30, first.fps());
    }

    @Test void timelineHasNoGapsAndNeverExceedsRequestedBudget() {
        media(0, false);
        var plan = planner.plan(submission(false), 25);
        double next = 0;
        for (var cut : plan.cuts()) {
            assertEquals(next, cut.timelineStart(), 0.05);
            assertTrue(cut.duration() > 0);
            next += cut.duration();
        }
        assertEquals(next, plan.durationSeconds(), 0.05);
        assertTrue(plan.durationSeconds() <= 25.05);
    }

    @Test void videoTrimsStayInsideMeasuredSourceDuration() {
        media(4, true);
        var request = submission(true);
        var plan = planner.plan(request, 35);
        Set<UUID> allowed = request.sources().stream().map(AiRenderClient.Source::assetId).collect(Collectors.toSet());
        for (var cut : plan.cuts()) {
            assertTrue(allowed.contains(cut.sourceId()));
            assertTrue(cut.inPoint() >= 0);
            assertTrue(cut.inPoint() + cut.duration() <= 4.05);
        }
        assertTrue(plan.durationSeconds() <= 20.05);
    }

    @Test void insufficientFootageProducesWarningInsteadOfInventedExtension() {
        media(2, true);
        var plan = planner.plan(submission(true), 35);
        assertFalse(plan.warnings().isEmpty());
        assertTrue(plan.durationSeconds() <= 10.05);
        assertEquals(5, plan.cuts().stream().map(EditPlan.Cut::sourceId).distinct().count());
    }
    @Test void longSourcePlansRespectTheRenderersPerCutLimit() {
        media(120, true);
        var plan = planner.plan(submission(true), 90);
        assertTrue(plan.cuts().stream().allMatch(cut -> cut.duration() <= 15));
        assertTrue(plan.durationSeconds() <= 90);
        assertFalse(plan.warnings().isEmpty());
    }

    @Test void photographsUseEditingMotionWithoutInventingHandheldCaptureOrDiaryDays() {
        media(0, false);
        var plan = planner.plan(submission(false), 35);
        for (var cut : plan.cuts()) {
            assertNotNull(cut.motion());
            assertFalse(cut.motion().toLowerCase().contains("handheld"));
            if (cut.overlay() != null) {
                assertFalse(cut.overlay().matches("(?is).*DAY\\s*[1-6].*"));
                assertFalse(cut.overlay().contains("시험"));
                assertFalse(cut.overlay().contains("공부"));
            }
        }
    }

    @Test void planningExportsStyleMoodAndGroundedPromptAlongsideTimeline() {
        media(0, false);
        var request = submission(false);
        var plan = planner.plan(request, 35);
        assertNotNull(plan.prompt());
        assertFalse(plan.prompt().isBlank());
        assertTrue(plan.prompt().contains("VLOG"));
        assertTrue(plan.prompt().contains("CALM"));
        assertNotNull(plan.audio());
        assertTrue(plan.audio().preserveOriginal());
        assertTrue(plan.audio().normalize());
        assertNull(plan.audio().musicId());
    }

    @Test void rejectsDurationOutsideSupportedRangeBeforeReadingMedia() {
        var request = submission(false);
        assertThrows(RuntimeException.class, () -> planner.plan(request, 14));
        assertThrows(RuntimeException.class, () -> planner.plan(request, 91));
        verifyNoInteractions(inspector);
    }
}
