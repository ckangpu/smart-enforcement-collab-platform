package com.secp.api.auth;

import java.util.UUID;

public record AuthPrincipal(UUID userId, boolean isAdmin, String username, String userType) {
}
