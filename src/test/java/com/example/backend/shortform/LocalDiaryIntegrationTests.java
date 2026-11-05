package com.example.backend.shortform;

import com.example.backend.PostgresIntegrationTest;
import com.example.backend.auth.AuthService;
import com.example.backend.auth.dto.SignupRequest;
import com.example.backend.shortform.edit.MediaInspector;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.core.io.FileSystemResource;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import javax.imageio.ImageIO;
import java.awt.Color;
import java.awt.image.BufferedImage;
import java.io.ByteArrayOutputStream;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import static org.junit.jupiter.api.Assertions.*;
import static org.junit.jupiter.api.Assumptions.assumeTrue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.*;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real PostgreSQL + FFmpeg encoding. No fake provider or generated-video placeholder. */
@SpringBootTest(properties = {
        "app.shortform.render-provider=remote",
        "app.shortform.ai-enabled=false",
        "app.shortform.worker-enabled=false",
        "app.shortform.retry-delay-ms=0",
        "app.shortform.result-poll-delay-ms=0"
})
@AutoConfigureMockMvc
class LocalDiaryIntegrationTests extends PostgresIntegrationTest {
    private static final Path ROOT = Path.of("build", "local-diary-api-" + UUID.randomUUID()).toAbsolutePath();
    private static final String FFMPEG = executable("SHORTFORM_FFMPEG_PATH", "FFMPEG_PATH", "ffmpeg");
    private static final String FFPROBE = executable("SHORTFORM_FFPROBE_PATH", "FFPROBE_PATH", "ffprobe");

    @Autowired MockMvc mvc;
    @Autowired AuthService auth;
    @Autowired ObjectMapper mapper;
    @Autowired ShortformWorker worker;
    @Autowired MediaInspector inspector;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("app.shortform.ffmpeg-path", () -> FFMPEG);
        registry.add("app.shortform.ffprobe-path", () -> FFPROBE);
        registry.add("app.shortform.storage-root", () -> ROOT.resolve("sources").toString());
        registry.add("app.shortform.output-root", () -> ROOT.resolve("renders").toString());
    }

    @BeforeAll static void requireRealMediaTools() {
        assumeTrue(available(FFMPEG) && available(FFPROBE), "Real FFmpeg and FFprobe are required for local rendering integration.");
    }

    @Test void measuredPlanRendersAnAuthenticatedVerticalVideoAndThumbnail() throws Exception {
        String owner = token("diary-owner");
        String other = token("diary-other");
        JsonNode options = json(mvc.perform(get("/api/shortforms/options")).andExpect(status().isOk())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        var providers = new ArrayList<String>();
        for (JsonNode provider : options.get("renderProviders")) providers.add(provider.asString());
        assertTrue(providers.contains("local"));
        assertFalse(providers.contains("remote"));
        JsonNode project = json(mvc.perform(post("/api/shortforms").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content("{\"title\":\"서울에서 보낸 하루\"}"))
                .andExpect(status().isCreated()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String base = "/api/shortforms/" + project.get("id").asString();
        var upload = multipart(base + "/assets");
        for (int index = 0; index < 5; index++) upload.file(photo(index));
        JsonNode uploaded = json(mvc.perform(upload.header("Authorization", owner)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        var assetIds = new ArrayList<String>();
        for (JsonNode asset : uploaded) assetIds.add(asset.get("id").asString());
        Map<String, Object> request = new LinkedHashMap<>();
        request.put("style", "VLOG"); request.put("mood", "CALM"); request.put("musicId", null);
        request.put("renderProvider", "local");
        request.put("assetIds", assetIds); request.put("targetDurationSec", 15);
        String requestJson = mapper.writeValueAsString(request);
        JsonNode plan = json(mvc.perform(post(base + "/edit-plan").header("Authorization", owner)
                        .contentType(MediaType.APPLICATION_JSON).content(requestJson))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals(1080, plan.get("width").asInt());
        assertEquals(1920, plan.get("height").asInt());
        assertTrue(plan.get("durationSeconds").asDouble() <= 15.05);
        assertEquals(5, plan.get("cuts").size());
        for (int index = 0; index < assetIds.size(); index++)
            assertEquals(assetIds.get(index), plan.get("cuts").get(index).get("sourceId").asString());
        assertFalse(plan.get("prompt").asString().isBlank());
        mvc.perform(post(base + "/edit-plan").header("Authorization", other)
                .contentType(MediaType.APPLICATION_JSON).content(requestJson)).andExpect(status().isNotFound());

        String key = UUID.randomUUID().toString();
        JsonNode accepted = json(mvc.perform(post(base + "/renders").header("Authorization", owner)
                        .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(requestJson))
                .andExpect(status().isAccepted()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        String resultBase = base + "/renders/" + accepted.get("id").asString();
        var changedDuration = new LinkedHashMap<>(request); changedDuration.put("targetDurationSec", 20);
        mvc.perform(post(base + "/renders").header("Authorization", owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(changedDuration)))
                .andExpect(status().isConflict());
        var changedProvider = new LinkedHashMap<>(request); changedProvider.put("renderProvider", "remote");
        mvc.perform(post(base + "/renders").header("Authorization", owner).header("Idempotency-Key", key)
                .contentType(MediaType.APPLICATION_JSON).content(mapper.writeValueAsString(changedProvider)))
                .andExpect(status().isConflict());
        worker.runOnce();
        worker.runOnce();
        JsonNode completed = json(mvc.perform(get(resultBase).header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString(StandardCharsets.UTF_8));
        assertEquals("SUCCEEDED", completed.get("status").asString(), completed.toString());
        byte[] video = mvc.perform(get(resultBase + "/video").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertTrue(video.length > 1000);
        Path output = ROOT.resolve("verified-result.mp4");
        Files.createDirectories(ROOT); Files.write(output, video);
        var info = inspector.inspect(new FileSystemResource(output), "video/mp4");
        assertEquals(1080, info.width()); assertEquals(1920, info.height());
        assertTrue(info.durationSeconds() > 0 && info.durationSeconds() <= 15.2);
        byte[] thumbnail = mvc.perform(get(resultBase + "/thumbnail").header("Authorization", owner))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsByteArray();
        assertNotNull(ImageIO.read(new java.io.ByteArrayInputStream(thumbnail)));
        mvc.perform(get(resultBase + "/video").header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get(resultBase + "/thumbnail").header("Authorization", other)).andExpect(status().isNotFound());
        mvc.perform(get(resultBase + "/video")).andExpect(status().isUnauthorized());
    }

    private String token(String prefix) {
        return "Bearer " + auth.signup(new SignupRequest(prefix + UUID.randomUUID() + "@example.test", "여행자", "password123")).accessToken();
    }
    private JsonNode json(String value) { return mapper.readTree(value); }
    private static MockMultipartFile photo(int index) throws Exception {
        var image = new BufferedImage(120, 200, BufferedImage.TYPE_INT_RGB);
        var graphics = image.createGraphics();
        try {
            graphics.setColor(Color.getHSBColor(index / 5f, 0.55f, 0.8f)); graphics.fillRect(0, 0, 120, 200);
            graphics.setColor(Color.WHITE); graphics.drawString("Seoul " + (index + 1), 20, 100);
        } finally { graphics.dispose(); }
        var bytes = new ByteArrayOutputStream(); ImageIO.write(image, "png", bytes);
        return new MockMultipartFile("files", "scene-" + index + ".png", "image/png", bytes.toByteArray());
    }
    private static String executable(String primary, String secondary, String fallback) {
        String result = System.getenv(primary);
        if (result == null || result.isBlank()) result = System.getenv(secondary);
        return result == null || result.isBlank() ? fallback : result;
    }
    private static boolean available(String command) {
        Process process = null;
        try {
            process = new ProcessBuilder(List.of(command, "-version")).redirectOutput(ProcessBuilder.Redirect.DISCARD)
                    .redirectError(ProcessBuilder.Redirect.DISCARD).start();
            return process.waitFor(5, TimeUnit.SECONDS) && process.exitValue() == 0;
        } catch (Exception error) { return false; }
        finally { if (process != null && process.isAlive()) process.destroyForcibly(); }
    }
}
