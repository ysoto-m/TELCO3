package com.telco3.agentui.legacy;

/**
 * Legacy sync status used by compatibility interaction endpoints.
 */
@Deprecated(forRemoval = false, since = "1.3.0")
public enum SyncStatus {
  PENDING,
  SYNCED,
  FAILED
}
