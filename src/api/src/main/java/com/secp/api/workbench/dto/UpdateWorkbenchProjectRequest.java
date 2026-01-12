package com.secp.api.workbench.dto;

import java.time.LocalDate;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

public record UpdateWorkbenchProjectRequest(
    String progressStatus,
    LocalDate targetDate,
    UUID ownerUserId,
    UUID leadUserId,
    UUID assistUserId,
    String entrustor,
    String note
) {
  public Map<String, Object> changedFields() {
    Map<String, Object> m = new LinkedHashMap<>();
    if (progressStatus != null) m.put("progressStatus", progressStatus);
    if (targetDate != null) m.put("targetDate", targetDate);
    if (ownerUserId != null) m.put("ownerUserId", ownerUserId);
    if (leadUserId != null) m.put("leadUserId", leadUserId);
    if (assistUserId != null) m.put("assistUserId", assistUserId);
    if (entrustor != null) m.put("entrustor", entrustor);
    if (note != null) m.put("note", note);
    return m;
  }
}
