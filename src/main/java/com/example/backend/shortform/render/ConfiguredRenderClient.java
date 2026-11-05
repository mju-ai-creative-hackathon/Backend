package com.example.backend.shortform.render;

import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.*;
import com.example.backend.shortform.edit.DiaryEditPlanner;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Primary;
import org.springframework.stereotype.Component;
import java.util.UUID;

/** Keeps the existing provider untouched unless the experimental mode is explicitly enabled. */
@Component
@Primary
public class ConfiguredRenderClient implements AiRenderClient {
    private final HttpAiRenderClient remote;
    private final LocalVideoRenderer local;
    private final DiaryEditPlanner planner;
    private final String provider;
    private final boolean enhanced;
    public ConfiguredRenderClient(HttpAiRenderClient remote, LocalVideoRenderer local, DiaryEditPlanner planner,
            @Value("${app.shortform.render-provider:remote}") String provider,
            @Value("${app.shortform.enhanced-editing-enabled:false}") boolean enhanced) {
        if (!provider.equals("local") && !provider.equals("remote")) throw new IllegalArgumentException("Unknown shortform render provider");
        this.remote = remote; this.local = local; this.planner = planner; this.provider = provider; this.enhanced = enhanced;
    }
    @Override public boolean isConfigured() { return isConfigured(provider); }
    @Override public boolean isConfigured(String selected) {
        return "local".equals(selected) ? local.isAvailable() : "remote".equals(selected) && remote.isConfigured();
    }
    @Override public String submit(RenderSubmission request) {
        try {
            if ("local".equals(request.renderProvider() == null ? provider : request.renderProvider())) {
                if (request.musicId() != null) throw new AiRenderException("LOCAL_MUSIC_UNSUPPORTED", false, "로컬 모드의 배경 음악은 아직 지원하지 않습니다.");
                var plan = planner.plan(request, request.targetDurationSec(), request.projectTitle());
                local.render(request, plan);
                return "local:" + request.projectId() + ":" + request.jobId();
            }
            // Legacy provider manifests remain unchanged by default.
            var outgoing = enhanced ? request.withEditPlan(planner.plan(request, request.targetDurationSec(), request.projectTitle())) : request;
            return remote.submit(outgoing);
        } catch (ApiException error) {
            throw new AiRenderException("MEDIA_EDIT_FAILED", false, "원본 분석 또는 편집에 실패했습니다.");
        }
    }
    @Override public RenderResult poll(String externalJobId) {
        if (!externalJobId.startsWith("local:")) return remote.poll(externalJobId);
        try {
            String[] ids = externalJobId.split(":");
            if (ids.length != 3) throw new IllegalArgumentException();
            UUID project = UUID.fromString(ids[1]), job = UUID.fromString(ids[2]);
            local.video(job); local.thumbnail(job);
            String base = "/api/shortforms/" + project + "/renders/" + job;
            return new RenderResult("SUCCEEDED", base + "/video", base + "/thumbnail");
        } catch (RuntimeException error) {
            throw new AiRenderException("LOCAL_OUTPUT_MISSING", false, "완성된 영상 파일을 찾을 수 없습니다.");
        }
    }
}
