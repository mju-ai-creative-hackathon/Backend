package com.example.backend.shortform.ai;

import org.springframework.core.io.Resource;
import com.example.backend.shortform.edit.EditPlan;

import java.util.List;
import java.util.UUID;

/** Draft provider boundary. Change this adapter when the AI team's contract is finalized. */
public interface AiRenderClient {
    boolean isConfigured();
    default boolean isConfigured(String provider) { return "remote".equals(provider) && isConfigured(); }

    String submit(RenderSubmission request);

    RenderResult poll(String externalJobId);

    record RenderSubmission(UUID jobId, UUID projectId, String style, String mood, String musicId,
                            List<Source> sources, List<ShootingHint> shootingHints, EditPlan editPlan, int targetDurationSec, String projectTitle, String renderProvider) {
        public RenderSubmission {
            sources = List.copyOf(sources);
            shootingHints = shootingHints == null ? List.of() : List.copyOf(shootingHints);
        }

        public RenderSubmission(UUID jobId, UUID projectId, String style, String mood, String musicId,
                                List<Source> sources) {
            this(jobId, projectId, style, mood, musicId, sources, List.of());
        }
        public RenderSubmission(UUID jobId, UUID projectId, String style, String mood, String musicId,
                                List<Source> sources, List<ShootingHint> shootingHints) {
            this(jobId, projectId, style, mood, musicId, sources, shootingHints, null, 35, null, null);
        }
        public RenderSubmission withTargetDuration(int seconds) {
            return new RenderSubmission(jobId, projectId, style, mood, musicId, sources, shootingHints, editPlan, seconds, projectTitle, renderProvider);
        }
        public RenderSubmission withEditPlan(EditPlan plan) {
            return new RenderSubmission(jobId, projectId, style, mood, musicId, sources, shootingHints, plan, targetDurationSec, projectTitle, renderProvider);
        }
        public RenderSubmission withProjectTitle(String title) {
            return new RenderSubmission(jobId, projectId, style, mood, musicId, sources, shootingHints, editPlan, targetDurationSec, title, renderProvider);
        }
        public RenderSubmission withRenderProvider(String provider) {
            return new RenderSubmission(jobId, projectId, style, mood, musicId, sources, shootingHints, editPlan, targetDurationSec, projectTitle, provider);
        }
    }

    record Source(UUID assetId, UUID missionId, int position, String contentType, Resource resource) {}

    record RenderResult(String status, String videoUrl, String thumbnailUrl) {}
}
