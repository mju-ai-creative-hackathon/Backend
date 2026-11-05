package com.example.backend.shortform.edit;

import org.springframework.beans.factory.config.YamlPropertiesFactoryBean;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;
import java.util.Properties;

/** Versioned server-owned template. User uploads are never parsed as YAML or executable rules. */
@Component
public class DiaryStylePreset {
    private final Properties rules;
    public DiaryStylePreset() {
        var yaml = new YamlPropertiesFactoryBean();
        yaml.setResources(new ClassPathResource("shortform/daily-diary-v1.yaml"));
        rules = yaml.getObject();
        if (rules == null) throw new IllegalStateException("Diary template is missing");
        if (width() < 2 || height() < 2 || width() > 1080 || height() > 1920
                || width() % 2 != 0 || height() % 2 != 0 || fps() < 1 || fps() > 30)
            throw new IllegalStateException("Invalid diary output dimensions");
        for (String key : new String[]{"timing.photo_max_seconds", "timing.hook_max_seconds", "timing.ending_max_seconds"}) {
            double value = number(key);
            if (!Double.isFinite(value) || value <= 0 || value > 10) throw new IllegalStateException("Invalid diary timing");
        }
        if (!java.util.Set.of("static", "slow_push_in").contains(photoMotion())
                || !required("visual.video_motion").equals("original") || !fit().equals("contain")
                || !required("visual.transition").equals("cut")) throw new IllegalStateException("Unsupported diary visual rule");
    }
    public String id() { return required("identity.id"); }
    public String version() { return required("identity.version"); }
    public int width() { return Integer.parseInt(required("output.width")); }
    public int height() { return Integer.parseInt(required("output.height")); }
    public int fps() { return Integer.parseInt(required("output.fps")); }
    public double photoMax() { return number("timing.photo_max_seconds"); }
    public double hookMax() { return number("timing.hook_max_seconds"); }
    public double endingMax() { return number("timing.ending_max_seconds"); }
    public String photoMotion() { return required("visual.photo_motion"); }
    public String fit() { return required("visual.fit"); }
    public boolean preserveOriginal() { return flag("audio.preserve_original"); }
    public boolean normalizeAudio() { return flag("audio.normalize"); }
    private boolean flag(String name) {
        String value = required(name);
        if (!value.equals("true") && !value.equals("false")) throw new IllegalStateException("Invalid diary boolean rule");
        return Boolean.parseBoolean(value);
    }
    private double number(String name) { return Double.parseDouble(required(name)); }
    private String required(String name) {
        String value = rules.getProperty(name);
        if (value == null || value.isBlank()) throw new IllegalStateException("Missing diary template rule: " + name);
        return value;
    }
}
