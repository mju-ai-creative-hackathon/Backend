package com.example.backend.shortform;

import com.example.backend.auth.AuthService;
import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.edit.*;
import com.example.backend.shortform.render.LocalVideoRenderer;
import com.example.backend.shortform.storage.MediaStorage;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import java.util.*;
import java.util.function.Function;
import java.util.stream.Collectors;

@Service
@Transactional(readOnly = true)
public class ShortformEditingService {
    private final ShortformProjectRepository projects;
    private final ShortformAssetRepository assets;
    private final RenderJobRepository jobs;
    private final AuthService auth;
    private final MediaStorage storage;
    private final DiaryEditPlanner planner;
    private final LocalVideoRenderer renderer;
    private final RenderStateService state;
    public ShortformEditingService(ShortformProjectRepository projects, ShortformAssetRepository assets,
            RenderJobRepository jobs, AuthService auth, MediaStorage storage, DiaryEditPlanner planner,
            LocalVideoRenderer renderer, RenderStateService state) {
        this.projects = projects; this.assets = assets; this.jobs = jobs; this.auth = auth;
        this.storage = storage; this.planner = planner; this.renderer = renderer; this.state = state;
    }
    public EditPlan preview(String authorization, UUID projectId, ShortformDtos.RenderRequest request) {
        var project = owned(authorization, projectId);
        if (request.assetIds() == null || request.assetIds().size() < 5 || request.assetIds().size() > 20
                || new HashSet<>(request.assetIds()).size() != request.assetIds().size())
            throw ApiException.badRequest("소스를 중복 없이 5~20개 선택해주세요.");
        var available = assets.findByProjectIdOrderByPositionAsc(projectId).stream().collect(Collectors.toMap(a -> a.id, Function.identity()));
        var sources = new ArrayList<AiRenderClient.Source>();
        for (int i = 0; i < request.assetIds().size(); i++) {
            var source = available.get(request.assetIds().get(i));
            if (source == null) throw ApiException.badRequest("이 프로젝트의 원본만 선택해주세요.");
            sources.add(new AiRenderClient.Source(source.id, source.missionId, i, source.contentType, storage.load(source.storageKey)));
        }
        var submission = new AiRenderClient.RenderSubmission(UUID.randomUUID(), projectId, request.style().name(),
                request.mood().name(), request.musicId(), sources).withTargetDuration(request.effectiveTargetDuration());
        return planner.plan(state.withShootingHints(submission), request.effectiveTargetDuration(), project.title);
    }
    public ShortformService.Download result(String authorization, UUID projectId, UUID jobId, boolean thumbnail) {
        owned(authorization, projectId);
        var job = jobs.findById(jobId).filter(j -> j.projectId.equals(projectId)
                && j.status == RenderStatus.SUCCEEDED && ("local:" + projectId + ":" + jobId).equals(j.externalJobId))
                .orElseThrow(() -> ApiException.notFound("완료된 로컬 제작 결과를 찾을 수 없습니다."));
        return new ShortformService.Download(thumbnail ? renderer.thumbnail(job.id) : renderer.video(job.id),
                thumbnail ? "image/jpeg" : "video/mp4", job.id + (thumbnail ? ".jpg" : ".mp4"));
    }
    private ShortformProject owned(String authorization, UUID id) {
        UUID owner = auth.getCurrentUser(authorization).id();
        return projects.findById(id).filter(p -> p.ownerId.equals(owner))
                .orElseThrow(() -> ApiException.notFound("프로젝트를 찾을 수 없습니다."));
    }
}
