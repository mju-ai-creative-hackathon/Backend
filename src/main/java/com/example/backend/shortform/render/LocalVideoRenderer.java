package com.example.backend.shortform.render;

import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.edit.EditPlan;
import com.example.backend.shortform.edit.MediaInspector;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.FileSystemResource;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.time.Duration;
import java.util.*;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;

/** Experimental original-footage renderer. It does not invent scenes, speech or music. */
@Component
public class LocalVideoRenderer {
    private static final long JOB_TIMEOUT_SECONDS = 240;
    private static final long PROCESS_TIMEOUT_SECONDS = 80;
    private final String executable;
    private final Path outputRoot;
    private final String fontFile;
    private final MediaInspector inspector;
    private final Object availabilityLock = new Object();
    private volatile long nextAvailabilityCheck = Long.MIN_VALUE;
    private volatile boolean cachedAvailability;

    public LocalVideoRenderer(@Value("${app.shortform.ffmpeg-path:ffmpeg}") String executable,
            @Value("${app.shortform.output-root:./data/shortforms/renders}") String outputRoot,
            @Value("${app.shortform.font-file:}") String fontFile, MediaInspector inspector) {
        this.executable = executable;
        this.fontFile = fontFile;
        this.inspector = inspector;
        try {
            Path configured = Path.of(outputRoot).toAbsolutePath().normalize();
            if (Files.isSymbolicLink(configured)) throw new IOException("Symbolic output root");
            Files.createDirectories(configured);
            this.outputRoot = configured.toRealPath();
        } catch (IOException error) { throw new IllegalStateException("영상 결과 저장소를 초기화할 수 없습니다."); }
    }

    public boolean isAvailable() {
        long now = System.nanoTime();
        if (now < nextAvailabilityCheck) return cachedAvailability;
        synchronized (availabilityLock) {
            if (System.nanoTime() < nextAvailabilityCheck) return cachedAvailability;
            try {
                run(List.of(executable, "-version"), outputRoot, System.nanoTime() + Duration.ofSeconds(5).toNanos());
                cachedAvailability = inspector.isAvailable();
            } catch (RuntimeException error) { cachedAvailability = false; }
            nextAvailabilityCheck = System.nanoTime() + Duration.ofSeconds(30).toNanos();
            return cachedAvailability;
        }
    }

    public synchronized void render(AiRenderClient.RenderSubmission submission, EditPlan plan) {
        validate(submission, plan);
        Path video = result(submission.jobId(), ".mp4");
        Path thumbnail = result(submission.jobId(), ".jpg");
        if (Files.isRegularFile(video, LinkOption.NOFOLLOW_LINKS)
                && Files.isRegularFile(thumbnail, LinkOption.NOFOLLOW_LINKS)) return;
        Path work = null;
        long deadline = System.nanoTime() + Duration.ofSeconds(JOB_TIMEOUT_SECONDS).toNanos();
        try {
            work = Files.createTempDirectory(outputRoot, "render-" + submission.jobId() + "-");
            var sourceMap = submission.sources().stream().collect(Collectors.toMap(AiRenderClient.Source::assetId, s -> s));
            boolean hasText = plan.cuts().stream().anyMatch(c -> c.overlay() != null && !c.overlay().isBlank());
            if (hasText) Files.copy(resolveFont(), work.resolve("font.ttf"));
            var playlist = new ArrayList<String>();
            boolean includesOriginalAudio = false;
            for (int index = 0; index < plan.cuts().size(); index++) {
                var cut = plan.cuts().get(index);
                var source = sourceMap.get(cut.sourceId());
                if (source == null) throw ApiException.badRequest("편집 계획에 존재하지 않는 소스가 있습니다.");
                Path original = source.resource().getFile().toPath().toRealPath();
                if (!Files.isRegularFile(original, LinkOption.NOFOLLOW_LINKS)) throw ApiException.badRequest("원본 파일을 읽을 수 없습니다.");
                var info = inspector.inspect(source.resource(), source.contentType());
                if (info.video() && cut.inPoint() + cut.duration() > info.durationSeconds() + 0.05)
                    throw ApiException.badRequest("편집 구간이 원본 영상 길이를 초과합니다.");
                String clip = String.format(Locale.ROOT, "clip-%03d.mp4", index);
                var command = new ArrayList<>(List.of(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-y"));
                if (info.video()) command.addAll(List.of("-ss", number(cut.inPoint())));
                else command.addAll(List.of("-loop", "1", "-framerate", Integer.toString(plan.fps())));
                command.addAll(List.of("-protocol_whitelist", "file,pipe", "-i", original.toString()));
                boolean sourceAudio = info.video() && info.hasAudio() && plan.audio().preserveOriginal();
                includesOriginalAudio |= sourceAudio;
                if (!sourceAudio) command.addAll(List.of("-f", "lavfi", "-i", "anullsrc=r=48000:cl=stereo"));
                command.addAll(List.of("-map", "0:v:0", "-map", sourceAudio ? "0:a:0" : "1:a:0"));
                String filter = visualFilter(plan, cut, !info.video());
                if (cut.overlay() != null && !cut.overlay().isBlank()) {
                    Files.writeString(work.resolve("overlay-" + index + ".txt"), cut.overlay(), StandardCharsets.UTF_8);
                    filter += ",drawtext=fontfile=font.ttf:textfile=overlay-" + index
                            + ".txt:expansion=none:fontcolor=white:fontsize=" + Math.max(16, plan.width() / 24)
                            + ":x=(w-text_w)/2:y=h*0.09:box=1:boxcolor=black@0.45:boxborderw=12:fix_bounds=1";
                }
                command.addAll(List.of("-vf", filter, "-af", "aresample=48000,apad", "-t", number(cut.duration()), "-r", Integer.toString(plan.fps()),
                        "-c:v", "libx264", "-preset", "veryfast", "-crf", "20", "-pix_fmt", "yuv420p",
                        "-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2", "-threads", "2",
                        "-movflags", "+faststart", "-shortest", clip));
                run(command, work, deadline);
                playlist.add("file '" + clip + "'");
            }
            Files.write(work.resolve("concat.txt"), playlist, StandardCharsets.UTF_8);
            var concat = new ArrayList<>(List.of(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                    "-f", "concat", "-safe", "1", "-i", "concat.txt", "-map", "0:v:0", "-map", "0:a:0", "-c:v", "copy"));
            // EBU loudness analysis has no useful target for synthesized silence and can emit
            // non-finite samples on sub-second clips. Keep silence silent; use a peak limiter
            // for very short original-audio edits that cannot provide an analysis window.
            if (plan.audio().normalize() && includesOriginalAudio) concat.addAll(List.of("-af",
                    plan.durationSeconds() >= 3 ? "loudnorm=I=-16:TP=-1.5:LRA=11" : "alimiter=limit=0.8913:level=false"));
            concat.addAll(List.of("-c:a", "aac", "-b:a", "160k", "-ar", "48000", "-ac", "2",
                    "-t", number(plan.durationSeconds()), "-movflags", "+faststart", "-f", "mp4", "result.part"));
            run(concat, work, deadline);
            run(List.of(executable, "-nostdin", "-hide_banner", "-loglevel", "error", "-y", "-ss",
                    number(Math.min(0.5, plan.durationSeconds() / 4)), "-i", "result.part", "-frames:v", "1", "-q:v", "2", "thumbnail.jpg"), work, deadline);
            if (Files.size(work.resolve("result.part")) == 0 || Files.size(work.resolve("thumbnail.jpg")) == 0) throw renderFailure();
            publish(work.resolve("result.part"), video);
            publish(work.resolve("thumbnail.jpg"), thumbnail);
        } catch (ApiException error) { throw error; }
        catch (IOException | RuntimeException error) { throw renderFailure(); }
        finally { cleanWork(work); }
    }

    public Resource video(UUID jobId) { return load(result(jobId, ".mp4")); }
    public Resource thumbnail(UUID jobId) { return load(result(jobId, ".jpg")); }

    private static String visualFilter(EditPlan plan, EditPlan.Cut cut, boolean photo) {
        String size = plan.width() + ":" + plan.height();
        String filter = "scale=" + size + ":force_original_aspect_ratio=decrease,pad=" + size
                + ":(ow-iw)/2:(oh-ih)/2:color=0x181818,setsar=1";
        if (photo && cut.motion() != null && !cut.motion().equalsIgnoreCase("static"))
            filter += ",zoompan=z='min(zoom+0.00012,1.03)':x='iw/2-(iw/zoom/2)':y='ih/2-(ih/zoom/2)':d=1:s="
                    + plan.width() + "x" + plan.height() + ":fps=" + plan.fps();
        return filter + ",fps=" + plan.fps() + ",format=yuv420p";
    }

    private static void validate(AiRenderClient.RenderSubmission submission, EditPlan plan) {
        if (submission.jobId() == null || submission.sources().isEmpty() || submission.sources().size() > 20
                || plan == null || plan.cuts() == null || plan.cuts().isEmpty() || plan.cuts().size() > 40
                || plan.width() < 2 || plan.width() > 1080 || plan.height() < 2 || plan.height() > 1920
                || plan.width() % 2 != 0 || plan.height() % 2 != 0 || plan.fps() < 1 || plan.fps() > 30
                || !Double.isFinite(plan.durationSeconds()) || plan.durationSeconds() <= 0 || plan.durationSeconds() > 90
                || plan.audio() == null) throw ApiException.badRequest("지원하지 않는 편집 계획입니다.");
        if ((submission.musicId() != null && !submission.musicId().isBlank())
                || (plan.audio().musicId() != null && !plan.audio().musicId().isBlank()))
            throw ApiException.badRequest("임시 로컬 렌더러는 배경 음악을 지원하지 않습니다.");
        double end = 0;
        for (var cut : plan.cuts()) {
            if (cut.sourceId() == null || !Double.isFinite(cut.inPoint()) || cut.inPoint() < 0
                    || !Double.isFinite(cut.duration()) || cut.duration() <= 0 || cut.duration() > 15
                    || !Double.isFinite(cut.timelineStart()) || Math.abs(cut.timelineStart() - end) > 0.05
                    || (cut.overlay() != null && (cut.overlay().length() > 240 || cut.overlay().indexOf('\0') >= 0)))
                throw ApiException.badRequest("편집 구간이 올바르지 않습니다.");
            end += cut.duration();
        }
        if (Math.abs(end - plan.durationSeconds()) > 0.1) throw ApiException.badRequest("편집 계획의 총 길이가 일치하지 않습니다.");
    }

    private Path resolveFont() {
        var candidates = fontFile.isBlank()
                ? List.of("C:/Windows/Fonts/malgun.ttf", "/usr/share/fonts/opentype/noto/NotoSansCJK-Regular.ttc",
                        "/usr/share/fonts/truetype/noto/NotoSansCJK-Regular.ttc") : List.of(fontFile);
        return candidates.stream().map(Path::of).filter(Files::isRegularFile).findFirst()
                .orElseThrow(() -> new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FONT_UNAVAILABLE", "한글 자막용 글꼴을 설정해주세요."));
    }
    private Path result(UUID id, String extension) {
        Path result = outputRoot.resolve(Objects.requireNonNull(id) + extension);
        if (Files.isSymbolicLink(result)) throw ApiException.badRequest("유효하지 않은 결과 경로입니다.");
        return result;
    }
    private Resource load(Path file) {
        if (!Files.isRegularFile(file, LinkOption.NOFOLLOW_LINKS)) throw ApiException.notFound("제작 영상을 찾을 수 없습니다.");
        return new FileSystemResource(file);
    }
    private static String number(double value) { return String.format(Locale.ROOT, "%.6f", value); }
    private static void publish(Path source, Path target) throws IOException {
        try { Files.move(source, target, StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING); }
        catch (AtomicMoveNotSupportedException ignored) { Files.move(source, target, StandardCopyOption.REPLACE_EXISTING); }
    }
    private static void run(List<String> command, Path directory, long deadline) {
        Process process = null;
        try {
            long remainingMs = Math.min(PROCESS_TIMEOUT_SECONDS * 1000, TimeUnit.NANOSECONDS.toMillis(deadline - System.nanoTime()));
            if (remainingMs <= 0) throw renderTimeout();
            process = new ProcessBuilder(command).directory(directory.toFile()).redirectErrorStream(true).start();
            Process running = process;
            Thread drain = Thread.ofVirtual().start(() -> {
                try (InputStream stream = running.getInputStream()) { stream.transferTo(OutputStream.nullOutputStream()); }
                catch (IOException ignored) {}
            });
            if (!process.waitFor(remainingMs, TimeUnit.MILLISECONDS)) throw renderTimeout();
            drain.join(1000);
            if (process.exitValue() != 0) throw renderFailure();
        } catch (InterruptedException error) { Thread.currentThread().interrupt(); throw renderTimeout(); }
        catch (IOException error) { throw new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "FFMPEG_UNAVAILABLE", "FFmpeg 실행 파일을 확인해주세요."); }
        finally {
            if (process != null && process.isAlive()) {
                process.descendants().forEach(child -> child.destroyForcibly());
                process.destroyForcibly();
            }
        }
    }
    private void cleanWork(Path work) {
        if (work == null || !work.toAbsolutePath().normalize().startsWith(outputRoot)) return;
        try (var paths = Files.walk(work)) {
            paths.sorted(Comparator.reverseOrder()).forEach(path -> { try { Files.deleteIfExists(path); } catch (IOException ignored) {} });
        } catch (IOException ignored) {}
    }
    private static ApiException renderFailure() { return new ApiException(HttpStatus.UNPROCESSABLE_ENTITY, "LOCAL_RENDER_FAILED", "원본을 확인한 후 다시 제작해주세요."); }
    private static ApiException renderTimeout() { return new ApiException(HttpStatus.GATEWAY_TIMEOUT, "LOCAL_RENDER_TIMEOUT", "영상 제작 제한 시간을 초과했습니다."); }
}
