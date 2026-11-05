package com.example.backend.shortform.render;

import com.example.backend.common.ApiException;
import com.example.backend.shortform.ai.AiRenderClient;
import com.example.backend.shortform.edit.EditPlan;
import com.example.backend.shortform.edit.MediaInspector;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.FileSystemResource;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.nio.file.*;
import java.util.*;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.mockito.Mockito.mock;

class LocalVideoRendererTests {
    @TempDir Path temp;

    private AiRenderClient.RenderSubmission submission(Path file) {
        return new AiRenderClient.RenderSubmission(UUID.randomUUID(), UUID.randomUUID(), "VLOG", "CALM", null,
                List.of(new AiRenderClient.Source(UUID.randomUUID(), null, 0, "image/png", new FileSystemResource(file))));
    }
    private EditPlan plan(UUID source, String overlay, String music) {
        return new EditPlan("diary", "1", 108, 192, 30, .9,
                List.of(new EditPlan.Cut(source, "hook", 0, 0, .3, "static", overlay, "contain"),
                        new EditPlan.Cut(source, "activity", .3, 0, .3, "slow_push_in", null, "contain"),
                        new EditPlan.Cut(source, "ending", .6, 0, .3, "static", null, "contain")),
                new EditPlan.Audio(true, true, music), "", List.of());
    }
    @Test void unsupportedMusicAndMissingResultsAreExplicit() {
        var renderer = new LocalVideoRenderer("missing-ffmpeg", temp.toString(), "", mock(MediaInspector.class));
        var request = submission(temp.resolve("source.png"));
        var error = assertThrows(ApiException.class, () -> renderer.render(request,
                plan(request.sources().getFirst().assetId(), null, "unlicensed-track")));
        assertEquals(400, error.getStatus().value());
        assertEquals(404, assertThrows(ApiException.class, () -> renderer.video(request.jobId())).getStatus().value());
    }

    @Test void missingFontFailsBeforeExecutingRenderer() {
        var renderer = new LocalVideoRenderer("missing-ffmpeg", temp.toString(), temp.resolve("missing-font.ttf").toString(), mock(MediaInspector.class));
        var request = submission(temp.resolve("source.png"));
        var error = assertThrows(ApiException.class, () -> renderer.render(request,
                plan(request.sources().getFirst().assetId(), "서울의 하루", null)));
        assertEquals("FONT_UNAVAILABLE", error.getCode());
    }

    @Test void rejectsInvalidTimelineBeforeRunningAnyProcess() {
        var renderer = new LocalVideoRenderer("missing-ffmpeg", temp.toString(), "", mock(MediaInspector.class));
        var request = submission(temp.resolve("source.png"));
        var invalid = new EditPlan("diary", "1", 108, 192, 30, 1,
                List.of(new EditPlan.Cut(request.sources().getFirst().assetId(), "hook", .5, 0, 1, "static", null, "contain")),
                new EditPlan.Audio(true, false, null), "", List.of());
        assertEquals(400, assertThrows(ApiException.class, () -> renderer.render(request, invalid)).getStatus().value());
    }

    @Test void rendersRealPlayableMp4AndThumbnailAndReusesCompletedJob() throws Exception {
        String ffmpeg = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
        String ffprobe = System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe");
        var inspector = new MediaInspector(ffprobe, new ObjectMapper());
        Path output = temp.resolve("results");
        var renderer = new LocalVideoRenderer(ffmpeg, output.toString(), "", inspector);
        assumeTrue(renderer.isAvailable(), "FFmpeg is required for the real-render smoke test");
        var image = new BufferedImage(20, 30, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics(); graphics.setColor(Color.ORANGE); graphics.fillRect(0, 0, 20, 30); graphics.dispose();
        Path photo = temp.resolve("source.png"); ImageIO.write(image, "png", photo.toFile());
        byte[] original = Files.readAllBytes(photo);
        var request = submission(photo);
        var plan = plan(request.sources().getFirst().assetId(), null, null);
        renderer.render(request, plan);
        var video = renderer.video(request.jobId());
        var metadata = inspector.inspect(video, "video/mp4");
        assertEquals(108, metadata.width()); assertEquals(192, metadata.height());
        assertEquals(.9, metadata.durationSeconds(), .15); assertTrue(metadata.hasAudio());
        assertNotNull(ImageIO.read(renderer.thumbnail(request.jobId()).getFile()));
        var modified = Files.getLastModifiedTime(video.getFile().toPath());
        renderer.render(request, plan);
        assertEquals(modified, Files.getLastModifiedTime(video.getFile().toPath()));
        assertArrayEquals(original, Files.readAllBytes(photo));
        try (var files = Files.list(output)) { assertEquals(2, files.count()); }
    }

    @Test void shortOriginalAudioUsesSafeLimiterAndRemainsPlayable() throws Exception {
        String ffmpeg = System.getenv().getOrDefault("FFMPEG_PATH", "ffmpeg");
        String ffprobe = System.getenv().getOrDefault("FFPROBE_PATH", "ffprobe");
        var inspector = new MediaInspector(ffprobe, new ObjectMapper());
        var renderer = new LocalVideoRenderer(ffmpeg, temp.resolve("audio-results").toString(), "", inspector);
        assumeTrue(renderer.isAvailable(), "FFmpeg is required for the real-render smoke test");
        Path original = temp.resolve("tone.mp4");
        Process fixture = new ProcessBuilder(ffmpeg, "-nostdin", "-hide_banner", "-loglevel", "error", "-y",
                "-f", "lavfi", "-i", "color=c=blue:s=108x192:r=30:d=1",
                "-f", "lavfi", "-i", "sine=frequency=440:sample_rate=48000:duration=1",
                "-c:v", "libx264", "-pix_fmt", "yuv420p", "-c:a", "aac", "-shortest", original.toString())
                .redirectError(ProcessBuilder.Redirect.DISCARD).redirectOutput(ProcessBuilder.Redirect.DISCARD).start();
        try {
            assertTrue(fixture.waitFor(15, java.util.concurrent.TimeUnit.SECONDS));
            assertEquals(0, fixture.exitValue());
        } finally { if (fixture.isAlive()) fixture.destroyForcibly(); }
        var source = new AiRenderClient.Source(UUID.randomUUID(), null, 0, "video/mp4", new FileSystemResource(original));
        var request = new AiRenderClient.RenderSubmission(UUID.randomUUID(), UUID.randomUUID(), "VLOG", "CALM", null, List.of(source));
        renderer.render(request, plan(source.assetId(), null, null));
        var metadata = inspector.inspect(renderer.video(request.jobId()), "video/mp4");
        assertTrue(metadata.hasAudio()); assertEquals(.9, metadata.durationSeconds(), .15);
        assertEquals(108, metadata.width()); assertEquals(192, metadata.height());
    }
}
