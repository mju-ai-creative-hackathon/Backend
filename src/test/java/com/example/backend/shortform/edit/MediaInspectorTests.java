package com.example.backend.shortform.edit;

import com.example.backend.common.ApiException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.core.io.ByteArrayResource;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.HttpStatus;
import tools.jackson.databind.json.JsonMapper;
import javax.imageio.ImageIO;
import java.awt.image.BufferedImage;
import java.io.*;
import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.concurrent.TimeUnit;
import static org.junit.jupiter.api.Assertions.*;
import static org.mockito.ArgumentMatchers.*;
import static org.mockito.Mockito.*;

class MediaInspectorTests {
    @TempDir Path directory;
    private final MediaInspector inspector = new MediaInspector("ffprobe", JsonMapper.builder().build());
    private byte[] json(String value) { return value.getBytes(StandardCharsets.UTF_8); }

    @Test void readsActualPhotoDimensionsWithoutExternalTools() throws Exception {
        var path = directory.resolve("photo.png");
        assertTrue(ImageIO.write(new BufferedImage(640, 320, BufferedImage.TYPE_INT_RGB), "png", path.toFile()));
        var result = inspector.inspect(new FileSystemResource(path), "image/png");
        assertEquals(new MediaInspector.MediaInfo(0, 640, 320, false, false), result);
    }

    @Test void rejectsUndecodableImagesAndNonFileResources() throws Exception {
        var path = directory.resolve("broken.png"); Files.write(path, new byte[]{1, 2, 3});
        assertThrows(ApiException.class, () -> inspector.inspect(new FileSystemResource(path), "image/png"));
        assertThrows(ApiException.class, () -> inspector.inspect(new ByteArrayResource(new byte[]{1}), "video/mp4"));
        Files.write(path, new byte[]{(byte)137,80,78,71,13,10,26,10});
        assertThrows(ApiException.class, () -> inspector.inspect(new FileSystemResource(path), "image/png"));
    }

    @Test void readsVideoDurationAudioAndDisplayMatrixRotation() {
        var result = inspector.parseProbe(json("""
                {"streams":[{"codec_type":"video","width":1920,"height":1080,
                  "side_data_list":[{"rotation":-90}]},{"codec_type":"audio"}],"format":{"duration":"7.25"}}
                """));
        assertEquals(new MediaInspector.MediaInfo(7.25,1080,1920,true,true), result);
        var tags = inspector.parseProbe(json("""
                {"streams":[{"codec_type":"video","width":1280,"height":720,"duration":"2.5","tags":{"rotate":"90"}}]}
                """));
        assertEquals(new MediaInspector.MediaInfo(2.5,720,1280,false,true), tags);
    }

    @Test void rejectsMissingVideoInvalidDimensionsAndNonFiniteDuration() {
        for (String value : new String[]{"{}", "[]", "not json", "{\"streams\":[{\"codec_type\":\"audio\"}]}",
                "{\"streams\":[{\"codec_type\":\"video\",\"width\":0,\"height\":720}],\"format\":{\"duration\":\"1\"}}",
                "{\"streams\":[{\"codec_type\":\"video\",\"width\":1280,\"height\":720}],\"format\":{\"duration\":\"NaN\"}}"})
            assertThrows(ApiException.class, () -> inspector.parseProbe(json(value)));
    }

    @Test void nativeImageMetadataDoesNotInventVideoDuration() {
        var result = inspector.parseProbe(json("""
                {"streams":[{"codec_type":"video","codec_name":"webp","width":640,"height":480}]}
                """), false);
        assertEquals(new MediaInspector.MediaInfo(0,640,480,false,false), result);
    }

    @Test void audioTailDoesNotExtendSelectableVideoDuration() {
        var result = inspector.parseProbe(json("""
                {"streams":[{"codec_type":"video","width":1280,"height":720,"duration":"1.0"},
                            {"codec_type":"audio","duration":"10.0"}],"format":{"duration":"10.0"}}
                """));
        assertEquals(1.0, result.durationSeconds());
        assertTrue(result.hasAudio());
    }

    @Test void missingExecutableReturns503WithoutLeakingFilePaths() throws Exception {
        var path = directory.resolve("private-source.mp4"); Files.write(path, new byte[]{1});
        var unavailable = new MediaInspector("missing-executable-for-unit-test", JsonMapper.builder().build());
        var error = assertThrows(ApiException.class, () -> unavailable.inspect(new FileSystemResource(path), "video/mp4"));
        assertEquals(HttpStatus.SERVICE_UNAVAILABLE, error.getStatus());
        assertEquals("MEDIA_PROBE_UNAVAILABLE", error.getCode());
        assertFalse(error.getMessage().contains(path.toString()));
    }

    @Test void invokesProbeWithoutShellAndRejectsFailedExit() throws Exception {
        var path = directory.resolve("file with spaces.mp4"); Files.write(path, new byte[]{1});
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(json("{\"streams\":[]}")));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(true);
        when(process.exitValue()).thenReturn(1);
        var probe = new MediaInspector("ffprobe", JsonMapper.builder().build(), command -> {
            assertEquals("ffprobe", command.getFirst());
            assertEquals(path.toAbsolutePath().toString(), command.getLast());
            assertFalse(command.contains("sh")); assertFalse(command.contains("cmd"));
            return process;
        });
        assertThrows(ApiException.class, () -> probe.inspect(new FileSystemResource(path), "video/mp4"));
    }

    @Test void timeoutForcefullyStopsProcessAndReturns503() throws Exception {
        var path = directory.resolve("slow.mp4"); Files.write(path, new byte[]{1});
        Process process = mock(Process.class);
        when(process.getInputStream()).thenReturn(new ByteArrayInputStream(new byte[0]));
        when(process.waitFor(anyLong(), any(TimeUnit.class))).thenReturn(false);
        when(process.isAlive()).thenReturn(true);
        var probe = new MediaInspector("ffprobe", JsonMapper.builder().build(), command -> process);
        assertEquals("MEDIA_PROBE_UNAVAILABLE", assertThrows(ApiException.class,
                () -> probe.inspect(new FileSystemResource(path), "video/mp4")).getCode());
        verify(process).destroyForcibly();
    }
}
