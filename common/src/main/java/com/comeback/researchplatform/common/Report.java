package com.comeback.researchplatform.common;

import java.util.List;

public record Report(
        String summary,
        List<Section> sections
) {}
