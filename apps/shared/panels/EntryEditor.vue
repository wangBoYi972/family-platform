<script setup>
import { onMounted, ref } from "vue";
import { request, upload, loadImageUrl } from "@app/utils/request";
const props = defineProps({ kind: String, entry: Object });
const emit = defineEmits(["changed"]);
const session = ref(null), opened = ref(false), busy = ref(false), form = ref({});
const photos = ref([]), progress = ref("");
let generation = 0;
async function openPhotos() {
  const current = ++generation;
  photos.value = String(form.value.fileIds || "").split(",").filter(Boolean).map(id => ({ id, url: "" }));
  await Promise.all(photos.value.map(async photo => {
    try { const url = await loadImageUrl(photo.id); if(current === generation) photo.url = url; }
    catch { /* 下载失败仍保留文件标识，避免编辑时丢失原有照片。 */ }
  }));
}
function choosePhotos() {
  if(busy.value || photos.value.length >= 30) return;
  const current = generation;
  uni.chooseImage({ count: Math.min(9, 30 - photos.value.length), sizeType: ["compressed"], success: result => {
    if(current !== generation || busy.value || !opened.value) return;
    for(const path of result.tempFilePaths.slice(0,30-photos.value.length)) photos.value.push({ id:null, url:path });
  }, fail: error => { if(!error.errMsg?.includes('cancel')) uni.showToast({title:'无法选择照片，请重试',icon:'none'}); } });
}
function previewPhoto(photo) {
  const urls = photos.value.map(item=>item.url).filter(Boolean);
  if(photo.url) uni.previewImage({urls,current:photo.url});
}
onMounted(async () => { try { session.value = await request("/auth/session"); } catch { /* 会话校验失败时隐藏管理入口。 */ } });
function allowed() { return session.value && (session.value.role === "ADMIN" || (props.kind !== "notices" && (!props.entry || String(props.entry.creatorId) === String(session.value.userId)))); }
function open() { form.value = { title: "", body: "", eventDate: "", yearly: false, pinned: false, visibility: "FAMILY", fileIds: null, ...props.entry }; opened.value = true; if(props.kind === 'albums') openPhotos(); }
async function save() {
  if(busy.value) return;
  if(!form.value.title.trim() || (props.kind === "calendar" && !form.value.eventDate)) return uni.showToast({title:"请填写标题和必填日期",icon:"none"});
  busy.value=true;
  try {
    if(props.kind === 'albums') {
      for(let i=0;i<photos.value.length;i++) {
        const photo=photos.value[i];
        if(photo.id) continue;
        progress.value=`正在上传第 ${i+1} / ${photos.value.length} 张照片`;
        const file=await upload('/files',photo.url,{visibility:form.value.visibility});
        // 保留已上传文件标识，后续失败重试时不重复上传成功的照片。
        photo.id=file.id;
      }
      form.value.fileIds=photos.value.map(photo=>photo.id).join(',') || null;
    }
    const {title,body,eventDate,yearly,pinned,visibility,fileIds}=form.value;
    await request('/'+props.kind+(props.entry?'/'+props.entry.id:''),{method:props.entry?'PUT':'POST',data:{title:title.trim(),body,eventDate:eventDate||null,yearly,pinned,visibility,fileIds}});
    opened.value=false;emit('changed');uni.showToast({title:'已保存',icon:'none'});
  } catch(e){uni.showToast({title:e.message||'保存失败，内容已保留',icon:'none'});} finally {busy.value=false;progress.value='';}
}
async function remove(){
  if(busy.value)return;busy.value=true;
  try{const result=await uni.showModal({title:'确认删除',content:'删除“'+props.entry.title+'”后无法恢复，确定继续吗？',confirmText:'删除'});if(!result.confirm)return;await request('/'+props.kind+'/'+props.entry.id,{method:'DELETE'});emit('changed');}
  catch(e){uni.showToast({title:e.message||'删除失败',icon:'none'});}finally{busy.value=false;}
}
</script>
<template><view v-if="allowed()" class="editor"><view class="actions"><button size="mini" :disabled="busy" @click="open">{{entry?'编辑':kind==='albums'?'＋ 创建相册':'＋ 发布公告'}}</button><button v-if="entry" size="mini" :disabled="busy" @click="remove">删除</button></view><view v-if="opened" class="form"><input v-model="form.title" maxlength="120" :disabled="busy" placeholder="标题（必填）"/><textarea v-model="form.body" maxlength="10000" :disabled="busy" placeholder="详细内容"/><picker v-if="kind==='calendar'" mode="date" :value="form.eventDate" :disabled="busy" @change="form.eventDate=$event.detail.value"><view>日期：{{form.eventDate}}</view></picker><view v-if="kind==='calendar'">每年纪念<switch :checked="form.yearly" :disabled="busy" @change="form.yearly=$event.detail.value"/></view><view v-if="kind==='notices'">置顶公告<switch :checked="form.pinned" :disabled="busy" @change="form.pinned=$event.detail.value"/></view><view v-if="kind==='albums'" class="photo-section"><text class="hint">照片 {{photos.length}} / 30 · 移除仅解除相册关联</text><view class="photo-grid"><view v-for="(photo,index) in photos" :key="photo.id || photo.url" class="photo"><image v-if="photo.url" :src="photo.url" mode="aspectFill" @click="previewPhoto(photo)"/><text v-else class="hint">照片暂不可预览</text><button size="mini" :disabled="busy" @click="photos.splice(index,1)">移除</button></view></view><button size="mini" :disabled="busy || photos.length>=30" @click="choosePhotos">＋ 添加照片</button><text v-if="progress" class="hint">{{progress}}</text></view><view class="actions"><button size="mini" :loading="busy" :disabled="busy" @click="save">保存</button><button size="mini" :disabled="busy" @click="opened=false">取消</button></view></view></view></template>
<style scoped>
.photo-section{display:flex;flex-direction:column;gap:12px}.photo-grid{display:grid;grid-template-columns:repeat(3,minmax(0,1fr));gap:8px}.photo image{width:100%;height:95px;border-radius:8px}.photo button{font-size:10px}.editor{margin-top:12px}.actions{display:flex;gap:10px}.actions button{margin:0;font-size:12px;background:var(--c-primary-soft);color:var(--c-primary);border-radius:9px}.form{display:flex;flex-direction:column;gap:14px;padding:18px;background:var(--c-surface-2);border:1px solid var(--c-line);border-radius:12px;margin-top:12px;font-size:12px}.form input,.form textarea{background:white;border:1px solid var(--c-line);border-radius:8px;padding:12px;width:100%;box-sizing:border-box;font-size:13px}.form input{height:45px}.form textarea{height:125px}.hint{font-size:11px;color:var(--c-muted)}
</style>
