package com.secp.api.workbench;

import com.secp.api.workbench.dto.*;
import lombok.RequiredArgsConstructor;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Repository;

import java.sql.Date;
import java.sql.Timestamp;
import java.time.LocalDate;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

@Repository
@RequiredArgsConstructor
public class WorkbenchProjectRepository {

  private final JdbcTemplate jdbc;

  public List<GroupOptionDto> listMyGroups() {
    return jdbc.query(
        """
        select g.id, g.name
          from app_group g
         where g.id = any(app_group_ids())
         order by g.name
        """,
        (rs, rowNum) -> new GroupOptionDto(UUID.fromString(rs.getString("id")), rs.getString("name"))
    );
  }

  public List<GroupMemberDto> listGroupMembers(UUID groupId) {
    return jdbc.query(
        """
        select u.id, u.username, u.phone
          from user_group ug
          join app_user u on u.id = ug.user_id
         where ug.group_id = ?
         order by u.username
        """,
        ps -> ps.setObject(1, groupId),
        (rs, rowNum) -> new GroupMemberDto(UUID.fromString(rs.getString("id")), rs.getString("username"), rs.getString("phone"))
    );
  }

  public void insertProject(UUID projectId, CreateWorkbenchProjectRequest req, String code, UUID actorUserId) {
    jdbc.update(
        """
        insert into project(
          id, group_id, name, status,
          created_by,
          code, accepted_at,
          entrustor, progress_status, target_date,
          owner_user_id, lead_user_id, assist_user_id,
          note
        )
        values (?,?,?,?, ?,?,?, ?,?,?, ?,?,?, ?)
        """,
        projectId,
        req.groupId(),
        req.name(),
        "ACTIVE",
        actorUserId,
        code,
        Date.valueOf(req.acceptedAt()),
        req.entrustor(),
        req.progressStatusOrDefault(),
        req.targetDate() == null ? null : Date.valueOf(req.targetDate()),
        req.ownerUserId(),
        req.leadUserId(),
        req.assistUserId(),
        req.note()
    );
  }

  public Optional<Map<String, Object>> findProjectBase(UUID projectId) {
    var rows = jdbc.queryForList("select id, group_id from project where id=?", projectId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateProject(UUID projectId, UpdateWorkbenchProjectRequest req) {
    jdbc.update(
        """
        update project
           set progress_status = coalesce(?, progress_status),
               target_date = coalesce(?, target_date),
               owner_user_id = coalesce(?, owner_user_id),
               lead_user_id = coalesce(?, lead_user_id),
               assist_user_id = coalesce(?, assist_user_id),
               entrustor = coalesce(?, entrustor),
               note = coalesce(?, note),
               updated_at = now()
         where id = ?
        """,
        req.progressStatus(),
        req.targetDate() == null ? null : Date.valueOf(req.targetDate()),
        req.ownerUserId(),
        req.leadUserId(),
        req.assistUserId(),
        req.entrustor(),
        req.note(),
        projectId
    );
  }

  public Optional<WorkbenchProjectBaseDto> findProjectDetail(UUID projectId) {
    var rows = jdbc.queryForList(
        """
        select p.id as project_id,
               p.group_id,
               p.code,
               p.name,
               p.status,
               p.accepted_at,
               p.entrustor,
               p.progress_status,
               p.target_date,
               p.note,
               p.created_at,
               p.updated_at,
               p.owner_user_id,
               ou.username as owner_name,
               p.lead_user_id,
               lu.username as lead_name,
               p.assist_user_id,
               au.username as assist_name
          from project p
          left join app_user ou on ou.id = p.owner_user_id
          left join app_user lu on lu.id = p.lead_user_id
          left join app_user au on au.id = p.assist_user_id
         where p.id = ?
        """,
        projectId
    );
    if (rows.isEmpty()) return Optional.empty();
    Map<String, Object> r = rows.getFirst();
    return Optional.of(WorkbenchProjectBaseDto.fromRow(r));
  }

  public List<WorkbenchCreditorDto> listCreditors(UUID projectId) {
    return jdbc.query(
        """
     select id, sr_code, name, id_no, unified_code, reg_address,
       e_delivery_phone, mail_address, mail_recipient, mail_phone,
       note,
               created_at, updated_at
          from project_creditor
         where project_id = ?
         order by sr_code
        """,
        ps -> ps.setObject(1, projectId),
        (rs, rowNum) -> new WorkbenchCreditorDto(
            UUID.fromString(rs.getString("id")),
            rs.getString("sr_code"),
            rs.getString("name"),
            rs.getString("id_no"),
            rs.getString("unified_code"),
            rs.getString("reg_address"),
            rs.getString("e_delivery_phone"),
            rs.getString("mail_address"),
            rs.getString("mail_recipient"),
            rs.getString("mail_phone"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public List<WorkbenchDebtorDto> listDebtors(UUID projectId) {
    return jdbc.query(
        """
     select id, br_code, name, id_no, unified_code, reg_address,
       e_delivery_phone, mail_address, mail_recipient, mail_phone,
       note,
               created_at, updated_at
          from project_debtor
         where project_id = ?
         order by br_code
        """,
        ps -> ps.setObject(1, projectId),
        (rs, rowNum) -> new WorkbenchDebtorDto(
            UUID.fromString(rs.getString("id")),
            rs.getString("br_code"),
            rs.getString("name"),
            rs.getString("id_no"),
            rs.getString("unified_code"),
            rs.getString("reg_address"),
            rs.getString("e_delivery_phone"),
            rs.getString("mail_address"),
            rs.getString("mail_recipient"),
            rs.getString("mail_phone"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public List<WorkbenchCaseDto> listCases(UUID projectId) {
    return jdbc.query(
        """
        select c.id,
               c.code,
               c.title,
               c.court,
               c.status,
               c.creditor_id,
               pc.sr_code as creditor_code,
               pc.name as creditor_name,
               c.debtor_id,
               pd.br_code as debtor_code,
               pd.name as debtor_name,
               c.cause,
               c.basis_doc_type,
               c.basis_doc_no,
               c.basis_org,
               c.basis_main_text,
               c.basis_decided_at,
               c.accepted_at,
               c.updated_at
          from "case" c
          left join project_creditor pc on pc.id = c.creditor_id
          left join project_debtor pd on pd.id = c.debtor_id
         where c.project_id = ?
         order by c.created_at desc
        """,
        ps -> ps.setObject(1, projectId),
        (rs, rowNum) -> new WorkbenchCaseDto(
            UUID.fromString(rs.getString("id")),
            rs.getString("code"),
            rs.getString("title"),
            rs.getString("court"),
            rs.getString("status"),
            rs.getObject("creditor_id", UUID.class),
            rs.getString("creditor_code"),
            rs.getString("creditor_name"),
            rs.getObject("debtor_id", UUID.class),
            rs.getString("debtor_code"),
            rs.getString("debtor_name"),
            rs.getString("cause"),
            rs.getString("basis_doc_type"),
            rs.getString("basis_doc_no"),
            rs.getString("basis_org"),
            rs.getString("basis_main_text"),
            rs.getDate("basis_decided_at") == null ? null : rs.getDate("basis_decided_at").toLocalDate(),
            rs.getDate("accepted_at") == null ? null : rs.getDate("accepted_at").toLocalDate(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public Optional<Map<String, Object>> findExistingCaseForProjectPartiesCause(UUID projectId,
                                                                              UUID creditorId,
                                                                              UUID debtorId,
                                                                              String cause) {
    String cs = cause == null ? "" : cause.trim();
    var rows = jdbc.queryForList(
        """
        select c.id, c.code
          from \"case\" c
         where c.project_id = ?
           and c.creditor_id = ?
           and c.debtor_id = ?
           and trim(coalesce(c.cause, '')) = ?
         order by c.created_at desc
         limit 1
        """,
        projectId,
        creditorId,
        debtorId,
        cs
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public Optional<Map<String, Object>> findExistingCaseForProjectPartiesCauseBasis(UUID projectId,
                                                                                   UUID creditorId,
                                                                                   UUID debtorId,
                                                                                   String cause,
                                                                                   String basisDocType,
                                                                                   String basisDocNo,
                                                                                   String basisOrg,
                                                                                   LocalDate basisDecidedAt) {
    String cs = cause == null ? "" : cause.trim();
    String t = basisDocType == null ? "" : basisDocType.trim();
    String n = basisDocNo == null ? "" : basisDocNo.trim();
    String o = basisOrg == null ? "" : basisOrg.trim();

    var rows = jdbc.queryForList(
        """
        select c.id, c.code
          from \"case\" c
         where c.project_id = ?
           and c.creditor_id = ?
           and c.debtor_id = ?
           and trim(coalesce(c.cause, '')) = ?
           and trim(coalesce(c.basis_doc_type, '')) = ?
           and trim(coalesce(c.basis_doc_no, '')) = ?
           and trim(coalesce(c.basis_org, '')) = ?
           and c.basis_decided_at is not distinct from ?
         order by c.created_at desc
         limit 1
        """,
        projectId,
        creditorId,
        debtorId,
        cs,
        t,
        n,
        o,
        basisDecidedAt == null ? null : Date.valueOf(basisDecidedAt)
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public Optional<Map<String, Object>> findExistingCreditorForProject(UUID projectId, String name, String idNo, String unifiedCode) {
    String nm = name == null ? null : name.trim();
    String id = idNo == null ? null : idNo.trim();
    String uc = unifiedCode == null ? null : unifiedCode.trim();

    var rows = jdbc.queryForList(
        """
        select id, sr_code
          from project_creditor
         where project_id = ?
           and (
             (? is not null and id_no = ?)
             or (? is not null and unified_code = ?)
             or (? is not null and name = ?)
           )
         order by created_at desc
         limit 1
        """,
        projectId,
        id, id,
        uc, uc,
        nm, nm
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public Optional<Map<String, Object>> findExistingDebtorForProject(UUID projectId, String name, String idNo, String unifiedCode) {
    String nm = name == null ? null : name.trim();
    String id = idNo == null ? null : idNo.trim();
    String uc = unifiedCode == null ? null : unifiedCode.trim();

    var rows = jdbc.queryForList(
        """
        select id, br_code
          from project_debtor
         where project_id = ?
           and (
             (? is not null and id_no = ?)
             or (? is not null and unified_code = ?)
             or (? is not null and name = ?)
           )
         order by created_at desc
         limit 1
        """,
        projectId,
        id, id,
        uc, uc,
        nm, nm
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public Optional<Map<String, Object>> findProjectDetailRow(UUID projectId) {
    var rows = jdbc.queryForList("select id, group_id, accepted_at from project where id=?", projectId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void insertCreditor(UUID creditorId, UUID groupId, UUID projectId, String srCode, CreateCreditorRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into project_creditor(
          id, group_id, project_id, sr_code,
          name, id_no, unified_code, reg_address,
          e_delivery_phone, mail_address, mail_recipient, mail_phone,
          note,
          created_by
        )
        values (?,?,?,?, ?,?,?, ?,?,?,?, ?,?, ?)
        """,
        creditorId, groupId, projectId, srCode,
        req.name(), req.idNo(), req.unifiedCode(), req.regAddress(),
        req.eDeliveryPhone(), req.mailAddress(), req.mailRecipient(), req.mailPhone(),
        req.note(),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findCreditorBase(UUID creditorId) {
    var rows = jdbc.queryForList("select id, group_id, project_id from project_creditor where id=?", creditorId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateCreditor(UUID creditorId, UpdateCreditorRequest req) {
    jdbc.update(
        """
        update project_creditor
           set name = coalesce(?, name),
               id_no = coalesce(?, id_no),
               unified_code = coalesce(?, unified_code),
               reg_address = coalesce(?, reg_address),
               e_delivery_phone = coalesce(?, e_delivery_phone),
               mail_address = coalesce(?, mail_address),
               mail_recipient = coalesce(?, mail_recipient),
               mail_phone = coalesce(?, mail_phone),
               note = coalesce(?, note),
               updated_at = now()
         where id = ?
        """,
        req.name(),
        req.idNo(),
        req.unifiedCode(),
        req.regAddress(),
        req.eDeliveryPhone(),
        req.mailAddress(),
        req.mailRecipient(),
        req.mailPhone(),
        req.note(),
        creditorId
    );
  }

  public void deleteCreditor(UUID creditorId) {
    jdbc.update("delete from project_creditor where id=?", creditorId);
  }

  public void insertDebtor(UUID debtorId, UUID groupId, UUID projectId, String brCode, CreateDebtorRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into project_debtor(
          id, group_id, project_id, br_code,
          name, id_no, unified_code, reg_address,
          e_delivery_phone, mail_address, mail_recipient, mail_phone,
          note,
          created_by
        )
        values (?,?,?,?, ?,?,?, ?,?,?,?, ?,?, ?)
        """,
        debtorId, groupId, projectId, brCode,
        req.name(), req.idNo(), req.unifiedCode(), req.regAddress(),
        req.eDeliveryPhone(), req.mailAddress(), req.mailRecipient(), req.mailPhone(),
        req.note(),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findDebtorBase(UUID debtorId) {
    var rows = jdbc.queryForList(
        """
        select d.id, d.group_id, d.project_id
          from project_debtor d
         where d.id=?
        """,
        debtorId
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateDebtor(UUID debtorId, UpdateDebtorRequest req) {
    jdbc.update(
        """
        update project_debtor
           set name = coalesce(?, name),
               id_no = coalesce(?, id_no),
               unified_code = coalesce(?, unified_code),
               reg_address = coalesce(?, reg_address),
               e_delivery_phone = coalesce(?, e_delivery_phone),
               mail_address = coalesce(?, mail_address),
               mail_recipient = coalesce(?, mail_recipient),
               mail_phone = coalesce(?, mail_phone),
               note = coalesce(?, note),
               updated_at = now()
         where id = ?
        """,
        req.name(),
        req.idNo(),
        req.unifiedCode(),
        req.regAddress(),
        req.eDeliveryPhone(),
        req.mailAddress(),
        req.mailRecipient(),
        req.mailPhone(),
        req.note(),
        debtorId
    );
  }

  public void deleteDebtor(UUID debtorId) {
    jdbc.update("delete from project_debtor where id=?", debtorId);
  }

  public boolean creditorBelongsToProject(UUID creditorId, UUID projectId) {
    Integer n = jdbc.queryForObject(
        "select count(1) from project_creditor where id=? and project_id=?",
        Integer.class,
        creditorId,
        projectId
    );
    return n != null && n > 0;
  }

  public boolean debtorBelongsToProject(UUID debtorId, UUID projectId) {
    Integer n = jdbc.queryForObject(
        "select count(1) from project_debtor where id=? and project_id=?",
        Integer.class,
        debtorId,
        projectId
    );
    return n != null && n > 0;
  }

  public Optional<Map<String, Object>> findCaseBase(UUID caseId) {
    var rows = jdbc.queryForList("select id, group_id, project_id from \"case\" where id=?", caseId);
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateCase(UUID caseId, UpdateWorkbenchCaseRequest req) {
    jdbc.update(
        """
        update "case"
           set creditor_id = coalesce(?, creditor_id),
               debtor_id = coalesce(?, debtor_id),
               cause = coalesce(?, cause),
               basis_doc_type = coalesce(?, basis_doc_type),
               basis_doc_no = coalesce(?, basis_doc_no),
               basis_org = coalesce(?, basis_org),
               basis_decided_at = coalesce(?, basis_decided_at),
               basis_main_text = coalesce(?, basis_main_text),
               updated_at = now()
         where id = ?
        """,
        req.creditorId(),
        req.debtorId(),
        req.cause(),
        req.basisDocType(),
        req.basisDocNo(),
        req.basisOrg(),
        req.basisDecidedAt(),
        req.basisMainText(),
        caseId
    );
  }

  public void insertWorkbenchCase(UUID caseId,
                                 UUID groupId,
                                 UUID projectId,
                                 String code,
                                 java.time.LocalDate acceptedAt,
                                 String title,
                                 UUID creditorId,
                                 UUID debtorId,
                                 String cause,
                                 String basisDocType,
                                 String basisDocNo,
                                 String basisOrg,
                                 java.time.LocalDate basisDecidedAt,
                                 String basisMainText,
                                 UUID actorUserId) {
    jdbc.update(
        """
        insert into "case"(
          id, group_id, project_id, code, accepted_at, title, status,
          creditor_id, debtor_id,
          cause, basis_doc_type, basis_doc_no, basis_org, basis_decided_at, basis_main_text,
          created_by
        )
        values (?,?,?,?,?, ?, 'OPEN', ?,?, ?,?,?,?, ?,?, ?)
        """,
        caseId,
        groupId,
        projectId,
        code,
        acceptedAt,
        title,
        creditorId,
        debtorId,
        cause,
        basisDocType,
        basisDocNo,
        basisOrg,
        basisDecidedAt,
        basisMainText,
        actorUserId
    );
  }

  public List<DebtorClueDto> listClues(UUID debtorId) {
    return jdbc.query(
        """
        select dc.id, dc.xs_code, dc.category, dc.detail, dc.source, dc.collected_at,
               dc.collector_user_id, u.username as collector_name, dc.created_at, dc.updated_at
          from debtor_clue dc
          left join app_user u on u.id = dc.collector_user_id
         where dc.debtor_id = ?
         order by dc.created_at desc
        """,
        ps -> ps.setObject(1, debtorId),
        (rs, rowNum) -> new DebtorClueDto(
            rs.getObject("id", UUID.class),
            rs.getString("xs_code"),
            rs.getString("category"),
            rs.getString("detail"),
            rs.getString("source"),
            rs.getDate("collected_at") == null ? null : rs.getDate("collected_at").toLocalDate(),
            rs.getObject("collector_user_id", UUID.class),
            rs.getString("collector_name"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public void insertClue(UUID clueId, UUID groupId, UUID debtorId, String xsCode, CreateClueRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into debtor_clue(
          id, group_id, debtor_id, xs_code,
          category, detail, source,
          collected_at, collector_user_id,
          created_by
        )
        values (?,?,?,?, ?,?,?, ?,?, ?)
        """,
        clueId, groupId, debtorId, xsCode,
        req.category(), req.detail(), req.source(),
        req.collectedAt() == null ? null : Date.valueOf(req.collectedAt()),
        req.collectorUserId(),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findClueBase(UUID clueId) {
    var rows = jdbc.queryForList(
        """
        select dc.id, dc.group_id, dc.debtor_id, d.project_id
          from debtor_clue dc
          join project_debtor d on d.id = dc.debtor_id
         where dc.id = ?
        """,
        clueId
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateClue(UUID clueId, UpdateClueRequest req) {
    jdbc.update(
        """
        update debtor_clue
           set category = coalesce(?, category),
               detail = coalesce(?, detail),
               source = coalesce(?, source),
               collected_at = coalesce(?, collected_at),
               collector_user_id = coalesce(?, collector_user_id),
               updated_at = now()
         where id = ?
        """,
        req.category(),
        req.detail(),
        req.source(),
        req.collectedAt() == null ? null : Date.valueOf(req.collectedAt()),
        req.collectorUserId(),
        clueId
    );
  }

  public void deleteClue(UUID clueId) {
    jdbc.update("delete from debtor_clue where id=?", clueId);
  }

  public List<AttachmentLinkDto> listAttachments(String objectType, UUID objectId) {
    return jdbc.query(
        """
        select al.id, al.object_type, al.object_id, al.file_id, al.title, al.created_at,
               fs.filename, fs.content_type, fs.size_bytes
          from attachment_link al
          join file_store fs on fs.id = al.file_id
         where al.object_type = ?
           and al.object_id = ?
         order by al.created_at desc
        """,
        ps -> {
          ps.setString(1, objectType);
          ps.setObject(2, objectId);
        },
        (rs, rowNum) -> new AttachmentLinkDto(
            rs.getObject("id", UUID.class),
            rs.getString("object_type"),
            rs.getObject("object_id", UUID.class),
            rs.getObject("file_id", UUID.class),
            rs.getString("title"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getString("filename"),
            rs.getString("content_type"),
            rs.getLong("size_bytes")
        )
    );
  }

  public Optional<UUID> resolveGroupIdForObject(String objectType, UUID objectId) {
    String t = normalizeObjectType(objectType);
    return switch (t) {
      case "project" -> jdbc.queryForList("select group_id from project where id=?", UUID.class, objectId).stream().findFirst();
      case "case" -> jdbc.queryForList("select group_id from \"case\" where id=?", UUID.class, objectId).stream().findFirst();
      case "project_creditor" -> jdbc.queryForList("select group_id from project_creditor where id=?", UUID.class, objectId).stream().findFirst();
      case "project_debtor" -> jdbc.queryForList("select group_id from project_debtor where id=?", UUID.class, objectId).stream().findFirst();
      case "debtor_clue" -> jdbc.queryForList("select group_id from debtor_clue where id=?", UUID.class, objectId).stream().findFirst();
      case "case_procedure" -> jdbc.queryForList("select group_id from case_procedure where id=?", UUID.class, objectId).stream().findFirst();
      case "case_measure_control" -> jdbc.queryForList("select group_id from case_measure_control where id=?", UUID.class, objectId).stream().findFirst();
      case "case_measure_sanction" -> jdbc.queryForList("select group_id from case_measure_sanction where id=?", UUID.class, objectId).stream().findFirst();
      case "case_cost" -> jdbc.queryForList("select group_id from case_cost where id=?", UUID.class, objectId).stream().findFirst();
      default -> Optional.empty();
    };
  }

  public Optional<UUID> resolveProjectIdForObject(String objectType, UUID objectId) {
    String t = normalizeObjectType(objectType);
    return switch (t) {
      case "project" -> Optional.of(objectId);
      case "case" -> jdbc.queryForList("select project_id from \"case\" where id=?", UUID.class, objectId).stream().findFirst();
      case "project_creditor" -> jdbc.queryForList("select project_id from project_creditor where id=?", UUID.class, objectId).stream().findFirst();
      case "project_debtor" -> jdbc.queryForList("select project_id from project_debtor where id=?", UUID.class, objectId).stream().findFirst();
      case "debtor_clue" -> jdbc.queryForList(
          """
          select d.project_id
            from debtor_clue dc
            join project_debtor d on d.id = dc.debtor_id
           where dc.id = ?
          """,
          UUID.class,
          objectId
      ).stream().findFirst();
      case "case_procedure" -> jdbc.queryForList(
          """
          select c.project_id
            from case_procedure cp
            join \"case\" c on c.id = cp.case_id
           where cp.id = ?
          """,
          UUID.class,
          objectId
      ).stream().findFirst();
      case "case_measure_control" -> jdbc.queryForList(
          """
          select c.project_id
            from case_measure_control m
            join \"case\" c on c.id = m.case_id
           where m.id = ?
          """,
          UUID.class,
          objectId
      ).stream().findFirst();
      case "case_measure_sanction" -> jdbc.queryForList(
          """
          select c.project_id
            from case_measure_sanction m
            join \"case\" c on c.id = m.case_id
           where m.id = ?
          """,
          UUID.class,
          objectId
      ).stream().findFirst();
      case "case_cost" -> jdbc.queryForList(
          """
          select c.project_id
            from case_cost cc
            join \"case\" c on c.id = cc.case_id
           where cc.id = ?
          """,
          UUID.class,
          objectId
      ).stream().findFirst();
      default -> Optional.empty();
    };
  }

  private String normalizeObjectType(String objectType) {
    return String.valueOf(objectType == null ? "" : objectType).trim().toLowerCase();
  }

  public record FileRow(UUID fileId, UUID projectId, UUID caseId, String status) {
  }

  public Optional<FileRow> findFile(UUID fileId) {
    var rows = jdbc.query(
        "select id, project_id, case_id, status from file_store where id=?",
        ps -> ps.setObject(1, fileId),
        (rs, rowNum) -> new FileRow(
            rs.getObject("id", UUID.class),
            rs.getObject("project_id", UUID.class),
            rs.getObject("case_id", UUID.class),
            rs.getString("status")
        )
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void insertAttachment(UUID attachmentId, UUID groupId, CreateAttachmentLinkRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into attachment_link(id, group_id, object_type, object_id, file_id, title, created_by)
        values (?,?,?,?,?,?,?)
        """,
        attachmentId,
        groupId,
        normalizeObjectType(req.objectType()),
        req.objectId(),
        req.fileId(),
        req.title(),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findAttachmentBase(UUID attachmentId) {
    var rows = jdbc.queryForList(
        """
        select al.id, al.group_id,
               case
                 when al.object_type='project' then al.object_id
                 when al.object_type='case' then (select c.project_id from \"case\" c where c.id = al.object_id)
                 when al.object_type='project_creditor' then (select pc.project_id from project_creditor pc where pc.id = al.object_id)
                 when al.object_type='project_debtor' then (select pd.project_id from project_debtor pd where pd.id = al.object_id)
                 when al.object_type='debtor_clue' then (
                   select d.project_id
                     from debtor_clue dc
                     join project_debtor d on d.id = dc.debtor_id
                    where dc.id = al.object_id
                 )
                 when al.object_type='case_procedure' then (
                   select c.project_id
                     from case_procedure cp
                     join \"case\" c on c.id = cp.case_id
                    where cp.id = al.object_id
                 )
                 when al.object_type='case_measure_control' then (
                   select c.project_id
                     from case_measure_control m
                     join \"case\" c on c.id = m.case_id
                    where m.id = al.object_id
                 )
                 when al.object_type='case_measure_sanction' then (
                   select c.project_id
                     from case_measure_sanction m
                     join \"case\" c on c.id = m.case_id
                    where m.id = al.object_id
                 )
                 when al.object_type='case_cost' then (
                   select c.project_id
                     from case_cost cc
                     join \"case\" c on c.id = cc.case_id
                    where cc.id = al.object_id
                 )
                 else null
               end as project_id
          from attachment_link al
         where al.id = ?
        """,
        attachmentId
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void deleteAttachment(UUID attachmentId) {
    jdbc.update("delete from attachment_link where id=?", attachmentId);
  }

  public List<CaseProcedureDto> listProcedures(UUID caseId) {
    return jdbc.query(
        """
        select id, name, doc_no, org, decided_at, created_at, updated_at
          from case_procedure
         where case_id = ?
         order by created_at desc
        """,
        ps -> ps.setObject(1, caseId),
        (rs, rowNum) -> new CaseProcedureDto(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("doc_no"),
            rs.getString("org"),
            rs.getDate("decided_at") == null ? null : rs.getDate("decided_at").toLocalDate(),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public void insertProcedure(UUID procedureId, UUID groupId, UUID caseId, CreateCaseProcedureRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into case_procedure(id, group_id, case_id, name, doc_no, org, decided_at, created_by)
        values (?,?,?,?,?,?,?,?)
        """,
        procedureId,
        groupId,
        caseId,
        req.name(),
        req.docNo(),
        req.org(),
        req.decidedAt() == null ? null : Date.valueOf(req.decidedAt()),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findProcedureBase(UUID procedureId) {
    var rows = jdbc.queryForList(
        """
        select cp.id, cp.group_id, cp.case_id, c.project_id
          from case_procedure cp
          join \"case\" c on c.id = cp.case_id
         where cp.id = ?
        """,
        procedureId
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateProcedure(UUID procedureId, UpdateCaseProcedureRequest req) {
    jdbc.update(
        """
        update case_procedure
           set name = coalesce(?, name),
               doc_no = coalesce(?, doc_no),
               org = coalesce(?, org),
               decided_at = coalesce(?, decided_at),
               updated_at = now()
         where id = ?
        """,
        req.name(),
        req.docNo(),
        req.org(),
        req.decidedAt() == null ? null : Date.valueOf(req.decidedAt()),
        procedureId
    );
  }

  public void deleteProcedure(UUID procedureId) {
    jdbc.update("delete from case_procedure where id=?", procedureId);
  }

  public List<CaseMeasureDto> listControlMeasures(UUID caseId) {
    return jdbc.query(
        """
        select id, name, target, basis_org, basis_doc_no, basis_doc_name,
               content, result, rank_no, due_at, note, created_at, updated_at
          from case_measure_control
         where case_id = ?
         order by created_at desc
        """,
        ps -> ps.setObject(1, caseId),
        (rs, rowNum) -> new CaseMeasureDto(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("target"),
            rs.getString("basis_org"),
            rs.getString("basis_doc_no"),
            rs.getString("basis_doc_name"),
            rs.getString("content"),
            rs.getString("result"),
            rs.getString("rank_no"),
            rs.getDate("due_at") == null ? null : rs.getDate("due_at").toLocalDate(),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public void insertControlMeasure(UUID id, UUID groupId, UUID caseId, CreateCaseMeasureRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into case_measure_control(
          id, group_id, case_id, name, target,
          basis_org, basis_doc_no, basis_doc_name,
          content, result, rank_no, due_at, note,
          created_by
        )
        values (?,?,?,?,?, ?,?,?, ?,?,?,?, ?,?)
        """,
        id, groupId, caseId, req.name(), req.target(),
        req.basisOrg(), req.basisDocNo(), req.basisDocName(),
        req.content(), req.result(), req.rankNo(),
        req.dueAt() == null ? null : Date.valueOf(req.dueAt()),
        req.note(),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findControlMeasureBase(UUID id) {
    var rows = jdbc.queryForList(
        """
        select m.id, m.group_id, m.case_id, c.project_id
          from case_measure_control m
          join \"case\" c on c.id = m.case_id
         where m.id = ?
        """,
        id
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateControlMeasure(UUID id, UpdateCaseMeasureRequest req) {
    jdbc.update(
        """
        update case_measure_control
           set name = coalesce(?, name),
               target = coalesce(?, target),
               basis_org = coalesce(?, basis_org),
               basis_doc_no = coalesce(?, basis_doc_no),
               basis_doc_name = coalesce(?, basis_doc_name),
               content = coalesce(?, content),
               result = coalesce(?, result),
               rank_no = coalesce(?, rank_no),
               due_at = coalesce(?, due_at),
               note = coalesce(?, note),
               updated_at = now()
         where id = ?
        """,
        req.name(),
        req.target(),
        req.basisOrg(),
        req.basisDocNo(),
        req.basisDocName(),
        req.content(),
        req.result(),
        req.rankNo(),
        req.dueAt() == null ? null : Date.valueOf(req.dueAt()),
        req.note(),
        id
    );
  }

  public void deleteControlMeasure(UUID id) {
    jdbc.update("delete from case_measure_control where id=?", id);
  }

  public List<CaseMeasureDto> listSanctionMeasures(UUID caseId) {
    return jdbc.query(
        """
        select id, name, target, basis_org, basis_doc_no, basis_doc_name,
               content, result, null::varchar as rank_no, due_at, note, created_at, updated_at
          from case_measure_sanction
         where case_id = ?
         order by created_at desc
        """,
        ps -> ps.setObject(1, caseId),
        (rs, rowNum) -> new CaseMeasureDto(
            rs.getObject("id", UUID.class),
            rs.getString("name"),
            rs.getString("target"),
            rs.getString("basis_org"),
            rs.getString("basis_doc_no"),
            rs.getString("basis_doc_name"),
            rs.getString("content"),
            rs.getString("result"),
            rs.getString("rank_no"),
            rs.getDate("due_at") == null ? null : rs.getDate("due_at").toLocalDate(),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public void insertSanctionMeasure(UUID id, UUID groupId, UUID caseId, CreateCaseMeasureRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into case_measure_sanction(
          id, group_id, case_id, name, target,
          basis_org, basis_doc_no, basis_doc_name,
          content, result, due_at, note,
          created_by
        )
        values (?,?,?,?,?, ?,?,?, ?,?,?, ?,?)
        """,
        id, groupId, caseId, req.name(), req.target(),
        req.basisOrg(), req.basisDocNo(), req.basisDocName(),
        req.content(), req.result(),
        req.dueAt() == null ? null : Date.valueOf(req.dueAt()),
        req.note(),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findSanctionMeasureBase(UUID id) {
    var rows = jdbc.queryForList(
        """
        select m.id, m.group_id, m.case_id, c.project_id
          from case_measure_sanction m
          join \"case\" c on c.id = m.case_id
         where m.id = ?
        """,
        id
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateSanctionMeasure(UUID id, UpdateCaseMeasureRequest req) {
    jdbc.update(
        """
        update case_measure_sanction
           set name = coalesce(?, name),
               target = coalesce(?, target),
               basis_org = coalesce(?, basis_org),
               basis_doc_no = coalesce(?, basis_doc_no),
               basis_doc_name = coalesce(?, basis_doc_name),
               content = coalesce(?, content),
               result = coalesce(?, result),
               due_at = coalesce(?, due_at),
               note = coalesce(?, note),
               updated_at = now()
         where id = ?
        """,
        req.name(),
        req.target(),
        req.basisOrg(),
        req.basisDocNo(),
        req.basisDocName(),
        req.content(),
        req.result(),
        req.dueAt() == null ? null : Date.valueOf(req.dueAt()),
        req.note(),
        id
    );
  }

  public void deleteSanctionMeasure(UUID id) {
    jdbc.update("delete from case_measure_sanction where id=?", id);
  }

  public List<CaseCostDto> listCosts(UUID caseId) {
    return jdbc.query(
        """
        select id, category, amount, occurred_at, payer, note, created_at, updated_at
          from case_cost
         where case_id = ?
         order by occurred_at desc nulls last, created_at desc
        """,
        ps -> ps.setObject(1, caseId),
        (rs, rowNum) -> new CaseCostDto(
            rs.getObject("id", UUID.class),
            rs.getString("category"),
            rs.getBigDecimal("amount"),
            rs.getDate("occurred_at") == null ? null : rs.getDate("occurred_at").toLocalDate(),
            rs.getString("payer"),
            rs.getString("note"),
            rs.getTimestamp("created_at").toInstant(),
            rs.getTimestamp("updated_at").toInstant()
        )
    );
  }

  public void insertCost(UUID costId, UUID groupId, UUID caseId, CreateCaseCostRequest req, UUID actorUserId) {
    jdbc.update(
        """
        insert into case_cost(id, group_id, case_id, category, amount, occurred_at, payer, note, created_by)
        values (?,?,?,?, ?,?,?,?, ?)
        """,
        costId,
        groupId,
        caseId,
        req.category(),
        req.amount(),
        req.occurredAt() == null ? null : Date.valueOf(req.occurredAt()),
        req.payer(),
        req.note(),
        actorUserId
    );
  }

  public Optional<Map<String, Object>> findCostBase(UUID costId) {
    var rows = jdbc.queryForList(
        """
        select cc.id, cc.group_id, cc.case_id, c.project_id
          from case_cost cc
          join \"case\" c on c.id = cc.case_id
         where cc.id = ?
        """,
        costId
    );
    return rows.isEmpty() ? Optional.empty() : Optional.of(rows.getFirst());
  }

  public void updateCost(UUID costId, UpdateCaseCostRequest req) {
    jdbc.update(
        """
        update case_cost
           set category = coalesce(?, category),
               amount = coalesce(?, amount),
               occurred_at = coalesce(?, occurred_at),
               payer = coalesce(?, payer),
               note = coalesce(?, note),
               updated_at = now()
         where id = ?
        """,
        req.category(),
        req.amount(),
        req.occurredAt() == null ? null : Date.valueOf(req.occurredAt()),
        req.payer(),
        req.note(),
        costId
    );
  }

  public void deleteCost(UUID costId) {
    jdbc.update("delete from case_cost where id=?", costId);
  }
}

