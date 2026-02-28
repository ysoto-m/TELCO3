import api from './client';

export const login = (payload:{username:string,password:string})=>api.post('/api/auth/login',payload).then(r=>r.data);
export const getSettings = ()=>api.get('/api/settings/vicidial').then(r=>r.data);
export const putSettings = (payload:any)=>api.put('/api/settings/vicidial',payload).then(r=>r.data);

export const getAgentProfile = ()=>api.get('/api/agent/profile').then(r=>r.data);
export const updateAgentProfilePass = (payload:{agentPass:string})=>api.put('/api/agent/profile/agent-pass',payload).then(r=>r.data);

export const connectVicidialPhone = (payload:{phoneLogin:string})=>api.post('/api/agent/vicidial/phone/connect',payload).then(r=>r.data);
export const disconnectVicidialPhone = ()=>api.post('/api/agent/vicidial/phone/disconnect').then(r=>r.data);
export const getVicidialCampaigns = ()=>api.get('/api/agent/vicidial/campaigns').then(r=>r.data);
export const connectVicidialCampaign = (payload:{campaignId:string;rememberCredentials?:boolean})=>api.post('/api/agent/vicidial/campaign/connect',payload).then(r=>r.data);
export const getVicidialStatus = ()=>api.get('/api/agent/vicidial/status').then(r=>r.data);

export const getActiveLead = ()=>api.get('/api/agent/active-lead').then(r=>r.data);
export const getContext = (p:{leadId?:number})=>api.get('/api/agent/context',{params:p}).then(r=>r.data);
export const dialNext = (payload:{campaignId:string})=>api.post('/api/agent/vicidial/dial/next',payload).then(r=>r.data);
export const manualDial = (payload:{campaignId:string;phoneNumber:string;phoneCode?:string;dialTimeout?:number;dialPrefix?:string;preview?:'NO'|'YES'})=>api.post('/api/agent/vicidial/dial/manual',payload).then(r=>r.data);
export const saveInteraction = (payload:any)=>api.post('/api/agent/interactions',payload).then(r=>r.data);
export const retryInteraction = (id:number)=>api.post(`/api/agent/interactions/${id}/retry-vicidial`).then(r=>r.data);
export const previewAction = (payload:any)=>api.post('/api/agent/preview-action',payload).then(r=>r.data);
export const pauseAction = (payload:any)=>api.post('/api/agent/pause',payload).then(r=>r.data);
export const importCsv = (file:File)=>{const f=new FormData();f.append('file',file);return api.post('/api/vicidial/leads/import',f).then(r=>r.data)};
export const reportSummary=(p:any)=>api.get('/api/reports/summary',{params:p}).then(r=>r.data);

export const adminSummary = ()=>api.get('/api/admin/summary').then(r=>r.data);
export const adminAgents = ()=>api.get('/api/admin/agents').then(r=>r.data);
export const adminCampaigns = ()=>api.get('/api/admin/campaigns').then(r=>r.data);
export const adminInteractions = (params:any)=>api.get('/api/admin/interactions',{params}).then(r=>r.data);
export const adminExportCsvUrl = (params:any)=> {
  const query = new URLSearchParams(params).toString();
  return `${api.defaults.baseURL}/api/admin/interactions/export.csv?${query}`;
};
export const adminUsers = ()=>api.get('/api/admin/users').then(r=>r.data);
export const adminCreateUser = (payload:any)=>api.post('/api/admin/users',payload).then(r=>r.data);
export const adminUpdateUser = (id:number,payload:any)=>api.put(`/api/admin/users/${id}`,payload).then(r=>r.data);
export const adminSettings = ()=>api.get('/api/admin/settings').then(r=>r.data);
export const adminUpdateSettings = (payload:any)=>api.put('/api/admin/settings',payload).then(r=>r.data);
export const adminUpdateAgentPass = (id:number,payload:{agentPass:string})=>api.put(`/api/admin/users/${id}/agent-pass`,payload).then(r=>r.data);
