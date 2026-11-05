package com.example.backend.shortform.edit;

import java.util.*;

/** Provider-neutral, measured-source editing instructions. No generated speech or invented events. */
public record EditPlan(String templateId, String version, int width, int height, int fps,
                       double durationSeconds, List<Cut> cuts, Audio audio, String prompt, List<String> warnings) {
    public EditPlan { cuts = List.copyOf(cuts); warnings = List.copyOf(warnings); }
    public record Cut(UUID sourceId, String role, double timelineStart, double inPoint, double duration,
                      String motion, String overlay, String fit) {}
    public record Audio(boolean preserveOriginal, boolean normalize, String musicId) {}
}
