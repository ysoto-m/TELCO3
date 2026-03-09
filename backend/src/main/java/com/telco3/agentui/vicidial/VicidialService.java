package com.telco3.agentui.vicidial;

import com.telco3.agentui.agent.VicidialCredentialService;
import com.telco3.agentui.domain.Entities;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.env.Environment;
import org.springframework.dao.DataAccessException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Service;
import org.springframework.util.StringUtils;

import java.util.Arrays;
import java.util.LinkedHashMap;
import java.util.Locale;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

@Service
public class VicidialService {
  private static final Logger log = LoggerFactory.getLogger(VicidialService.class);
  private static final int CONF_EXTEN_CHECK_MAX_ATTEMPTS = 20;
  private static final long CONF_EXTEN_CHECK_WAIT_MS = 400L;

  private final VicidialClient client;
  private final VicidialCredentialService credentialService;
  private final Environment environment;
  private final VicidialRuntimeDataSourceFactory dataSourceFactory;

  public VicidialService(VicidialClient client, VicidialDialResponseParser dialResponseParser, VicidialCredentialService credentialService,
                         Environment environment, VicidialRuntimeDataSourceFactory dataSourceFactory) {
    this.client = client;
    this.credentialService = credentialService;
    this.environment = environment;
    this.dataSourceFactory = dataSourceFactory;
  }

  public String resolveModeForCampaign(String appUsername, String campaignId) {
    String mode = campaignMode(campaignId).mode();
    credentialService.updateSessionMode(appUsername, mode);
    if (isDevEnvironment()) {
      log.info("Vicidial campaign mode resolved agent={} campaign={} mode={}", appUsername, campaignId, mode);
    }
    return mode;
  }

  public CampaignMode campaignMode(String campaignId) {
    JdbcTemplate jdbc = new JdbcTemplate(dataSourceFactory.getOrCreate());
    Map<String, Object> row = jdbc.query(
        "SELECT campaign_id, dial_method FROM vicidial_campaigns WHERE campaign_id = ?",
        rs -> rs.next() ? Map.of("campaign_id", rs.getString("campaign_id"), "dial_method", rs.getString("dial_method")) : null,
        campaignId
    );
    if (row == null) {
      throw new VicidialServiceException(org.springframework.http.HttpStatus.NOT_FOUND,
          "VICIDIAL_CAMPAIGN_NOT_FOUND", "No se encontro la campana en Vicidial.");
    }
    String dialMethodRaw = Objects.toString(row.get("dial_method"), "");
    String mode = mapDialMethodToMode(dialMethodRaw);
    return new CampaignMode(Objects.toString(row.get("campaign_id"), campaignId), dialMethodRaw, mode);
  }

  public Optional<String> resolveManualDialListId(String campaignId) {
    if (!StringUtils.hasText(campaignId)) {
      return Optional.empty();
    }
    JdbcTemplate jdbc;
    try {
      jdbc = new JdbcTemplate(dataSourceFactory.getOrCreate());
    } catch (Exception ex) {
      log.warn("Vicidial runtime datasource unavailable for list_id cause={}", ex.getClass().getSimpleName());
      return Optional.empty();
    }
    String sql = """
        SELECT list_id
          FROM vicidial_lists
         WHERE campaign_id = ?
         ORDER BY CASE WHEN active = 'Y' THEN 0 ELSE 1 END, list_id
         LIMIT 1
        """;
    try {
      String listId = jdbc.query(sql, rs -> rs.next() ? rs.getString("list_id") : null, campaignId);
      return Optional.ofNullable(firstNonBlank(listId));
    } catch (DataAccessException ex) {
      log.warn("Vicidial list_id lookup failed campaign={} cause={}", campaignId, ex.getClass().getSimpleName());
      return Optional.empty();
    }
  }

  public String mapDialMethodToMode(String dialMethod) {
    String normalized = Objects.toString(dialMethod, "").trim().toUpperCase(Locale.ROOT);
    if (normalized.contains("MANUAL")) {
      return "manual";
    }
    return "predictive";
  }

  public DialNextResult dialNextWithLeadRetry(String agentUser, Entities.AgentVicidialCredentialEntity session, String rawBody, String callId, Long leadId) {
    RealtimeCallSnapshot snapshot = resolveRealtimeCallSnapshot(agentUser, session, callId, leadId, null, session.connectedCampaign, true);
    Long resolvedLeadId = snapshot.leadId();
    String resolvedCallId = firstNonBlank(snapshot.callId(), callId);
    if (resolvedLeadId == null) {
      credentialService.updateDialRuntime(agentUser, "DIALING", resolvedCallId, null);
      return new DialNextResult(resolvedCallId, null, snapshot.classification());
    }
    credentialService.updateDialRuntime(agentUser, "ACTIVE", resolvedCallId, resolvedLeadId);
    return new DialNextResult(resolvedCallId, resolvedLeadId, snapshot.classification());
  }

  public DialNextResult resolveManualDialLead(String agentUser, Entities.AgentVicidialCredentialEntity session, String callId, Long leadId, String phoneNumber) {
    RealtimeCallSnapshot snapshot = resolveRealtimeCallSnapshot(agentUser, session, callId, leadId, phoneNumber, session.connectedCampaign, true);
    Long resolvedLeadId = snapshot.leadId();
    String resolvedCallId = firstNonBlank(snapshot.callId(), callId);
    String classification = snapshot.classification();
    if (resolvedLeadId == null) {
      classification = isPausedStatus(snapshot.agentStatus()) ? "DIALING_AGENT_PAUSED" : classification;
      credentialService.updateDialRuntime(agentUser, "DIALING", resolvedCallId, null);
      return new DialNextResult(resolvedCallId, null, classification);
    }
    credentialService.updateDialRuntime(agentUser, "ACTIVE", resolvedCallId, resolvedLeadId);
    return new DialNextResult(resolvedCallId, resolvedLeadId, classification);
  }

  public DialFollowUpResult followUpManualDial(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      VicidialDialResponseParser.DetailedParsedDialResponse parsedResponse,
      String requestedPhoneNumber
  ) {
    Map<String, Object> details = new LinkedHashMap<>();
    Map<String, String> basePayload = buildAgentRuntimePayload(agentUser, agentPass, session, campaignId);

    var scriptDisplayResult = client.vdcScriptDisplay(agentUser, basePayload);
    details.put("scriptDisplayHttpStatus", scriptDisplayResult.statusCode());

    String callId = firstNonBlank(parsedResponse.callId(), session.currentCallId);
    Long leadId = parsedResponse.leadId();
    String phoneNumber = firstNonBlank(parsedResponse.phoneNumber(), requestedPhoneNumber);
    String listId = parsedResponse.listId();
    listId = firstNonBlank(listId, basePayload.get("list_id"));
    String leadStatus = parsedResponse.leadStatus();
    String normalizedConfExten = normalizeConfExten(session.confExten, session.connectedPhoneLogin);
    String normalizedMonitorExten = normalizeEndpointDigits(resolveDialExten(session));
    String agentStatus = null;
    String uniqueId = null;
    String channel = null;

    boolean incallConfirmed = false;
    boolean lookCallConsistent = false;
    for (int attempt = 1; attempt <= CONF_EXTEN_CHECK_MAX_ATTEMPTS; attempt++) {
      Map<String, String> confPayload = buildConfExtenCheckPayload(basePayload, callId, phoneNumber, attempt - 1, (attempt % 3) == 0);
      var confResult = client.confExtenCheck(agentUser, confPayload);
      Map<String, String> confParsed = client.parseKeyValueLines(confResult.body());
      String confStatus = firstNonBlank(extractAgentStatus(confParsed), extractAgentStatusFromBody(confResult.body()));
      if (StringUtils.hasText(confStatus)) {
        agentStatus = confStatus;
      }
      callId = firstNonBlank(callId, firstPresent(confParsed, "call_id", "callid", "callerid"));
      leadId = firstNonNull(leadId, toLong(firstPresent(confParsed, "lead_id", "leadid")));
      phoneNumber = firstNonBlank(phoneNumber, firstPresent(confParsed, "phone_number", "phonenumber"));
      listId = firstNonBlank(listId, firstPresent(confParsed, "list_id", "listid"));
      leadStatus = firstNonBlank(leadStatus, firstPresent(confParsed, "lead_status", "leadstatus", "status"));
      uniqueId = firstNonBlank(uniqueId, firstPresent(confParsed, "uniqueid", "unique_id"));
      channel = selectBestChannel(
          channel,
          firstPresent(confParsed, "channel"),
          session.connectedPhoneLogin,
          normalizedConfExten,
          normalizedMonitorExten
      );
      details.put("confExtenCheckAttempt", attempt);
      details.put("confExtenCheckHttpStatus", confResult.statusCode());
      details.put("confAgentStatus", confStatus);

      if (StringUtils.hasText(callId) || leadId != null || StringUtils.hasText(phoneNumber)) {
        Map<String, String> lookProbePayload = buildLookCallPayload(basePayload, callId, leadId, phoneNumber);
        var lookProbeResult = client.manualDialLookCall(agentUser, lookProbePayload);
        details.put("lookCallProbeHttpStatus", lookProbeResult.statusCode());
        Map<String, String> lookProbeParsed = client.parseKeyValueLines(lookProbeResult.body());
        String probeCallId = firstPresent(lookProbeParsed, "call_id", "callid", "callerid");
        Long probeLeadId = toLong(firstPresent(lookProbeParsed, "lead_id", "leadid"));
        String probeUniqueId = firstNonBlank(
            firstPresent(lookProbeParsed, "uniqueid", "unique_id"),
            extractUniqueIdFromBody(lookProbeResult.body())
        );
        String probeChannel = firstNonBlank(
            firstPresent(lookProbeParsed, "channel"),
            extractChannelFromBody(lookProbeResult.body())
        );
        if (!isOutboundCustomerChannel(probeChannel, session.connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten)) {
          probeChannel = firstNonBlank(
              extractBestOutboundChannelFromBody(
                  lookProbeResult.body(),
                  session.connectedPhoneLogin,
                  normalizedConfExten,
                  normalizedMonitorExten
              ),
              probeChannel
          );
        }
        callId = firstNonBlank(callId, probeCallId);
        leadId = firstNonNull(leadId, probeLeadId);
        uniqueId = firstNonBlank(uniqueId, probeUniqueId);
        channel = selectBestChannel(
            channel,
            probeChannel,
            session.connectedPhoneLogin,
            normalizedConfExten,
            normalizedMonitorExten
        );
        phoneNumber = firstNonBlank(phoneNumber, firstPresent(lookProbeParsed, "phone_number", "phonenumber"));
        leadStatus = firstNonBlank(leadStatus, firstPresent(lookProbeParsed, "status", "lead_status"));
        boolean leadMatch = probeLeadId != null && leadId != null && Objects.equals(probeLeadId, leadId);
        boolean probeHasOutboundChannel = isOutboundCustomerChannel(
            probeChannel,
            session.connectedPhoneLogin,
            normalizedConfExten,
            normalizedMonitorExten
        );
        boolean probeHasMediaEvidence = probeHasOutboundChannel && (StringUtils.hasText(probeUniqueId) || StringUtils.hasText(probeChannel));
        boolean probeHasIdentity = StringUtils.hasText(probeCallId) || probeLeadId != null;
        if (StringUtils.hasText(callId) && probeHasMediaEvidence && (leadMatch || probeHasIdentity)) {
          lookCallConsistent = true;
        }
      }

      if (hasDialEvidence(
          callId,
          leadId,
          uniqueId,
          channel,
          confStatus,
          lookCallConsistent,
          session.connectedPhoneLogin,
          normalizedConfExten,
          normalizedMonitorExten
      )) {
        incallConfirmed = true;
        break;
      }
      sleepQuietly(CONF_EXTEN_CHECK_WAIT_MS);
    }

    if (!incallConfirmed) {
      RealtimeCallSnapshot snapshot = resolveRealtimeCallSnapshot(agentUser, agentPass, session, callId, leadId, phoneNumber, campaignId, true);
      callId = firstNonBlank(callId, snapshot.callId());
      leadId = firstNonNull(leadId, snapshot.leadId());
      phoneNumber = firstNonBlank(phoneNumber, snapshot.phoneNumber());
      agentStatus = firstNonBlank(agentStatus, snapshot.agentStatus());
      uniqueId = firstNonBlank(uniqueId, snapshot.uniqueId());
      channel = selectBestChannel(
          channel,
          snapshot.channel(),
          session.connectedPhoneLogin,
          normalizedConfExten,
          normalizedMonitorExten
      );
      details.put("snapshotClassification", snapshot.classification());
      details.put("snapshotHttpStatus", snapshot.httpStatus());
      if (snapshot.details() != null && !snapshot.details().isEmpty()) {
        details.putAll(snapshot.details());
      }
      if (hasDialEvidence(
          callId,
          leadId,
          uniqueId,
          channel,
          agentStatus,
          true,
          session.connectedPhoneLogin,
          normalizedConfExten,
          normalizedMonitorExten
      )) {
        incallConfirmed = true;
      }
    }

    if (isAgentLoopChannel(channel, session.connectedPhoneLogin)) {
      incallConfirmed = false;
      details.put("outboundLeg", "AGENT_LOOP_DETECTED");
      details.put("outboundChannel", channel);
    }
    if (!isOutboundCustomerChannel(channel, session.connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten)
        && !details.containsKey("outboundLeg")) {
      incallConfirmed = false;
      details.put("outboundLeg", "OUTBOUND_CHANNEL_NOT_DETECTED");
      details.put("outboundChannel", channel);
    }

    if (!incallConfirmed) {
      String pendingCallId = firstNonBlank(callId, session.currentCallId);
      credentialService.updateDialRuntime(agentUser, "DIALING", pendingCallId, leadId);
      String classification = isPausedStatus(agentStatus)
          ? "AGENT_PAUSED"
          : (StringUtils.hasText(pendingCallId) ? "DIALING_PENDING_CONFIRMATION" : "INCALL_NOT_CONFIRMED");
      return new DialFollowUpResult(false, classification, pendingCallId, leadId, uniqueId, channel, phoneNumber, listId, leadStatus, agentStatus, details);
    }

    Map<String, String> lookPayload = buildLookCallPayload(basePayload, callId, leadId, phoneNumber);
    var lookResult = client.manualDialLookCall(agentUser, lookPayload);
    details.put("lookCallHttpStatus", lookResult.statusCode());
    Map<String, String> lookParsed = client.parseKeyValueLines(lookResult.body());
    callId = firstNonBlank(callId, firstPresent(lookParsed, "call_id", "callid", "callerid"));
    leadId = firstNonNull(leadId, toLong(firstPresent(lookParsed, "lead_id", "leadid")));
    phoneNumber = firstNonBlank(phoneNumber, firstPresent(lookParsed, "phone_number", "phonenumber"));
    uniqueId = firstNonBlank(uniqueId, firstPresent(lookParsed, "uniqueid", "unique_id"), extractUniqueIdFromBody(lookResult.body()));
    String lookChannel = firstNonBlank(firstPresent(lookParsed, "channel"), extractChannelFromBody(lookResult.body()));
    if (!isOutboundCustomerChannel(lookChannel, session.connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten)) {
      lookChannel = firstNonBlank(
          extractBestOutboundChannelFromBody(
              lookResult.body(),
              session.connectedPhoneLogin,
              normalizedConfExten,
              normalizedMonitorExten
          ),
          lookChannel
      );
    }
    channel = selectBestChannel(channel, lookChannel, session.connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten);
    leadStatus = firstNonBlank(leadStatus, firstPresent(lookParsed, "status", "lead_status"));

    var runtimeLead = resolveLeadFromRuntimeTables(session, callId, phoneNumber);
    leadId = firstNonNull(leadId, runtimeLead.leadId());
    phoneNumber = firstNonBlank(phoneNumber, runtimeLead.phoneNumber());
    String runtimeCampaign = runtimeLead.campaign();
    if (!StringUtils.hasText(agentStatus)) {
      agentStatus = runtimeLead.agentStatus();
    }
    uniqueId = firstNonBlank(uniqueId, Objects.toString(runtimeLead.details().get("uniqueId"), null));
    channel = selectBestChannel(
        channel,
        Objects.toString(runtimeLead.details().get("channel"), null),
        session.connectedPhoneLogin,
        normalizedConfExten,
        normalizedMonitorExten
    );
    callId = firstNonBlank(callId, Objects.toString(runtimeLead.details().get("callerId"), null));

    Map<String, String> monitorPayload = buildMonitorConfPayload(basePayload, session, callId, leadId, uniqueId, channel, phoneNumber);
    var monitorResult = client.monitorConf(agentUser, monitorPayload);
    details.put("monitorConfHttpStatus", monitorResult.statusCode());

    Map<String, String> logStartPayload = buildLogPayload(
        basePayload,
        session,
        callId,
        leadId,
        uniqueId,
        channel,
        phoneNumber,
        runtimeCampaign,
        listId
    );
    var logStartResult = client.manualDialLogCall(agentUser, logStartPayload, "start");
    details.put("logStartHttpStatus", logStartResult.statusCode());

    String classification = leadId == null ? "INCALL_NO_LEAD_ID" : "READY";
    credentialService.updateDialRuntime(agentUser, leadId == null ? "DIALING" : "ACTIVE", callId, leadId);
    return new DialFollowUpResult(true, classification, callId, leadId, uniqueId, channel, phoneNumber, listId, leadStatus, agentStatus, details);
  }

  public void manualDialLogEnd(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      Long leadId,
      String dispo
  ) {
    if (!StringUtils.hasText(agentPass)) {
      return;
    }
    String callId = firstNonBlank(session.currentCallId);
    Long resolvedLeadId = firstNonNull(leadId, session.currentLeadId);
    if (!StringUtils.hasText(callId) && resolvedLeadId == null) {
      return;
    }
    Map<String, String> payload = buildAgentRuntimePayload(agentUser, agentPass, session, campaignId);
    payload.putAll(buildLogPayload(payload, session, callId, resolvedLeadId, null, null, null, campaignId, null));
    if (StringUtils.hasText(dispo)) {
      payload.put("status", dispo);
    }
    client.manualDialLogCall(agentUser, payload, "end");
    credentialService.updateDialRuntime(agentUser, null, null, null);
  }

  public PostCallSyncResult syncPostCallDisposition(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      Long leadIdHint,
      String callIdHint,
      String uniqueIdHint,
      String phoneNumberHint,
      String dispo
  ) {
    Map<String, Object> details = new LinkedHashMap<>();
    if (!StringUtils.hasText(agentPass)) {
      return new PostCallSyncResult(false, null, "Missing agent_pass", details);
    }

    String resolvedCampaign = firstNonBlank(campaignId, session.connectedCampaign);
    Map<String, String> basePayload = buildAgentRuntimePayload(agentUser, agentPass, session, resolvedCampaign);
    RealtimeCallSnapshot snapshot = resolveRealtimeCallSnapshot(
        agentUser,
        agentPass,
        session,
        firstNonBlank(callIdHint, session.currentCallId),
        firstNonNull(leadIdHint, session.currentLeadId),
        phoneNumberHint,
        resolvedCampaign,
        true
    );

    String callId = firstNonBlank(callIdHint, snapshot.callId(), session.currentCallId);
    Long leadId = firstNonNull(leadIdHint, snapshot.leadId(), session.currentLeadId);
    String uniqueId = firstNonBlank(uniqueIdHint, snapshot.uniqueId());
    String channel = firstNonBlank(snapshot.channel());
    String phoneNumber = firstNonBlank(phoneNumberHint, snapshot.phoneNumber());
    String confExten = normalizeConfExten(session.confExten, session.connectedPhoneLogin);
    String listId = resolveManualDialListId(resolvedCampaign).orElse(null);
    String recordingFilename = buildRecordingFilename(phoneNumber, callId, uniqueId);

    details.put("callId", callId);
    details.put("leadId", leadId);
    details.put("uniqueId", uniqueId);
    details.put("channel", channel);
    details.put("campaign", resolvedCampaign);
    details.put("recordingFilename", recordingFilename);

    if (leadId == null) {
      return new PostCallSyncResult(false, recordingFilename, "Missing lead_id", details);
    }

    var callbacksResult = client.callbacksCount(agentUser, basePayload);
    details.put("callbacksCountHttpStatus", callbacksResult.statusCode());

    Map<String, String> logEndPayload = buildLogPayload(
        basePayload,
        session,
        callId,
        leadId,
        uniqueId,
        channel,
        phoneNumber,
        resolvedCampaign,
        listId
    );
    if (StringUtils.hasText(dispo)) {
      logEndPayload.put("status", dispo);
    }
    var logEndResult = client.manualDialLogCall(agentUser, logEndPayload, "end");
    details.put("logEndHttpStatus", logEndResult.statusCode());

    Map<String, String> updateLeadPayload = buildUpdateLeadPayload(basePayload, leadId, phoneNumber, resolvedCampaign);
    var updateLeadResult = client.updateLead(agentUser, updateLeadPayload);
    details.put("updateLeadHttpStatus", updateLeadResult.statusCode());

    Map<String, String> updateDispoPayload = buildUpdateDispoPayload(
        basePayload,
        session,
        resolvedCampaign,
        callId,
        leadId,
        uniqueId,
        channel,
        phoneNumber,
        listId,
        firstNonBlank(dispo, "N"),
        confExten
    );
    var updateDispoResult = client.updateDispo(agentUser, updateDispoPayload);
    details.put("updateDispoHttpStatus", updateDispoResult.statusCode());

    Map<String, String> runUrlsPayload = buildRunUrlsPayload(basePayload, resolvedCampaign);
    var runUrlsResult = client.runUrls(agentUser, runUrlsPayload);
    details.put("runUrlsHttpStatus", runUrlsResult.statusCode());

    var updateSettingsResult = client.updateSettings(agentUser, basePayload);
    details.put("updateSettingsHttpStatus", updateSettingsResult.statusCode());

    boolean synced = updateLeadResult.statusCode() < 400
        && updateDispoResult.statusCode() < 400
        && runUrlsResult.statusCode() < 400;
    if (synced) {
      credentialService.updateDialRuntime(agentUser, null, null, null);
      return new PostCallSyncResult(true, recordingFilename, null, details);
    }
    return new PostCallSyncResult(false, recordingFilename, "Post-call AGC sequence failed", details);
  }

  public LogoutFlowResult logoutAgentSession(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session
  ) {
    Map<String, Object> details = new LinkedHashMap<>();
    if (!StringUtils.hasText(agentPass)) {
      details.put("error", "MISSING_AGENT_PASS");
      return new LogoutFlowResult(false, false, false, details);
    }

    String campaign = firstNonBlank(session.connectedCampaign, "");
    Map<String, String> basePayload = buildAgentRuntimePayload(agentUser, agentPass, session, campaign);
    String mdNextCid = firstNonBlank(session.currentCallId, "");

    ConfCheckSnapshot confBefore = executeLogoutConfExtenCheck(agentUser, basePayload, mdNextCid, "");
    String phoneIp = confBefore.phoneIp();
    details.put("confBeforeHttpStatus", confBefore.httpStatus());
    details.put("beforeLoggedIn", confBefore.loggedIn());
    details.put("beforeStatus", confBefore.status());

    ConfCheckSnapshot confLogoutClick = executeLogoutConfExtenCheck(agentUser, basePayload, mdNextCid, "NormalLogout---LogouT---NORMAL");
    phoneIp = firstNonBlank(phoneIp, confLogoutClick.phoneIp());
    details.put("confLogoutClickHttpStatus", confLogoutClick.httpStatus());
    details.put("clickLoggedIn", confLogoutClick.loggedIn());
    details.put("clickStatus", confLogoutClick.status());

    String dialMethod = resolveLogoutDialMethod(session, campaign);
    Map<String, String> logoutPayload = buildUserLogoutPayload(basePayload, session, campaign, phoneIp, dialMethod);
    var userLogoutResult = client.userLogout(agentUser, logoutPayload);
    String userLogoutBody = Objects.toString(userLogoutResult.body(), "");
    boolean userLogoutAccepted = userLogoutBody.contains("1||1|1|")
        || userLogoutBody.toUpperCase(Locale.ROOT).contains("SUCCESS");
    details.put("userLogoutHttpStatus", userLogoutResult.statusCode());
    details.put("userLogoutAccepted", userLogoutAccepted);
    details.put("userLogoutSnippet", userLogoutResult.snippet(180));

    ConfCheckSnapshot confAfter = executeLogoutConfExtenCheck(agentUser, basePayload, mdNextCid, "");
    details.put("confAfterHttpStatus", confAfter.httpStatus());
    details.put("afterLoggedIn", confAfter.loggedIn());
    details.put("afterStatus", confAfter.status());

    boolean loggedOutConfirmed = "N".equalsIgnoreCase(confAfter.loggedIn())
        || "N".equalsIgnoreCase(confLogoutClick.loggedIn());
    boolean flowExecuted = confBefore.httpStatus() > 0
        && confLogoutClick.httpStatus() > 0
        && userLogoutResult.statusCode() > 0;
    details.put("flowExecuted", flowExecuted);
    details.put("loggedOutConfirmed", loggedOutConfirmed);
    return new LogoutFlowResult(flowExecuted, userLogoutAccepted, loggedOutConfirmed, details);
  }

  public HangupResult hangupActiveCall(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      String dispo
  ) {
    String campaign = firstNonBlank(campaignId, session.connectedCampaign);
    Map<String, Object> details = new LinkedHashMap<>();
    Map<String, String> basePayload = buildAgentRuntimePayload(agentUser, agentPass, session, campaign);

    RealtimeCallSnapshot snapshot = resolveRealtimeCallSnapshot(
        agentUser,
        agentPass,
        session,
        session.currentCallId,
        session.currentLeadId,
        null,
        campaign,
        true
    );
    String callId = firstNonBlank(snapshot.callId(), session.currentCallId);
    Long leadId = firstNonNull(snapshot.leadId(), session.currentLeadId);
    String uniqueId = firstNonBlank(snapshot.uniqueId());
    String channel = firstNonBlank(snapshot.channel());
    String phoneNumber = firstNonBlank(snapshot.phoneNumber());
    String agentStatus = firstNonBlank(snapshot.agentStatus());
    String resolvedCampaign = firstNonBlank(snapshot.campaign(), campaign, session.connectedCampaign);
    String resolvedConfExten = normalizeConfExten(session.confExten, session.connectedPhoneLogin);
    String listId = resolveManualDialListId(resolvedCampaign).orElse(null);
    details.put("snapshotClassification", snapshot.classification());
    details.put("snapshotHttpStatus", snapshot.httpStatus());
    details.put("callId", callId);
    details.put("leadId", leadId);
    details.put("uniqueId", uniqueId);
    details.put("channel", channel);
    details.put("agentStatus", agentStatus);
    details.put("recordingFilename", buildRecordingFilename(phoneNumber, callId, uniqueId));

    if (!StringUtils.hasText(callId) && leadId == null && !StringUtils.hasText(channel) && !isInCallStatus(agentStatus)) {
      credentialService.updateDialRuntime(agentUser, null, null, null);
      return new HangupResult(false, "NO_ACTIVE_CALL", null, null, null, null, agentStatus, details);
    }

    var callbacksResult = client.callbacksCount(agentUser, basePayload);
    details.put("callbacksCountHttpStatus", callbacksResult.statusCode());

    Map<String, String> hangupConfPayload = buildHangupConfDialPayload(basePayload, session, resolvedCampaign, resolvedConfExten);
    var hangupConfResult = client.hangupConfDial(agentUser, hangupConfPayload);
    details.put("hangupConfDialHttpStatus", hangupConfResult.statusCode());

    Map<String, String> hangupPayload = buildHangupPayload(basePayload, session, resolvedCampaign, resolvedConfExten, channel);
    var hangupResult = client.hangup(agentUser, hangupPayload);
    details.put("hangupHttpStatus", hangupResult.statusCode());

    Map<String, String> logEndPayload = buildLogPayload(
        basePayload,
        session,
        callId,
        leadId,
        uniqueId,
        channel,
        phoneNumber,
        resolvedCampaign,
        listId
    );
    if (StringUtils.hasText(dispo)) {
      logEndPayload.put("status", dispo);
    }
    var logEndResult = client.manualDialLogCall(agentUser, logEndPayload, "end");
    details.put("logEndHttpStatus", logEndResult.statusCode());

    if (leadId != null && StringUtils.hasText(dispo)) {
      Map<String, String> updateLeadPayload = buildUpdateLeadPayload(basePayload, leadId, phoneNumber, resolvedCampaign);
      var updateLeadResult = client.updateLead(agentUser, updateLeadPayload);
      details.put("updateLeadHttpStatus", updateLeadResult.statusCode());

      Map<String, String> updateDispoPayload = buildUpdateDispoPayload(
          basePayload,
          session,
          resolvedCampaign,
          callId,
          leadId,
          uniqueId,
          channel,
          phoneNumber,
          listId,
          firstNonBlank(dispo, "N"),
          resolvedConfExten
      );
      var updateDispoResult = client.updateDispo(agentUser, updateDispoPayload);
      details.put("updateDispoHttpStatus", updateDispoResult.statusCode());

      Map<String, String> runUrlsPayload = buildRunUrlsPayload(basePayload, resolvedCampaign);
      var runUrlsResult = client.runUrls(agentUser, runUrlsPayload);
      details.put("runUrlsHttpStatus", runUrlsResult.statusCode());
    } else if (leadId != null) {
      details.put("dispositionDeferred", true);
    }

    var updateSettingsResult = client.updateSettings(agentUser, basePayload);
    details.put("updateSettingsHttpStatus", updateSettingsResult.statusCode());
    credentialService.updateDialRuntime(agentUser, null, null, null);
    return new HangupResult(true, "HANGUP_SENT", callId, leadId, uniqueId, channel, agentStatus, details);
  }

  public ActiveLeadState classifyActiveLead(String agentUser, Entities.AgentVicidialCredentialEntity session) {
    RealtimeCallSnapshot snapshot = resolveRealtimeCallSnapshot(agentUser, session, session.currentCallId, session.currentLeadId, null, session.connectedCampaign, true);
    if (snapshot.reloginRequired()) {
      return ActiveLeadState.relogin(snapshot.httpStatus(), "");
    }

    if (snapshot.leadId() != null) {
      credentialService.updateDialRuntime(agentUser, "ACTIVE", snapshot.callId(), snapshot.leadId());
      return ActiveLeadState.ready(snapshot.leadId(), snapshot.phoneNumber(), snapshot.campaign(), snapshot.details());
    }

    if (isPausedStatus(snapshot.agentStatus())) {
      credentialService.updateDialRuntime(agentUser, null, snapshot.callId(), null);
      return ActiveLeadState.none(snapshot.httpStatus(), "AGENT_PAUSED", "", snapshot.details());
    }

    if (isInCallStatus(snapshot.agentStatus()) && StringUtils.hasText(snapshot.callId())) {
      credentialService.updateDialRuntime(agentUser, "DIALING", snapshot.callId(), null);
      return ActiveLeadState.dialing(snapshot.callId());
    }

    if ("DIALING".equalsIgnoreCase(session.currentDialStatus) && StringUtils.hasText(session.currentCallId)) {
      return ActiveLeadState.dialing(session.currentCallId);
    }

    credentialService.updateDialRuntime(agentUser, null, null, null);
    return ActiveLeadState.none(snapshot.httpStatus(), snapshot.classification(), "", snapshot.details());
  }

  public RealtimeCallSnapshot resolveRealtimeCallSnapshot(
      String agentUser,
      Entities.AgentVicidialCredentialEntity session,
      String callIdHint,
      Long leadIdHint,
      String phoneHint,
      String campaignHint,
      boolean includeActiveLeadApi
  ) {
    Optional<String> agentPass = Optional.ofNullable(credentialService.resolveAgentPass(agentUser)).orElse(Optional.empty());
    return resolveRealtimeCallSnapshot(agentUser, agentPass.orElse(null), session, callIdHint, leadIdHint, phoneHint, campaignHint, includeActiveLeadApi);
  }

  public RealtimeCallSnapshot resolveRealtimeCallSnapshot(
      String agentUser,
      String agentPass,
      Entities.AgentVicidialCredentialEntity session,
      String callIdHint,
      Long leadIdHint,
      String phoneHint,
      String campaignHint,
      boolean includeActiveLeadApi
  ) {
    Map<String, Object> details = new LinkedHashMap<>();
    String callId = firstNonBlank(callIdHint, session.currentCallId);
    Long leadId = firstNonNull(leadIdHint, session.currentLeadId);
    String phoneNumber = phoneHint;
    String campaign = firstNonBlank(campaignHint, session.connectedCampaign);
    String agentStatus = null;
    String uniqueId = null;
    String channel = null;
    String normalizedConfExten = normalizeConfExten(session.confExten, session.connectedPhoneLogin);
    String normalizedMonitorExten = normalizeEndpointDigits(resolveDialExten(session));
    boolean reloginRequired = false;
    int httpStatus = 200;

    if (StringUtils.hasText(agentPass)) {
      Map<String, String> basePayload = buildAgentRuntimePayload(agentUser, agentPass, session, campaign);
      Map<String, String> confPayload = buildConfExtenCheckPayload(basePayload, callId, phoneNumber, 0, true);
      var confResult = client.confExtenCheck(agentUser, confPayload);
      httpStatus = confResult.statusCode();
      Map<String, String> confParsed = client.parseKeyValueLines(confResult.body());
      String confStatus = firstNonBlank(extractAgentStatus(confParsed), extractAgentStatusFromBody(confResult.body()));
      details.put("confExtenCheckHttpStatus", confResult.statusCode());
      details.put("confAgentStatus", confStatus);
      callId = firstNonBlank(callId, firstPresent(confParsed, "call_id", "callid", "callerid"));
      leadId = firstNonNull(leadId, toLong(firstPresent(confParsed, "lead_id", "leadid")));
      phoneNumber = firstNonBlank(phoneNumber, firstPresent(confParsed, "phone_number", "phonenumber"));
      campaign = firstNonBlank(campaign, firstPresent(confParsed, "campaign_id", "campaign"));
      agentStatus = firstNonBlank(agentStatus, confStatus);
      uniqueId = firstNonBlank(uniqueId, firstPresent(confParsed, "uniqueid", "unique_id"));
      channel = selectBestChannel(
          channel,
          firstPresent(confParsed, "channel"),
          session.connectedPhoneLogin,
          normalizedConfExten,
          normalizedMonitorExten
      );
      if (containsReloginMarkers(confResult.body())) {
        reloginRequired = true;
      }

      if (!reloginRequired && (StringUtils.hasText(callId) || leadId != null || isInCallStatus(agentStatus))) {
        Map<String, String> lookPayload = buildLookCallPayload(basePayload, callId, leadId, phoneNumber);
        var lookResult = client.manualDialLookCall(agentUser, lookPayload);
        details.put("lookCallHttpStatus", lookResult.statusCode());
        Map<String, String> lookParsed = client.parseKeyValueLines(lookResult.body());
        callId = firstNonBlank(callId, firstPresent(lookParsed, "call_id", "callid", "callerid"));
        leadId = firstNonNull(leadId, toLong(firstPresent(lookParsed, "lead_id", "leadid")));
        phoneNumber = firstNonBlank(phoneNumber, firstPresent(lookParsed, "phone_number", "phonenumber"));
        campaign = firstNonBlank(campaign, firstPresent(lookParsed, "campaign_id", "campaign"));
        uniqueId = firstNonBlank(uniqueId, firstPresent(lookParsed, "uniqueid", "unique_id"), extractUniqueIdFromBody(lookResult.body()));
        String lookChannel = firstNonBlank(firstPresent(lookParsed, "channel"), extractChannelFromBody(lookResult.body()));
        if (!isOutboundCustomerChannel(lookChannel, session.connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten)) {
          lookChannel = firstNonBlank(
              extractBestOutboundChannelFromBody(
                  lookResult.body(),
                  session.connectedPhoneLogin,
                  normalizedConfExten,
                  normalizedMonitorExten
              ),
              lookChannel
          );
        }
        channel = selectBestChannel(channel, lookChannel, session.connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten);
      }
    }

    RuntimeLeadResolution runtimeLead = resolveLeadFromRuntimeTables(session, callId, phoneNumber);
    leadId = firstNonNull(leadId, runtimeLead.leadId());
    phoneNumber = firstNonBlank(phoneNumber, runtimeLead.phoneNumber());
    campaign = firstNonBlank(campaign, runtimeLead.campaign(), session.connectedCampaign);
    agentStatus = firstNonBlank(agentStatus, runtimeLead.agentStatus());
    uniqueId = firstNonBlank(uniqueId, Objects.toString(runtimeLead.details().get("uniqueId"), null));
    channel = selectBestChannel(
        channel,
        Objects.toString(runtimeLead.details().get("channel"), null),
        session.connectedPhoneLogin,
        normalizedConfExten,
        normalizedMonitorExten
    );
    callId = firstNonBlank(callId, Objects.toString(runtimeLead.details().get("callerId"), null));

    if (!reloginRequired && includeActiveLeadApi && leadId == null) {
      var activeLead = client.activeLeadSafe(agentUser);
      details.put("activeLeadOutcome", activeLead.outcome().name());
      details.put("activeLeadHttpStatus", activeLead.httpStatus());
      if (activeLead.outcome() == VicidialClient.ActiveLeadOutcome.RELOGIN_REQUIRED) {
        reloginRequired = true;
        httpStatus = activeLead.httpStatus();
      } else if (activeLead.outcome() == VicidialClient.ActiveLeadOutcome.SUCCESS) {
        leadId = firstNonNull(leadId, extractLong(activeLead.rawBody(), "lead_id"));
        phoneNumber = firstNonBlank(phoneNumber, extract(activeLead.rawBody(), "phone_number"));
        campaign = firstNonBlank(campaign, extract(activeLead.rawBody(), "campaign"));
      }
    }

    details.put("callId", callId);
    details.put("leadId", leadId);
    details.put("uniqueId", uniqueId);
    details.put("channel", channel);
    details.put("agentStatus", agentStatus);
    String classification = classifySnapshot(reloginRequired, agentStatus, callId, leadId);
    return new RealtimeCallSnapshot(
        reloginRequired,
        httpStatus,
        classification,
        agentStatus,
        callId,
        leadId,
        phoneNumber,
        campaign,
        uniqueId,
        channel,
        details
    );
  }

  public RuntimeLeadResolution resolveLeadFromRuntimeTables(Entities.AgentVicidialCredentialEntity session, String callId, String phoneNumber) {
    JdbcTemplate jdbc;
    try {
      jdbc = new JdbcTemplate(dataSourceFactory.getOrCreate());
    } catch (Exception ex) {
      log.warn("Vicidial runtime datasource unavailable cause={}", ex.getClass().getSimpleName());
      return RuntimeLeadResolution.empty();
    }
    RuntimeLeadResolution fromLiveAgents = queryFromLiveAgents(jdbc, session);
    if (fromLiveAgents.leadId() != null) {
      return fromLiveAgents;
    }

    RuntimeLeadResolution fromAutoCalls = queryFromAutoCalls(jdbc, session, callId, phoneNumber, fromLiveAgents);
    if (fromAutoCalls.leadId() != null || fromAutoCalls.agentStatus() != null) {
      return fromAutoCalls;
    }
    return fromLiveAgents;
  }

  private RuntimeLeadResolution queryFromLiveAgents(JdbcTemplate jdbc, Entities.AgentVicidialCredentialEntity session) {
    String sql = """
        SELECT user, status, campaign_id, lead_id, callerid, uniqueid, channel, extension, conf_exten
          FROM vicidial_live_agents
         WHERE user = ?
         ORDER BY last_update_time DESC
         LIMIT 1
        """;
    try {
      return jdbc.query(sql, rs -> {
        if (!rs.next()) {
          return RuntimeLeadResolution.empty();
        }
        Long leadId = rs.getLong("lead_id");
        if (rs.wasNull() || leadId != null && leadId <= 0) {
          leadId = null;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "vicidial_live_agents");
        details.put("agentStatus", rs.getString("status"));
        details.put("campaign", rs.getString("campaign_id"));
        details.put("callerId", rs.getString("callerid"));
        details.put("uniqueId", rs.getString("uniqueid"));
        details.put("channel", rs.getString("channel"));
        details.put("extension", rs.getString("extension"));
        details.put("confExten", rs.getString("conf_exten"));
        return new RuntimeLeadResolution(leadId, null, rs.getString("campaign_id"), rs.getString("status"), details);
      }, session.agentUser);
    } catch (DataAccessException ex) {
      log.warn("Vicidial runtime query failed table=vicidial_live_agents cause={}", ex.getClass().getSimpleName());
      return RuntimeLeadResolution.empty();
    }
  }

  private RuntimeLeadResolution queryFromAutoCalls(
      JdbcTemplate jdbc,
      Entities.AgentVicidialCredentialEntity session,
      String callId,
      String phoneNumber,
      RuntimeLeadResolution liveAgentState
  ) {
    String agentStatus = liveAgentState.agentStatus();
    String liveCallerId = Objects.toString(liveAgentState.details().get("callerId"), null);
    String liveUniqueId = Objects.toString(liveAgentState.details().get("uniqueId"), null);
    String liveChannel = Objects.toString(liveAgentState.details().get("channel"), null);
    String liveExtension = Objects.toString(liveAgentState.details().get("extension"), null);
    Long liveLeadId = liveAgentState.leadId();

    String callerIdHint = firstNonBlank(callId, liveCallerId);
    String uniqueIdHint = firstNonBlank(liveUniqueId);
    String channelHint = firstNonBlank(liveChannel);
    String extensionHint = firstNonBlank(liveExtension);
    Long leadIdHint = liveLeadId != null && liveLeadId > 0 ? liveLeadId : null;
    String phoneHint = firstNonBlank(phoneNumber);

    String sql = """
        SELECT lead_id, callerid, uniqueid, phone_number, campaign_id, status, channel, extension
          FROM vicidial_auto_calls
         WHERE (? IS NULL OR campaign_id = ?)
           AND (
             (? IS NOT NULL AND callerid = ?)
             OR (? IS NOT NULL AND uniqueid = ?)
             OR (? IS NOT NULL AND channel = ?)
             OR (? IS NOT NULL AND extension = ?)
             OR (? IS NOT NULL AND lead_id = ?)
             OR (? IS NOT NULL AND phone_number = ?)
           )
         ORDER BY call_time DESC
         LIMIT 1
        """;
    try {
      return jdbc.query(sql, rs -> {
        if (!rs.next()) {
          return RuntimeLeadResolution.empty();
        }
        Long leadId = rs.getLong("lead_id");
        if (rs.wasNull() || leadId != null && leadId <= 0) {
          leadId = null;
        }
        Map<String, Object> details = new LinkedHashMap<>();
        details.put("source", "vicidial_auto_calls");
        details.put("status", rs.getString("status"));
        details.put("callerId", rs.getString("callerid"));
        details.put("uniqueId", rs.getString("uniqueid"));
        details.put("campaign", rs.getString("campaign_id"));
        details.put("channel", rs.getString("channel"));
        details.put("extension", rs.getString("extension"));
        return new RuntimeLeadResolution(leadId, rs.getString("phone_number"), rs.getString("campaign_id"), agentStatus, details);
      },
          session.connectedCampaign, session.connectedCampaign,
          callerIdHint, callerIdHint,
          uniqueIdHint, uniqueIdHint,
          channelHint, channelHint,
          extensionHint, extensionHint,
          leadIdHint, leadIdHint,
          phoneHint, phoneHint);
    } catch (DataAccessException ex) {
      log.warn("Vicidial runtime query failed table=vicidial_auto_calls cause={}", ex.getClass().getSimpleName());
      return RuntimeLeadResolution.empty();
    }
  }

  private Map<String, String> buildAgentRuntimePayload(String agentUser, String agentPass, Entities.AgentVicidialCredentialEntity session, String campaignId) {
    LinkedHashMap<String, String> payload = new LinkedHashMap<>();
    String resolvedExten = resolveDialExten(session);
    String resolvedConfExten = normalizeConfExten(session.confExten, session.connectedPhoneLogin);
    String resolvedProtocol = resolveProtocol(session);
    payload.put("user", agentUser);
    payload.put("pass", agentPass);
    payload.put("server_ip", Objects.toString(session.serverIp, ""));
    payload.put("session_name", Objects.toString(session.sessionName, ""));
    payload.put("agent_user", agentUser);
    payload.put("campaign", Objects.toString(firstNonBlank(campaignId, session.connectedCampaign), ""));
    payload.put("phone_login", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("conf_exten", Objects.toString(resolvedConfExten, ""));
    payload.put("exten", resolvedExten);
    payload.put("ext_context", "default");
    payload.put("extension", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("protocol", Objects.toString(resolvedProtocol, ""));
    payload.put("agent_log_id", Objects.toString(session.agentLogId, ""));
    payload.put("format", "text");
    return payload;
  }

  private Map<String, String> buildLookCallPayload(Map<String, String> basePayload, String callId, Long leadId, String phoneNumber) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    if (StringUtils.hasText(callId)) {
      payload.put("call_id", callId);
      payload.put("MDnextCID", callId);
    }
    if (leadId != null) {
      payload.put("lead_id", String.valueOf(leadId));
    }
    if (StringUtils.hasText(phoneNumber)) {
      payload.put("phone_number", phoneNumber);
    }
    payload.putIfAbsent("DiaL_SecondS", "0");
    payload.putIfAbsent("stage", "");
    payload.putIfAbsent("routing_initiated_recording", "Y");
    payload.putIfAbsent("manual_minimum_ring_seconds", "0");
    payload.putIfAbsent("manual_minimum_ringing", "0");
    payload.putIfAbsent("stereo_recording", "DISABLED");
    payload.putIfAbsent("recording_filename", "");
    payload.putIfAbsent("recording_id", "");
    return payload;
  }

  private Map<String, String> buildConfExtenCheckPayload(
      Map<String, String> basePayload,
      String mdNextCid,
      String phoneNumber,
      int liveCallSeconds,
      boolean campAgentStatusDisplay
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    payload.put("client", "vdc");
    payload.put("auto_dial_level", "0");
    payload.put("campagentstdisp", campAgentStatusDisplay ? "YES" : "NO");
    payload.put("customer_chat_id", "");
    payload.put("live_call_seconds", String.valueOf(Math.max(liveCallSeconds, 0)));
    payload.put("active_ingroup_dial", "");
    payload.put("xferchannel", "");
    payload.put("check_for_answer", "0");
    payload.put("MDnextCID", Objects.toString(mdNextCid, ""));
    payload.put("phone_number", Objects.toString(phoneNumber, ""));
    payload.put("visibility", "");
    payload.put("latency", "0");
    payload.put("dead_count", "0");
    payload.put("clicks", "");
    return payload;
  }

  private Map<String, String> buildUserLogoutPayload(
      Map<String, String> basePayload,
      Entities.AgentVicidialCredentialEntity session,
      String campaign,
      String phoneIp,
      String dialMethod
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    String confExten = normalizeConfExten(session.confExten, session.connectedPhoneLogin);
    String extension = firstNonBlank(
        normalizeEndpointDigits(session.connectedPhoneLogin),
        normalizeEndpointDigits(session.extension),
        normalizeEndpointDigits(resolveDialExten(session)),
        session.connectedPhoneLogin,
        session.extension,
        resolveDialExten(session),
        ""
    );
    payload.put("session_name", Objects.toString(basePayload.get("session_name"), ""));
    payload.put("server_ip", Objects.toString(basePayload.get("server_ip"), ""));
    payload.put("campaign", Objects.toString(campaign, ""));
    payload.put("conf_exten", Objects.toString(confExten, ""));
    payload.put("phone_login", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("extension", Objects.toString(extension, ""));
    payload.put("protocol", firstNonBlank(resolveProtocol(session), "SIP"));
    payload.put("agent_log_id", Objects.toString(session.agentLogId, ""));
    payload.put("no_delete_sessions", "1");
    payload.put("phone_ip", Objects.toString(phoneIp, ""));
    payload.put("enable_sipsak_messages", "0");
    payload.put("LogouTKicKAlL", "1");
    payload.put("ext_context", "default");
    payload.put("qm_extension", Objects.toString(extension, ""));
    payload.put("stage", "NORMAL");
    payload.put("dial_method", firstNonBlank(dialMethod, "MANUAL"));
    payload.put("pause_max_url_trigger", "");
    payload.put("format", "text");
    return payload;
  }

  private Map<String, String> buildMonitorConfPayload(
      Map<String, String> basePayload,
      Entities.AgentVicidialCredentialEntity session,
      String callId,
      Long leadId,
      String uniqueId,
      String channel,
      String phoneNumber
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    if (leadId != null) {
      payload.put("lead_id", String.valueOf(leadId));
    }
    String confChannel = firstNonBlank(
        resolveMonitorChannel(session, normalizeConfExten(session.confExten, session.connectedPhoneLogin)),
        basePayload.get("channel"),
        channel
    );
    if (StringUtils.hasText(confChannel)) {
      payload.put("channel", confChannel);
    }
    if (StringUtils.hasText(uniqueId)) {
      payload.put("uniqueid", uniqueId);
    }
    payload.put("filename", buildRecordingFilename(phoneNumber, callId, uniqueId));
    payload.put("exten", resolveDialExten(session));
    payload.put("ext_context", "default");
    payload.put("ext_priority", "1");
    payload.put("FROMapi", "");
    payload.put("FROMvdc", "YES");
    return payload;
  }

  private Map<String, String> buildLogPayload(
      Map<String, String> basePayload,
      Entities.AgentVicidialCredentialEntity session,
      String callId,
      Long leadId,
      String uniqueId,
      String channel,
      String phoneNumber,
      String campaignId,
      String listId
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    if (StringUtils.hasText(callId)) {
      payload.put("call_id", callId);
      payload.put("MDnextCID", callId);
    }
    if (leadId != null) {
      payload.put("lead_id", String.valueOf(leadId));
    }
    if (StringUtils.hasText(uniqueId)) {
      payload.put("uniqueid", uniqueId);
    }
    if (StringUtils.hasText(channel)) {
      payload.put("channel", channel);
    }
    if (StringUtils.hasText(phoneNumber)) {
      payload.put("phone_number", phoneNumber);
    }
    if (StringUtils.hasText(campaignId)) {
      payload.put("campaign", campaignId);
    }
    if (StringUtils.hasText(listId)) {
      payload.put("list_id", listId);
    }
    payload.putIfAbsent("exten", resolveDialExten(session));
    payload.putIfAbsent("ext_context", "default");
    payload.putIfAbsent("extension", Objects.toString(session.connectedPhoneLogin, ""));
    payload.putIfAbsent("protocol", resolveProtocol(session));
    payload.putIfAbsent("qm_extension", Objects.toString(session.connectedPhoneLogin, ""));
    String channelRec = resolveMonitorChannel(session, normalizeConfExten(session.confExten, session.connectedPhoneLogin));
    if (StringUtils.hasText(channelRec)) {
      payload.put("channelrec", channelRec);
    }
    payload.putIfAbsent("inOUT", "OUT");
    payload.putIfAbsent("called_count", "1");
    return payload;
  }

  private Map<String, String> buildHangupConfDialPayload(
      Map<String, String> basePayload,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      String confExten
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    payload.put("log_campaign", Objects.toString(campaignId, ""));
    payload.put("ext_context", "default");
    payload.put("exten", Objects.toString(confExten, ""));
    payload.put("qm_extension", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("queryCID", buildQueryCid("HTvdcW", session.agentUser));
    payload.put("format", "text");
    return payload;
  }

  private Map<String, String> buildHangupPayload(
      Map<String, String> basePayload,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      String confExten,
      String channel
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    payload.put("auto_dial_level", "0");
    payload.put("call_server_ip", "");
    payload.put("CalLCID", "");
    payload.put("campaign", Objects.toString(campaignId, ""));
    payload.put("log_campaign", Objects.toString(campaignId, ""));
    payload.put("nodeletevdac", "");
    payload.put("qm_extension", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("queryCID", buildQueryCid("HLvdcW", session.agentUser));
    payload.put("secondS", "9");
    payload.put("stage", "CALLHANGUP");
    payload.put("exten", Objects.toString(confExten, ""));
    if (StringUtils.hasText(channel)) {
      payload.put("channel", channel);
    }
    payload.put("format", "text");
    return payload;
  }

  private Map<String, String> buildUpdateLeadPayload(
      Map<String, String> basePayload,
      Long leadId,
      String phoneNumber,
      String campaignId
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    payload.put("lead_id", String.valueOf(leadId));
    payload.put("campaign", Objects.toString(campaignId, ""));
    payload.put("phone_number", Objects.toString(phoneNumber, ""));
    payload.put("format", "text");
    return payload;
  }

  private Map<String, String> buildUpdateDispoPayload(
      Map<String, String> basePayload,
      Entities.AgentVicidialCredentialEntity session,
      String campaignId,
      String callId,
      Long leadId,
      String uniqueId,
      String channel,
      String phoneNumber,
      String listId,
      String dispo,
      String confExten
  ) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    payload.put("agent_log_id", Objects.toString(session.agentLogId, ""));
    payload.put("auto_dial_level", "0");
    payload.put("called_count", "1");
    payload.put("campaign", Objects.toString(campaignId, ""));
    payload.put("conf_exten", Objects.toString(confExten, ""));
    payload.put("customer_sec", "0");
    payload.put("dial_method", "MANUAL");
    payload.put("dispo_choice", Objects.toString(dispo, "N"));
    payload.put("exten", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("lead_id", Objects.toString(leadId, ""));
    payload.put("list_id", Objects.toString(listId, ""));
    payload.put("MDnextCID", Objects.toString(callId, ""));
    payload.put("orig_pass", Objects.toString(basePayload.get("pass"), ""));
    payload.put("original_phone_login", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("parked_hangup", "0");
    payload.put("phone_code", resolveDefaultPhoneCode());
    payload.put("phone_login", Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("phone_pass", "anexo_" + Objects.toString(session.connectedPhoneLogin, ""));
    payload.put("phone_number", Objects.toString(phoneNumber, ""));
    payload.put("recording_filename", buildRecordingFilename(phoneNumber, callId, uniqueId));
    payload.put("recording_id", "");
    payload.put("stage", Objects.toString(campaignId, ""));
    payload.put("uniqueid", Objects.toString(uniqueId, ""));
    payload.put("use_campaign_dnc", "N");
    payload.put("use_internal_dnc", "N");
    payload.put("vtiger_callback_id", "0");
    payload.put("qm_dispo_code", "");
    payload.put("dispo_comments", "");
    if (StringUtils.hasText(channel)) {
      payload.put("channel", channel);
    }
    return payload;
  }

  private Map<String, String> buildRunUrlsPayload(Map<String, String> basePayload, String campaignId) {
    Map<String, String> payload = new LinkedHashMap<>(basePayload);
    payload.put("auto_dial_level", "0");
    payload.put("campaign", Objects.toString(campaignId, ""));
    payload.put("orig_pass", Objects.toString(basePayload.get("pass"), ""));
    payload.put("stage", "dispo");
    payload.put("url_ids", "");
    return payload;
  }

  private String buildQueryCid(String prefix, String agentUser) {
    return Objects.toString(prefix, "") + System.currentTimeMillis() + buildUserAbb(agentUser);
  }

  private String buildUserAbb(String agentUser) {
    String normalized = Objects.toString(agentUser, "").trim();
    if (!StringUtils.hasText(normalized)) {
      return "";
    }
    StringBuilder builder = new StringBuilder();
    while (builder.length() < 32) {
      builder.append(normalized);
    }
    return builder.substring(0, 32);
  }

  private String buildRecordingFilename(String phoneNumber, String callId, String uniqueId) {
    String compactPhone = Objects.toString(phoneNumber, "").replaceAll("[^0-9]+", "");
    if (StringUtils.hasText(compactPhone)) {
      return java.time.LocalDateTime.now().format(java.time.format.DateTimeFormatter.ofPattern("yyyyMMdd-HHmmss")) + "_" + compactPhone;
    }
    if (StringUtils.hasText(uniqueId)) {
      return uniqueId;
    }
    if (StringUtils.hasText(callId)) {
      return callId;
    }
    return "monitor";
  }

  private String extractAgentStatus(Map<String, String> parsed) {
    return firstPresent(parsed,
        "status",
        "logged_in",
        "loggedin",
        "agent_status",
        "last_vdrp_stage",
        "vdrp_stage");
  }

  private String resolveDialExten(Entities.AgentVicidialCredentialEntity session) {
    String extension = firstNonBlank(
        session.extension,
        resolveDefaultRecordingExten(),
        session.connectedPhoneLogin
    );
    if (!StringUtils.hasText(extension)) {
      return "";
    }
    String value = extension.trim();
    if (containsPlaceholderToken(value)) {
      return Objects.toString(session.connectedPhoneLogin, "");
    }
    if (value.contains("/")) {
      value = value.substring(value.indexOf('/') + 1);
    }
    if (value.contains("@")) {
      value = value.substring(0, value.indexOf('@'));
    }
    if (value.contains("-")) {
      value = value.substring(0, value.indexOf('-'));
    }
    value = value.trim();
    value = StringUtils.hasText(value) ? value : Objects.toString(session.connectedPhoneLogin, "");
    if (isAgentPhoneExtension(value, session.connectedPhoneLogin)) {
      String fallbackRecordingExten = resolveDefaultRecordingExten();
      if (StringUtils.hasText(fallbackRecordingExten)
          && !isAgentPhoneExtension(fallbackRecordingExten, session.connectedPhoneLogin)) {
        return fallbackRecordingExten;
      }
      return "";
    }
    return value;
  }

  private String resolveProtocol(Entities.AgentVicidialCredentialEntity session) {
    if (StringUtils.hasText(session.protocol)) {
      String protocol = session.protocol.trim().toUpperCase(Locale.ROOT);
      if (!containsPlaceholderToken(protocol)) {
        return protocol;
      }
    }
    String extension = Objects.toString(session.extension, "");
    int slash = extension.indexOf('/');
    if (slash > 0) {
      String protocol = extension.substring(0, slash).trim().toUpperCase(Locale.ROOT);
      return containsPlaceholderToken(protocol) ? "" : protocol;
    }
    return "";
  }

  private String resolveLogoutDialMethod(Entities.AgentVicidialCredentialEntity session, String campaign) {
    try {
      if (StringUtils.hasText(campaign)) {
        String fromCampaign = firstNonBlank(campaignMode(campaign).dialMethodRaw());
        if (StringUtils.hasText(fromCampaign)) {
          return fromCampaign.trim().toUpperCase(Locale.ROOT);
        }
      }
    } catch (Exception ignored) {
      // fallback to session mode below
    }
    String fromMode = firstNonBlank(session.connectedMode);
    if (!StringUtils.hasText(fromMode)) {
      return "MANUAL";
    }
    String normalized = fromMode.trim().toUpperCase(Locale.ROOT);
    if ("MANUAL".equals(normalized)) {
      return "MANUAL";
    }
    if ("PREDICTIVE".equals(normalized)) {
      return "RATIO";
    }
    return normalized;
  }

  private String normalizeLoggedIn(String value) {
    if (!StringUtils.hasText(value)) {
      return "";
    }
    String normalized = value.trim().toUpperCase(Locale.ROOT);
    if (normalized.startsWith("Y")) {
      return "Y";
    }
    if (normalized.startsWith("N")) {
      return "N";
    }
    return normalized;
  }

  private ConfCheckSnapshot executeLogoutConfExtenCheck(
      String agentUser,
      Map<String, String> basePayload,
      String mdNextCid,
      String clicks
  ) {
    Map<String, String> payload = buildConfExtenCheckPayload(basePayload, mdNextCid, "", 0, true);
    payload.put("clicks", Objects.toString(clicks, ""));
    var result = client.confExtenCheck(agentUser, payload);
    Map<String, String> parsed = client.parseKeyValueLines(result.body());
    String loggedIn = normalizeLoggedIn(firstPresent(parsed, "logged_in", "loggedin"));
    String status = firstNonBlank(firstPresent(parsed, "status", "agent_status"), extractAgentStatus(parsed));
    String phoneIp = firstPresent(parsed, "phone_ip", "phoneip");
    return new ConfCheckSnapshot(result.statusCode(), loggedIn, status, phoneIp);
  }

  private String normalizeConfExten(String confExten, String fallback) {
    String candidate = firstNonBlank(confExten, fallback);
    if (!StringUtils.hasText(candidate) || containsPlaceholderToken(candidate)) {
      return Objects.toString(fallback, "").replaceAll("[^0-9]+", "");
    }
    String digits = candidate.replaceAll("[^0-9]+", "");
    if (StringUtils.hasText(digits)) {
      return digits;
    }
    return containsPlaceholderToken(candidate) ? "" : candidate;
  }

  private String resolveDefaultRecordingExten() {
    String configured = firstNonBlank(
        environment.getProperty("app.vicidial.dial.default-recording-exten"),
        environment.getProperty("VICIDIAL_RECORDING_EXTEN")
    );
    if (!StringUtils.hasText(configured) || containsPlaceholderToken(configured)) {
      return null;
    }
    String digits = configured.replaceAll("[^0-9]+", "");
    return StringUtils.hasText(digits) ? digits : configured.trim();
  }

  private String resolveDefaultPhoneCode() {
    String configured = firstNonBlank(
        environment.getProperty("app.vicidial.dial.default-phone-code"),
        environment.getProperty("VICIDIAL_DIAL_DEFAULT_PHONE_CODE"),
        "1"
    );
    String digits = Objects.toString(configured, "").replaceAll("[^0-9]+", "");
    return StringUtils.hasText(digits) ? digits : "1";
  }

  private boolean isAgentPhoneExtension(String candidate, String phoneLogin) {
    String left = Objects.toString(candidate, "").replaceAll("[^0-9]+", "");
    String right = Objects.toString(phoneLogin, "").replaceAll("[^0-9]+", "");
    return StringUtils.hasText(left) && StringUtils.hasText(right) && left.equals(right);
  }

  private String resolveMonitorChannel(Entities.AgentVicidialCredentialEntity session, String normalizedConfExten) {
    if (StringUtils.hasText(normalizedConfExten)) {
      return buildConferenceLocalChannel(normalizedConfExten);
    }
    String extension = Objects.toString(session.extension, "").trim();
    if (isLocalChannel(extension) && !containsPlaceholderToken(extension)) {
      return extension;
    }
    return "";
  }

  private boolean isLocalChannel(String value) {
    String candidate = Objects.toString(value, "").trim();
    return StringUtils.hasText(candidate)
        && candidate.regionMatches(true, 0, "Local/", 0, "Local/".length())
        && candidate.contains("@");
  }

  private String buildConferenceLocalChannel(String normalizedConfExten) {
    String digits = Objects.toString(normalizedConfExten, "").replaceAll("[^0-9]+", "");
    if (!StringUtils.hasText(digits)) {
      return "";
    }
    String monitorNumber = digits.startsWith("5") ? digits : "5" + digits;
    return "Local/" + monitorNumber + "@default";
  }

  private boolean containsPlaceholderToken(String value) {
    String normalized = Objects.toString(value, "").toLowerCase(Locale.ROOT);
    return normalized.contains("taskconfnum")
        || normalized.contains("session_id")
        || normalized.contains("undefined")
        || normalized.contains("null")
        || normalized.contains("+")
        || normalized.contains("&");
  }

  private String extractAgentStatusFromBody(String body) {
    String safeBody = Objects.toString(body, "");
    if (!StringUtils.hasText(safeBody)) {
      return null;
    }
    String[] patterns = new String[]{
        "(?im)\\bLogged-?in\\s*[:=]\\s*([A-Z_]+)\\b",
        "(?im)\\bStatus\\s*[:=]\\s*([A-Z_]+)\\b",
        "(?im)\\blast_VDRP_stage\\s*[:=]\\s*([A-Z_]+)\\b",
        "(?im)\\bagent_status\\s*[:=]\\s*([A-Z_]+)\\b",
        "(?im)\\b(?:var\\s+)?status\\s*=\\s*['\\\"]?([A-Z_]+)['\\\"]?\\s*;?",
        "(?im)\\b(?:var\\s+)?last_VDRP_stage\\s*=\\s*['\\\"]?([A-Z_]+)['\\\"]?\\s*;?"
    };
    for (String regex : patterns) {
      Matcher matcher = Pattern.compile(regex).matcher(safeBody);
      if (matcher.find()) {
        String status = Objects.toString(matcher.group(1), "").trim();
        if (StringUtils.hasText(status)) {
          return status.toUpperCase(Locale.ROOT);
        }
      }
    }
    return null;
  }

  private String extractUniqueIdFromBody(String body) {
    String safeBody = Objects.toString(body, "");
    if (!StringUtils.hasText(safeBody)) {
      return null;
    }
    String[] patterns = new String[]{
        "(?im)\\buniqueid\\s*[:=]\\s*([0-9]{8,}\\.[0-9]+)\\b",
        "(?im)^\\s*([0-9]{8,}\\.[0-9]+)\\s+(?:SIP|PJSIP|IAX2?|Local|DAHDI|Zap|Agent)/",
        "(?im)^\\s*([0-9]{8,}\\.[0-9]+)\\s*$"
    };
    for (String regex : patterns) {
      Matcher matcher = Pattern.compile(regex).matcher(safeBody);
      if (matcher.find()) {
        String value = Objects.toString(matcher.group(1), "").trim();
        if (StringUtils.hasText(value)) {
          return value;
        }
      }
    }
    return null;
  }

  private String extractChannelFromBody(String body) {
    String safeBody = Objects.toString(body, "");
    if (!StringUtils.hasText(safeBody)) {
      return null;
    }
    String[] patterns = new String[]{
        "(?im)\\bchannel\\s*[:=]\\s*((?:SIP|PJSIP|IAX2?|Local|DAHDI|Zap|Agent)/[^\\s|]+)",
        "(?im)^[0-9]{8,}\\.[0-9]+\\s+((?:SIP|PJSIP|IAX2?|Local|DAHDI|Zap|Agent)/[^\\s|]+)",
        "(?im)^\\s*((?:SIP|PJSIP|IAX2?|Local|DAHDI|Zap|Agent)/[^\\s|]+)\\s*$",
        "(?im)(?:^|[|\\s])((?:SIP|PJSIP|IAX2?|Local|DAHDI|Zap|Agent)/[^\\s|~]+)"
    };
    for (String regex : patterns) {
      Matcher matcher = Pattern.compile(regex).matcher(safeBody);
      if (matcher.find()) {
        String value = Objects.toString(matcher.group(1), "").trim();
        if (StringUtils.hasText(value)) {
          return value;
        }
      }
    }
    return null;
  }

  private String extractBestOutboundChannelFromBody(
      String body,
      String connectedPhoneLogin,
      String normalizedConfExten,
      String normalizedMonitorExten
  ) {
    String safeBody = Objects.toString(body, "");
    if (!StringUtils.hasText(safeBody)) {
      return null;
    }
    Matcher matcher = Pattern.compile("(?im)((?:SIP|PJSIP|IAX2?|Local|DAHDI|Zap|Agent)/[^\\s|~]+)").matcher(safeBody);
    String fallback = null;
    while (matcher.find()) {
      String candidate = Objects.toString(matcher.group(1), "").trim();
      if (!StringUtils.hasText(candidate)) {
        continue;
      }
      if (fallback == null) {
        fallback = candidate;
      }
      if (isOutboundCustomerChannel(candidate, connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten)) {
        return candidate;
      }
    }
    return fallback;
  }

  private boolean hasDialEvidence(
      String callId,
      Long leadId,
      String uniqueId,
      String channel,
      String agentStatus,
      boolean lookCallConsistent,
      String connectedPhoneLogin,
      String normalizedConfExten,
      String normalizedMonitorExten
  ) {
    boolean hasCall = StringUtils.hasText(callId);
    boolean hasLead = leadId != null;
    boolean outboundChannel = isOutboundCustomerChannel(channel, connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten);
    boolean hasMedia = outboundChannel && (StringUtils.hasText(uniqueId) || StringUtils.hasText(channel));
    boolean incall = isInCallStatus(agentStatus);
    if (!outboundChannel) {
      return false;
    }
    if (incall && hasMedia && (hasCall || hasLead)) {
      return true;
    }
    if (lookCallConsistent && hasMedia && (hasCall || hasLead)) {
      return true;
    }
    if (hasCall && hasLead && hasMedia) {
      return true;
    }
    return false;
  }

  private boolean isOutboundCustomerChannel(
      String channel,
      String connectedPhoneLogin,
      String normalizedConfExten,
      String normalizedMonitorExten
  ) {
    String candidate = Objects.toString(channel, "").trim();
    if (!StringUtils.hasText(candidate)) {
      return false;
    }
    if (candidate.regionMatches(true, 0, "Local/", 0, "Local/".length())) {
      return false;
    }
    if (isAgentLoopChannel(candidate, connectedPhoneLogin)) {
      return false;
    }
    String confDigits = Objects.toString(normalizedConfExten, "").replaceAll("[^0-9]+", "");
    if (StringUtils.hasText(confDigits)) {
      String escapedConfDigits = Pattern.quote(confDigits);
      String escapedSilentConf = Pattern.quote("5" + confDigits);
      if (candidate.matches("(?i)^.*/" + escapedConfDigits + "(?:[-@].*)?$")
          || candidate.matches("(?i)^.*/" + escapedSilentConf + "(?:[-@].*)?$")) {
        return false;
      }
    }
    if (isMonitorLoopChannel(candidate, normalizedMonitorExten)) {
      return false;
    }
    return true;
  }

  private boolean isMonitorLoopChannel(String channel, String normalizedMonitorExten) {
    String candidate = Objects.toString(channel, "").trim();
    String monitorExten = normalizeEndpointDigits(normalizedMonitorExten);
    if (!StringUtils.hasText(candidate) || !StringUtils.hasText(monitorExten)) {
      return false;
    }
    String escapedMonitorExten = Pattern.quote(monitorExten);
    return candidate.matches("(?i)^(?:SIP|PJSIP|IAX2?)/" + escapedMonitorExten + "(?:[-@].*)?$");
  }

  private String selectBestChannel(
      String existing,
      String candidate,
      String connectedPhoneLogin,
      String normalizedConfExten,
      String normalizedMonitorExten
  ) {
    String current = firstNonBlank(existing);
    String incoming = firstNonBlank(candidate);
    if (!StringUtils.hasText(current)) {
      return incoming;
    }
    if (!StringUtils.hasText(incoming)) {
      return current;
    }
    boolean currentOutbound = isOutboundCustomerChannel(current, connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten);
    boolean incomingOutbound = isOutboundCustomerChannel(incoming, connectedPhoneLogin, normalizedConfExten, normalizedMonitorExten);
    if (!currentOutbound && incomingOutbound) {
      return incoming;
    }
    return current;
  }

  private String normalizeEndpointDigits(String value) {
    String candidate = Objects.toString(value, "").trim();
    if (!StringUtils.hasText(candidate)) {
      return null;
    }
    String digits = candidate.replaceAll("[^0-9]+", "");
    return StringUtils.hasText(digits) ? digits : null;
  }

  private boolean isAgentLoopChannel(String channel, String connectedPhoneLogin) {
    String candidate = Objects.toString(channel, "").trim();
    String phone = Objects.toString(connectedPhoneLogin, "").trim();
    if (!StringUtils.hasText(candidate) || !StringUtils.hasText(phone)) {
      return false;
    }
    String escapedPhone = Pattern.quote(phone);
    return candidate.matches("(?i)^(?:SIP|PJSIP)/" + escapedPhone + "(?:[-@].*)?$");
  }

  private String classifySnapshot(boolean reloginRequired, String agentStatus, String callId, Long leadId) {
    if (reloginRequired) {
      return "RELOGIN_REQUIRED";
    }
    if (leadId != null) {
      return "READY";
    }
    if (isPausedStatus(agentStatus)) {
      return "AGENT_PAUSED";
    }
    if (isInCallStatus(agentStatus)) {
      return "INCALL_NO_LEAD";
    }
    if (StringUtils.hasText(callId)) {
      return "DIALING_NO_LEAD_YET";
    }
    return "NO_ACTIVE_LEAD";
  }

  private boolean containsReloginMarkers(String body) {
    String normalized = Objects.toString(body, "").toLowerCase(Locale.ROOT);
    return normalized.contains("name=\"vd_login\"")
        || normalized.contains("name='vd_login'")
        || normalized.contains("id=\"vd_login\"")
        || normalized.contains("id='vd_login'")
        || normalized.contains("name=\"vd_pass\"")
        || normalized.contains("name='vd_pass'")
        || normalized.contains("id=\"vd_pass\"")
        || normalized.contains("id='vd_pass'")
        || normalized.contains("not logged in")
        || normalized.contains("re-login")
        || normalized.contains("agent login");
  }

  private String extract(String raw, String key) {
    Map<String, String> parsed = client.parseKeyValueLines(raw);
    String value = firstPresent(parsed, key);
    if (StringUtils.hasText(value)) {
      return value;
    }
    var matcher = java.util.regex.Pattern.compile(key + "=([^&\\n]+)").matcher(Objects.toString(raw, ""));
    return matcher.find() ? matcher.group(1) : "";
  }

  private Long extractLong(String raw, String key) {
    return toLong(extract(raw, key));
  }

  private Long toLong(String value) {
    if (!StringUtils.hasText(value)) {
      return null;
    }
    try {
      return Long.parseLong(value.trim());
    } catch (NumberFormatException ex) {
      return null;
    }
  }

  private String firstPresent(Map<String, String> parsed, String... keys) {
    for (String key : keys) {
      String normalized = normalizeKey(key);
      String value = parsed.get(normalized);
      if (!StringUtils.hasText(value)) {
        value = parsed.get(normalized.replace("_", ""));
      }
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private String normalizeKey(String key) {
    return Objects.toString(key, "")
        .trim()
        .toLowerCase(Locale.ROOT)
        .replaceAll("[^a-z0-9]+", "_")
        .replaceAll("_+", "_")
        .replaceAll("^_+", "")
        .replaceAll("_+$", "");
  }

  private boolean isInCallStatus(String status) {
    return "INCALL".equalsIgnoreCase(Objects.toString(status, ""));
  }

  private boolean isPausedStatus(String status) {
    return "PAUSED".equalsIgnoreCase(Objects.toString(status, ""));
  }

  private String firstNonBlank(String... values) {
    for (String value : values) {
      if (StringUtils.hasText(value)) {
        return value.trim();
      }
    }
    return null;
  }

  private Long firstNonNull(Long... values) {
    for (Long value : values) {
      if (value != null) {
        return value;
      }
    }
    return null;
  }

  private void sleepQuietly(long millis) {
    try {
      Thread.sleep(millis);
    } catch (InterruptedException ignored) {
      Thread.currentThread().interrupt();
    }
  }

  private boolean isDevEnvironment() {
    boolean profileDev = Arrays.stream(environment.getActiveProfiles()).anyMatch("dev"::equalsIgnoreCase);
    String appEnv = environment.getProperty("APP_ENV", environment.getProperty("app.env", ""));
    return profileDev || "dev".equalsIgnoreCase(appEnv);
  }

  public record CampaignMode(String campaignId, String dialMethodRaw, String mode) {
  }

  public record DialNextResult(String callId, Long leadId, String classification) {
  }

  public record DialFollowUpResult(
      boolean incallConfirmed,
      String classification,
      String callId,
      Long leadId,
      String uniqueId,
      String channel,
      String phoneNumber,
      String listId,
      String leadStatus,
      String agentStatus,
      Map<String, Object> details
  ) {
  }

  public record RealtimeCallSnapshot(
      boolean reloginRequired,
      int httpStatus,
      String classification,
      String agentStatus,
      String callId,
      Long leadId,
      String phoneNumber,
      String campaign,
      String uniqueId,
      String channel,
      Map<String, Object> details
  ) {
  }

  public record HangupResult(
      boolean executed,
      String classification,
      String callId,
      Long leadId,
      String uniqueId,
      String channel,
      String agentStatus,
      Map<String, Object> details
  ) {
  }

  public record PostCallSyncResult(
      boolean synced,
      String recordingFilename,
      String error,
      Map<String, Object> details
  ) {
  }

  public record LogoutFlowResult(
      boolean executed,
      boolean userLogoutAccepted,
      boolean loggedOutConfirmed,
      Map<String, Object> details
  ) {
  }

  private record ConfCheckSnapshot(
      int httpStatus,
      String loggedIn,
      String status,
      String phoneIp
  ) {
  }

  public record ActiveLeadState(boolean hasLead, boolean dialing, boolean reloginRequired, String callId, Long leadId,
                                String phoneNumber, String campaign, int httpStatus, String classification, String rawBody,
                                Map<String, Object> details) {
    public static ActiveLeadState dialing(String callId) {
      return new ActiveLeadState(false, true, false, callId, null, null, null, 200, "DIALING", "", Map.of());
    }

    public static ActiveLeadState ready(Long leadId, String phoneNumber, String campaign) {
      return ready(leadId, phoneNumber, campaign, Map.of("source", "active_lead_api"));
    }

    public static ActiveLeadState ready(Long leadId, String phoneNumber, String campaign, Map<String, Object> details) {
      return new ActiveLeadState(true, false, false, null, leadId, phoneNumber, campaign, 200, "SUCCESS", "",
          details == null ? Map.of() : details);
    }

    public static ActiveLeadState none(int httpStatus, String classification, String rawBody) {
      return none(httpStatus, classification, rawBody, Map.of());
    }

    public static ActiveLeadState none(int httpStatus, String classification, String rawBody, Map<String, Object> details) {
      return new ActiveLeadState(false, false, false, null, null, null, null, httpStatus, classification, rawBody,
          details == null ? Map.of() : details);
    }

    public static ActiveLeadState relogin(int httpStatus, String rawBody) {
      return new ActiveLeadState(false, false, true, null, null, null, null, httpStatus, "RELOGIN_REQUIRED", rawBody, Map.of());
    }
  }

  public record RuntimeLeadResolution(Long leadId, String phoneNumber, String campaign, String agentStatus, Map<String, Object> details) {
    static RuntimeLeadResolution empty() {
      return new RuntimeLeadResolution(null, null, null, null, Map.of());
    }
  }
}
