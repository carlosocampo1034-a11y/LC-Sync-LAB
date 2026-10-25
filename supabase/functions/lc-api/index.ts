// L&C QA-only. Auth and authorization enforced server-side on every order request.
import { createClient } from "https://esm.sh/@supabase/supabase-js@2.57.0";
import { aplicar, versionValida, type Rol } from "./orders.ts";
const H = { "content-type":"application/json", "access-control-allow-origin":"*", "access-control-allow-headers":"authorization,apikey,x-client-info,content-type", "access-control-allow-methods":"OPTIONS,GET,POST,PATCH" };
function out(status:number, value:unknown) { return new Response(JSON.stringify(value), {status,headers:H}); }
const url = Deno.env.get("SUPABASE_URL")!;
const key = Deno.env.get("SUPABASE_SERVICE_ROLE_KEY")!;
const db = createClient(url,key,{auth:{persistSession:false,autoRefreshToken:false}});
type Actor={id:string,role:Rol};
async function authenticate(req:Request):Promise<Actor|Response>{
 const token=(req.headers.get("authorization")||"").match(/^Bearer (.+)$/i)?.[1];
 if(!token) return out(401,{error:"token_required"});
 const {data,error}=await db.auth.getUser(token);
 if(error||!data.user) return out(401,{error:"invalid_token"});
 const {data:p,error:pe}=await db.from("profiles").select("role").eq("id",data.user.id).maybeSingle();
 if(pe||!p||!["ADMIN","DISPATCHER","TECHNICIAN","CUSTOMER"].includes(p.role))
  return out(403,{error:"profile_not_authorized"});
 return {id:data.user.id,role:p.role as Rol};
}
function hasAccess(u:Actor,j:any):boolean {
 return ["ADMIN","DISPATCHER"].includes(u.role) || (u.role==="CUSTOMER"&&j.customer_id===u.id)
     || (u.role==="TECHNICIAN"&&j.technician_id===u.id);
}
Deno.serve(async(req)=>{
 if(req.method==="OPTIONS")return new Response(null,{status:204,headers:H});
 const path=new URL(req.url).pathname;
 const route=(path.split("/lc-api").pop()||"/").replace(/\/+$/,"")||"/";
 try {
  if(route==="/health"&&req.method==="GET")return out(200,{status:"ok",environment:"LC-Sync-QA",version:"qa-1"});
  const user=await authenticate(req);
  if(user instanceof Response)return user;
  if(route==="/auth/me"&&req.method==="GET")return out(200,{id:user.id,role:user.role});
  if((route==="/protected-test"||route==="/tech-probe")&&req.method==="GET")return out(200,{ok:true,role:user.role});
  const collection=route==="/orders"||route==="/jobs";
  const m=route.match(/^\/(?:orders|jobs)\/(\d+)$/);
  if(collection&&req.method==="GET"){
   let q=db.from("jobs").select("*").order("updated_at",{ascending:false}).limit(100);
   if(user.role==="CUSTOMER")q=q.eq("customer_id",user.id);
   if(user.role==="TECHNICIAN")q=q.eq("technician_id",user.id);
   const {data,error}=await q;
   return error?out(500,{error:"list_failed"}):out(200,data||[]);
  }
  if(collection&&req.method==="POST"){
   if(!["CUSTOMER","DISPATCHER","ADMIN"].includes(user.role))return out(403,{error:"role_denied"});
   const b=await req.json().catch(()=>null);
   if(typeof b?.cliente!=="string"||!b.cliente.trim()||typeof b?.descripcion!=="string"||!b.descripcion.trim())
    return out(422,{error:"cliente_and_descripcion_required"});
   const customer=user.role==="CUSTOMER"?user.id:(b.customer_id||null);
   if(customer&&user.role!=="CUSTOMER"){
    const {data:p}=await db.from("profiles").select("role").eq("id",customer).maybeSingle();
    if(p?.role!=="CUSTOMER")return out(422,{error:"invalid_customer"});
   }
   const now=Date.now();
   const {data,error}=await db.from("jobs").insert({
    created_by:user.id,customer_id:customer,origin:user.role,version:1,estado:"PENDIENTE",
    cliente:b.cliente.trim().slice(0,200),descripcion:b.descripcion.trim().slice(0,4000),
    direccion:typeof b.direccion==="string"?b.direccion.slice(0,400):null,
    telefono:typeof b.telefono==="string"?b.telefono.slice(0,80):null,
    categoria:typeof b.categoria==="string"?b.categoria.slice(0,80):null,
    created_at:now,updated_at:now
   }).select("id,version,estado").single();
   return error?out(500,{error:"create_failed"}):out(201,data);
  }
  if(m&&req.method==="GET"){
   const {data,error}=await db.from("jobs").select("*").eq("id",Number(m[1])).maybeSingle();
   if(error)return out(500,{error:"read_failed"});
   if(!data||!hasAccess(user,data))return out(404,{error:"not_found"});
   return out(200,data);
  }
  if(m&&req.method==="PATCH"){
   if(!["ADMIN","DISPATCHER","TECHNICIAN"].includes(user.role))return out(403,{error:"role_denied"});
   const b=await req.json().catch(()=>null);
   if(!b||!versionValida(b.version)||!Number.isSafeInteger(b.updatedAt)||b.updatedAt<=0||
      typeof b.device!=="string"||b.device.length<1||b.device.length>128)
    return out(422,{error:"version_device_updatedAt_required"});
   const id=Number(m[1]);
   const {data:current,error:readErr}=await db.from("jobs").select("*").eq("id",id).maybeSingle();
   if(readErr)return out(500,{error:"read_failed"});
   if(!current||!hasAccess(user,current))return out(404,{error:"not_found"});
   const {data:rec}=await db.from("sync_receipts").select("status,version").eq("user_id",user.id)
    .eq("device_id",b.device).eq("job_id",id).eq("client_updated_at",b.updatedAt).maybeSingle();
   if(rec)return out(200,{id,...rec,replay:true});
   const changes:Record<string,unknown>={};
   for(const f of ["estado","estado_dispatch","notas","prioridad","horas","programado_para","technician_id"])
    if(Object.prototype.hasOwnProperty.call(b,f))changes[f]=b[f];
   if("technician_id" in changes){
    if(!["ADMIN","DISPATCHER"].includes(user.role))return out(403,{error:"assign_denied"});
    const target=changes.technician_id;
    if(target!==null){
     if(typeof target!=="string"||!/^[0-9a-f-]{36}$/i.test(target))return out(422,{error:"invalid_technician"});
     const {data:p}=await db.from("profiles").select("role,technician_code").eq("id",target).maybeSingle();
     if(p?.role!=="TECHNICIAN")return out(422,{error:"invalid_technician"});
     changes.technician_code=p.technician_code||null;
    }else changes.technician_code=null;
   }
   const {data:hist,error:histErr}=await db.from("job_history").select("field").eq("job_id",id).gt("version",b.version);
   if(histErr)return out(500,{error:"history_failed"});
   const merge=aplicar(current as any,b.version,changes,new Set<string>((hist||[]).map((h:any)=>h.field)),user.role);
   if(merge.result.status==="REJECTED")return out(422,{id,...merge.result});
   if(merge.conflictos.length){
    await db.from("job_conflicts").insert(merge.conflictos.map(f=>({job_id:id,field:f,
      base_version:b.version,server_version:current.version,server_value:String(current[f]??""),
      client_value:String(changes[f]??""),actor:user.id})));
    return out(409,{id,...merge.result});
   }
   const fields=Object.keys(merge.cambios);
   const nextVersion=current.version+(fields.length?1:0);
   if(fields.length){
    const {data:written,error:writeErr}=await db.from("jobs").update({
      ...merge.cambios,version:nextVersion,updated_at:Date.now()
    }).eq("id",id).eq("version",current.version).select("id").maybeSingle();
    if(writeErr)return out(500,{error:"update_failed"});
    if(!written)return out(409,{id,status:"CONFLICT",reason:"concurrent_update"});
    const {error:eventErr}=await db.from("job_history").insert(fields.map(field=>({
      job_id:id,version:nextVersion,field,actor:user.id,
      old_value:String(current[field]??""),new_value:String(merge.cambios[field]??"")
    })));
    if(eventErr)return out(500,{error:"history_write_failed",id,version:nextVersion});
   }
   const {error:receiptErr}=await db.from("sync_receipts").insert({
     user_id:user.id,device_id:b.device,job_id:id,client_updated_at:b.updatedAt,
     status:"ACCEPTED",version:nextVersion
   });
   if(receiptErr&&receiptErr.code!=="23505")return out(500,{error:"receipt_write_failed",id,version:nextVersion});
   return out(200,{id,status:"ACCEPTED",version:nextVersion});
  }
  return out(404,{error:"route_not_found"});
 }catch(e){console.error("QA lc-api",e instanceof Error?e.name:"unknown");return out(500,{error:"internal_error"});}
});