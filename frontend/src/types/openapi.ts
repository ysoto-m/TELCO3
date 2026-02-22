export type Role = 'AGENT' | 'REPORT_ADMIN';
export type SyncStatus = 'PENDING'|'SYNCED'|'FAILED';
export interface LoginResponse { accessToken:string; role:Role; username:string }
export interface AgentContextResponse { lead:any; customer:any; phones:any[]; interactions:any[]; dispoOptions:string[] }
