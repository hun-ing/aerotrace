package com.huning.aerotrace.auth.application;

import java.util.UUID;

public record AccessibleProject(
        UUID projectId, UUID tenantId, String name, String slug
) implements ProjectScope {
}
