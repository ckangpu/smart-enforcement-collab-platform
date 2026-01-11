package com.secp.api.auth;

import java.util.List;
import java.util.UUID;

public record AuthUser(UUID userId, boolean isAdmin, List<UUID> groupIds, String username) {}
