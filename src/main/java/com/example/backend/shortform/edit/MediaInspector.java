package com.example.backend.shortform.edit;

import com.example.backend.common.ApiException;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.core.io.Resource;
import org.springframework.http.HttpStatus;
import org.springframework.stereotype.Component;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import javax.imageio.ImageReader;
import javax.imageio.stream.ImageInputStream;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.LinkOption;
import java.nio.file.Path;
import java.util.List;
import java.util.Locale;
import java.util.concurrent.*;

/** Reads actual local source metadata. Never downloads media or substitutes guessed durations. */
@Component
public class MediaInspector {
    private static final int MAX_OUTPUT = 512 * 1024;
    private final String executable;
    private final ObjectMapper mapper;
    private final ProcessLauncher launcher;

    @Autowired
    public MediaInspector(@Value("${app.shortform.ffprobe-path:ffprobe}") String executable, ObjectMapper mapper) {
        this(executable, mapper, command -> new ProcessBuilder(command)
                .redirectError(ProcessBuilder.Redirect.DISCARD).start());
    }

    MediaInspector(String executable, ObjectMapper mapper, ProcessLauncher launcher) {
        if (executable == null || executable.isBlank() || executable.indexOf('\0') >= 0)
            throw new IllegalArgumentException("A local ffprobe executable is required.");
        this.executable = executable;
        this.mapper = mapper;
        this.launcher = launcher;
    }

    public record MediaInfo(double durationSeconds, int width, int height, boolean hasAudio, boolean video) {}

    public boolean isAvailable() {
        Process process = null;
        try {
            process = launcher.start(List.of(executable, "-version"));
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (IOException | RuntimeException error) { return false; }
        catch (InterruptedException error) { Thread.currentThread().interrupt(); return false; }
        finally {
            if (process != null) {
                if (process.isAlive()) process.destroyForcibly();
                try { process.getInputStream().close(); } catch (IOException ignored) {}
            }
        }
    }

    public MediaInfo inspect(Resource resource, String contentType) {
        Path file = localFile(resource);
        if (contentType != null && contentType.toLowerCase(Locale.ROOT).startsWith("image/"))
            return image(file, "image/webp".equalsIgnoreCase(contentType));
        if (contentType == null || !contentType.toLowerCase(Locale.ROOT).startsWith("video/")) throw invalid();
        return probe(file, true);
    }

    private Path localFile(Resource resource) {
        try {
            if (resource == null || !resource.isFile()) throw invalid();
            Path path = resource.getFile().toPath().toAbsolutePath().normalize();
            if (!Files.isRegularFile(path, LinkOption.NOFOLLOW_LINKS) || Files.isSymbolicLink(path)) throw invalid();
            return path;
        } catch (IOException error) { throw invalid(); }
    }

    private MediaInfo image(Path path, boolean nativeFallback) {
        ImageReader reader = null;
        try (ImageInputStream input = ImageIO.createImageInputStream(path.toFile())) {
            if (input == null) throw invalid();
            var readers = ImageIO.getImageReaders(input);
            // The JDK's built-in ImageIO readers do not normally include WebP.
            if (!readers.hasNext()) {
                if (nativeFallback) return probe(path, false);
                throw invalid();
            }
            reader = readers.next();
            reader.setInput(input, true, true);
            int width = reader.getWidth(0), height = reader.getHeight(0);
            validateDimensions(width, height);
            // Decode a subsampled image to reject corrupt pixel data without allocating the full source bitmap.
            var parameters = reader.getDefaultReadParam();
            parameters.setSourceSubsampling(Math.max(1, (width + 511) / 512), Math.max(1, (height + 511) / 512), 0, 0);
            if (reader.read(0, parameters) == null) throw invalid();
            return new MediaInfo(0, width, height, false, false);
        } catch (IOException | RuntimeException error) {
            if (error instanceof ApiException api) throw api;
            throw invalid();
        } finally { if (reader != null) reader.dispose(); }
    }

    private MediaInfo probe(Path path, boolean videoContent) {
        Process process;
        try {
            process = launcher.start(List.of(executable, "-v", "error", "-protocol_whitelist", "file,pipe", "-show_streams", "-show_format",
                    "-of", "json", path.toString()));
        } catch (IOException | RuntimeException error) { throw unavailable(); }
        var executor = Executors.newVirtualThreadPerTaskExecutor();
        Future<byte[]> output = executor.submit(() -> {
            byte[] bytes = process.getInputStream().readNBytes(MAX_OUTPUT + 1);
            if (bytes.length > MAX_OUTPUT) { process.destroyForcibly(); throw invalid(); }
            return bytes;
        });
        try {
            if (!process.waitFor(15, TimeUnit.SECONDS)) throw unavailable();
            byte[] bytes = output.get(1, TimeUnit.SECONDS);
            if (process.exitValue() != 0) throw invalid();
            return parseProbe(bytes, videoContent);
        } catch (InterruptedException error) {
            Thread.currentThread().interrupt(); throw unavailable();
        } catch (TimeoutException error) { throw unavailable(); }
        catch (ExecutionException error) {
            if (error.getCause() instanceof ApiException api) throw api;
            throw invalid();
        } finally {
            if (process.isAlive()) process.destroyForcibly();
            output.cancel(true);
            try { process.getInputStream().close(); } catch (IOException ignored) {}
            executor.shutdownNow();
        }
    }

    MediaInfo parseProbe(byte[] bytes) {
        return parseProbe(bytes, true);
    }

    MediaInfo parseProbe(byte[] bytes, boolean videoContent) {
        if (bytes.length == 0 || bytes.length > MAX_OUTPUT) throw invalid();
        try {
            JsonNode root = mapper.readTree(bytes);
            if (root == null || !root.isObject() || !root.path("streams").isArray()) throw invalid();
            JsonNode video = null;
            boolean audio = false;
            for (JsonNode stream : root.path("streams")) {
                String type = stream.path("codec_type").asString("");
                if ("audio".equals(type)) audio = true;
                if (video == null && "video".equals(type) && stream.path("disposition").path("attached_pic").asInt(0) == 0)
                    video = stream;
            }
            if (video == null) throw invalid();
            int width = video.path("width").asInt(0), height = video.path("height").asInt(0);
            validateDimensions(width, height);
            // Container duration may include an audio tail after the final video frame.
            // Use the selected video's measured duration whenever the demuxer provides it.
            double duration = number(video.path("duration"));
            if (!Double.isFinite(duration) || duration <= 0) duration = number(root.path("format").path("duration"));
            if (videoContent && (!Double.isFinite(duration) || duration <= 0 || duration > 86400)) throw invalid();
            double rotation = number(video.path("tags").path("rotate"));
            for (JsonNode data : video.path("side_data_list")) {
                if (data.has("rotation")) { rotation = number(data.path("rotation")); break; }
            }
            if (Double.isFinite(rotation)) {
                long normalized = Math.floorMod(Math.round(rotation), 360);
                if (normalized == 90 || normalized == 270) { int swap = width; width = height; height = swap; }
            }
            return new MediaInfo(videoContent ? duration : 0, width, height, videoContent && audio, videoContent);
        } catch (RuntimeException error) {
            if (error instanceof ApiException api) throw api;
            throw invalid();
        }
    }

    private static double number(JsonNode value) {
        if (value.isNumber()) return value.doubleValue();
        try { return Double.parseDouble(value.asString("NaN")); }
        catch (NumberFormatException error) { return Double.NaN; }
    }

    private static void validateDimensions(int width, int height) {
        if (width < 1 || height < 1 || width > 16384 || height > 16384 || (long) width * height > 100_000_000)
            throw invalid();
    }

    private static ApiException invalid() { return ApiException.badRequest("미디어를 분석할 수 없습니다. 정상적인 사진·영상 파일을 사용해주세요."); }
    private static ApiException unavailable() {
        return new ApiException(HttpStatus.SERVICE_UNAVAILABLE, "MEDIA_PROBE_UNAVAILABLE", "영상 분석 도구를 사용할 수 없거나 분석 제한 시간을 초과했습니다.");
    }
    @FunctionalInterface interface ProcessLauncher { Process start(List<String> command) throws IOException; }
}
