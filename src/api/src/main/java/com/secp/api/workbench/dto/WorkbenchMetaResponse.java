package com.secp.api.workbench.dto;

import java.util.List;

public record WorkbenchMetaResponse(
    List<GroupOptionDto> groups
) {
}
