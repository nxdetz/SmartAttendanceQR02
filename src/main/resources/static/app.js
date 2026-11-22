let courses=[],sess=[],sessOff=0,studs=[],impRows=[];
const $=id=>document.getElementById(id);
async function api(url,opts={}){let r=await fetch('/api'+url,{credentials:'same-origin',headers:{'Content-Type':'application/json'},...opts});let d={};try{d=await r.json()}catch(e){}if(!r.ok)throw Error(d.message||'เกิดข้อผิดพลาด');return d}
function esc(x){return String(x??'').replace(/[&<>"']/g,c=>({'&':'&amp;','<':'&lt;','>':'&gt;','"':'&quot;',"'":'&#39;'}[c]))}
function setOpts(id,opts){let el=$(id),v=el.value;el.innerHTML=opts;if([...el.options].some(o=>o.value===v))el.value=v}

async function signin(){try{let r=await api('/login',{method:'POST',body:JSON.stringify({username:$('user').value,password:$('pass').value})});$('login').classList.add('hide');$('app').classList.remove('hide');$('hello').textContent='สวัสดี '+r.name;await load()}catch(e){$('loginmsg').textContent=e.message}}
async function signout(){await api('/logout',{method:'POST'});location.reload()}
function tab(id){['check','students','courses','report'].forEach(x=>$(x).classList.toggle('hide',x!==id));if(id==='report')loadReport()}

async function load(){
 courses=await api('/courses');
 let opts=courses.map(c=>`<option value="${Number(c.id)}">${esc(c.code)} - ${esc(c.name)}</option>`).join('');
 setOpts('course',opts);setOpts('enrollcourse',opts);setOpts('rcourse',opts);
 await loadStudents();
 $('courselist').innerHTML=courses.map(c=>`<tr><td>${esc(c.code)} - ${esc(c.name)} (${esc(c.teacher_email||'-')})</td><td style="white-space:nowrap;text-align:right"><button class="mini gray" onclick="editCourse(${Number(c.id)})">แก้ไข</button><button class="mini danger" onclick="delCourse(${Number(c.id)})">ลบ</button></td></tr>`).join('');
 showCourseQR();report();refreshSession();loadEnrolled()
}

// ---------- students
async function loadStudents(){let s=await api('/students');studs=s;$('studentlist').innerHTML=s.map(x=>`<tr><td>${esc(x.student_code)} - ${esc(x.full_name)}${x.must_change?' <span class="note">(ยังไม่ตั้งรหัสผ่านใหม่)</span>':''}</td><td style="white-space:nowrap;text-align:right"><button class="mini gray" onclick="editStudent(${Number(x.id)})">แก้ไข</button><button class="mini gray" onclick="resetPw(${Number(x.id)})">รีเซ็ตรหัสผ่าน</button><button class="mini danger" onclick="delStudent(${Number(x.id)})">ลบ</button></td></tr>`).join('')}
async function addStudent(){try{await api('/students',{method:'POST',body:JSON.stringify({code:$('newcode').value,name:$('newname').value})});$('newcode').value='';$('newname').value='';loadStudents()}catch(e){alert(e.message)}}
async function editStudent(id){let x=studs.find(a=>a.id===id);if(!x)return;let code=prompt('รหัสนักศึกษา',x.student_code);if(code===null)return;let name=prompt('ชื่อ-นามสกุล',x.full_name);if(name===null)return;try{await api('/students/'+id,{method:'PUT',body:JSON.stringify({code,name})});await loadStudents();loadEnrolled();report()}catch(e){alert(e.message)}}
async function delStudent(id){let x=studs.find(a=>a.id===id);if(!x||!confirm('ลบนักศึกษา '+x.student_code+' - '+x.full_name+' ?\nการลงทะเบียนและประวัติเช็คชื่อของคนนี้จะถูกลบด้วย'))return;try{await api('/students/'+id,{method:'DELETE'});await loadStudents();loadEnrolled();report()}catch(e){alert(e.message)}}
async function resetPw(id){let x=studs.find(a=>a.id===id);if(!x||!confirm('รีเซ็ตรหัสผ่านของ '+x.student_code+' ให้กลับเป็นรหัสนักศึกษา?\nนักศึกษาต้องตั้งรหัสผ่านใหม่ตอนเข้าครั้งถัดไป'))return;try{await api('/students/'+id+'/reset-password',{method:'POST'});await loadStudents();alert('รีเซ็ตรหัสผ่านแล้ว')}catch(e){alert(e.message)}}

// ---------- courses
async function addCourse(){try{await api('/courses',{method:'POST',body:JSON.stringify({code:$('ccode').value,name:$('cname').value,email:$('email').value})});$('ccode').value='';$('cname').value='';$('email').value='';await load();alert('เพิ่มรายวิชาแล้ว')}catch(e){alert(e.message)}}
async function editCourse(id){let c=courses.find(x=>x.id===id);if(!c)return;let code=prompt('รหัสวิชา',c.code);if(code===null)return;let name=prompt('ชื่อวิชา',c.name);if(name===null)return;let email=prompt('อีเมลอาจารย์',c.teacher_email||'');if(email===null)return;try{await api('/courses/'+id,{method:'PUT',body:JSON.stringify({code,name,email})});await load()}catch(e){alert(e.message)}}
async function delCourse(id){let c=courses.find(x=>x.id===id);if(!c||!confirm('ลบรายวิชา '+c.code+' - '+c.name+' ?\nรายชื่อที่ลงทะเบียนและประวัติเช็คชื่อของวิชานี้จะถูกลบด้วย'))return;try{await api('/courses/'+id,{method:'DELETE'});await load()}catch(e){alert(e.message)}}
function showCourseQR(){let c=courses.find(x=>String(x.id)===String($('course').value));let img=$('courseqr');if(!c||!c.qr_token){img.removeAttribute('src');$('qrurl').textContent='ยังไม่มีรายวิชา';return}let u=location.origin+'/c/'+c.qr_token;img.src='/api/qr?data='+encodeURIComponent(u);$('qrurl').textContent=u}
async function regenCourseQR(){let c=courses.find(x=>String(x.id)===String($('course').value));if(!c||!confirm('สร้าง QR ใหม่ให้วิชา '+c.code+' ?\nQR เดิมที่แจกหรือติดไว้จะใช้ไม่ได้อีก'))return;try{await api('/courses/'+c.id+'/regen-qr',{method:'POST'});await load()}catch(e){alert(e.message)}}

// ---------- enrollment + Excel import
async function enroll(){try{await api('/enroll',{method:'POST',body:JSON.stringify({courseId:$('enrollcourse').value,code:$('enrollcode').value})});$('enrollcode').value='';loadEnrolled();report();alert('ลงทะเบียนแล้ว')}catch(e){alert(e.message)}}
async function loadEnrolled(){let c=$('enrollcourse').value;if(!c){$('enrolledlist').innerHTML='';return}try{let s=await api('/enrollments?courseId='+encodeURIComponent(c));$('enrolledlist').innerHTML=s.map(x=>`<tr><td>${esc(x.student_code)} - ${esc(x.full_name)}</td><td style="text-align:right"><button class="mini danger" onclick="unenroll(${Number(x.id)})">ถอดออก</button></td></tr>`).join('')||'<tr><td>ยังไม่มีนักศึกษาในรายวิชานี้</td></tr>'}catch(e){console.error(e)}}
async function unenroll(sid){let c=$('enrollcourse').value;if(!confirm('ถอดนักศึกษาคนนี้ออกจากรายวิชา?'))return;try{await api('/enroll?courseId='+encodeURIComponent(c)+'&studentId='+sid,{method:'DELETE'});loadEnrolled();report()}catch(e){alert(e.message)}}
function norm(x){return String(x==null?'':x).trim()}
// turns spreadsheet rows (array of arrays) into [{code,name}]; finds the header row when there is one
function parseRows(aoa){
 let hi=-1,ic=-1,iname=-1,isur=-1;
 for(let i=0;i<Math.min(aoa.length,15)&&hi<0;i++){
  let r=(aoa[i]||[]).map(c=>norm(c).toLowerCase());
  let c=r.findIndex(h=>h.includes('รหัส')||h.includes('code')||h==='id'||h.includes('student id'));
  if(c<0)continue;
  let full=r.findIndex((h,k)=>k!==c&&h.includes('ชื่อ')&&h.includes('สกุล'));
  let first=r.findIndex((h,k)=>k!==c&&k!==full&&(h.includes('ชื่อ')||h.includes('name'))&&!h.includes('สกุล')&&!h.includes('surname'));
  let last=r.findIndex((h,k)=>k!==c&&k!==full&&k!==first&&(h.includes('สกุล')||h.includes('surname')||h.includes('last')));
  if(full<0&&first<0)continue;
  hi=i;ic=c;iname=full>=0?full:first;isur=full>=0?-1:last
 }
 let start,ci,ni,si;
 if(hi>=0){start=hi+1;ci=ic;ni=iname;si=isur}else{start=0;ci=0;ni=1;si=-1}
 let out=[];
 for(let i=start;i<aoa.length;i++){let r=aoa[i]||[];let code=norm(r[ci]);let name=norm(r[ni]);if(si>=0)name=(name+' '+norm(r[si])).trim();if(!code||!name)continue;out.push({code,name})}
 return out
}
async function pickFile(inp){
 let f=inp.files[0];impRows=[];$('ximpbtn').classList.add('hide');
 if(!f){$('ximpmsg').textContent='';return}
 if(!window.XLSX){$('ximpmsg').textContent='โหลดตัวอ่านไฟล์ Excel ไม่สำเร็จ กรุณาเชื่อมต่ออินเทอร์เน็ตแล้วรีเฟรชหน้า';return}
 try{
  let wb=/\.csv$/i.test(f.name)?XLSX.read(await f.text(),{type:'string'}):XLSX.read(await f.arrayBuffer(),{type:'array'});
  let aoa=XLSX.utils.sheet_to_json(wb.Sheets[wb.SheetNames[0]],{header:1,defval:'',raw:true});
  impRows=parseRows(aoa);
  if(!impRows.length){$('ximpmsg').textContent='ไม่พบข้อมูล ไฟล์ต้องมีคอลัมน์รหัสนักศึกษาและชื่อ-นามสกุล';return}
  $('ximpmsg').textContent='พบนักศึกษา '+impRows.length+' คน เช่น '+impRows.slice(0,3).map(x=>x.code+' '+x.name).join(', ');
  $('ximpbtn').classList.remove('hide')
 }catch(e){$('ximpmsg').textContent='อ่านไฟล์ไม่สำเร็จ: '+e.message}
}
async function doImport(){
 let c=$('enrollcourse').value;if(!c||!impRows.length)return;
 if(!confirm('นำเข้า '+impRows.length+' คน เข้ารายวิชาที่เลือก?'))return;
 try{
  let r=await api('/courses/'+c+'/import',{method:'POST',body:JSON.stringify({rows:impRows})});
  alert('นำเข้าเสร็จแล้ว\nนักศึกษาใหม่ '+r.newStudents+' คน\nลงทะเบียนเข้าวิชา '+r.enrolled+' คน\nอยู่ในวิชานี้อยู่แล้ว '+r.already+' คน\nข้าม '+r.skipped+' แถว');
  $('xfile').value='';impRows=[];$('ximpbtn').classList.add('hide');$('ximpmsg').textContent='';
  await loadStudents();loadEnrolled();report()
 }catch(e){alert(e.message)}
}

// ---------- check-in window + live list
async function report(){if(!$('course').value)return;try{let r=await api('/report?courseId='+$('course').value);$('today').innerHTML=r.students.filter(x=>x.status==='มาเรียน').map(x=>`<tr><td>${esc(x.student_code)} - ${esc(x.full_name)} | ${esc(x.attendance_time||'')}</td></tr>`).join('')}catch(e){console.error(e)}}
async function refreshSession(){try{let r=await api('/sessions/active');sess=r.sessions;sessOff=r.now-Date.now();renderSession();if(sess.some(x=>String(x.courseId)===String($('course').value)))report()}catch(e){}}
function renderSession(){let m=$('sessmsg');if(!m)return;let x=sess.find(a=>String(a.courseId)===String($('course').value));if(!x){m.className='';m.textContent='ยังไม่เปิดรับเช็คชื่อสำหรับรายวิชานี้';return}let left=Math.floor((x.expiresAt-(Date.now()+sessOff))/1000);if(left<=0){m.className='';m.textContent='หมดเวลารับเช็คชื่อแล้ว';return}m.className='ok';m.textContent='กำลังเปิดรับเช็คชื่อ เหลือ '+Math.floor(left/60)+':'+String(left%60).padStart(2,'0')+' นาที'}
async function openSession(){try{await api('/sessions/open',{method:'POST',body:JSON.stringify({courseId:$('course').value,minutes:$('minutes').value})});await refreshSession()}catch(e){alert(e.message)}}
async function closeSession(){try{await api('/sessions/close',{method:'POST',body:JSON.stringify({courseId:$('course').value})});await refreshSession()}catch(e){alert(e.message)}}
setInterval(()=>{if(!$('app').classList.contains('hide'))renderSession()},1000);
setInterval(()=>{if(!$('app').classList.contains('hide'))refreshSession()},5000);

// ---------- report tab
function todayStr(){return new Date().toLocaleDateString('en-CA')}
async function loadReport(){let c=$('rcourse').value;if(!$('rdate').value)$('rdate').value=todayStr();if(!c){$('stats').textContent='ยังไม่มีรายวิชา';$('reportlist').innerHTML='';return}try{let r=await api('/report?courseId='+encodeURIComponent(c)+'&date='+encodeURIComponent($('rdate').value));$('stats').textContent='ทั้งหมด '+r.total+' คน | มาเรียน '+r.present+' คน | ขาดเรียน '+r.absent+' คน';$('reportlist').innerHTML=r.students.map(x=>{let p=x.status==='มาเรียน';return `<tr><td>${esc(x.student_code)} - ${esc(x.full_name)} | ${esc(x.status)} | ${esc(x.attendance_time||'-')}</td><td style="white-space:nowrap;text-align:right">${p?`<button class="mini danger" onclick="delAtt(${Number(x.student_id)})">ลบการเช็คชื่อ</button>`:`<button class="mini green" onclick="markAtt(${Number(x.student_id)})">เช็คชื่อให้</button>`}</td></tr>`}).join('')}catch(e){alert(e.message)}}
async function delAtt(sid){if(!confirm('ลบการเช็คชื่อของวันที่เลือก?'))return;try{await api('/attendance?courseId='+encodeURIComponent($('rcourse').value)+'&studentId='+sid+'&date='+encodeURIComponent($('rdate').value),{method:'DELETE'});loadReport();report()}catch(e){alert(e.message)}}
async function markAtt(sid){try{await api('/attendance/manual',{method:'POST',body:JSON.stringify({courseId:$('rcourse').value,studentId:String(sid),date:$('rdate').value})});loadReport();report()}catch(e){alert(e.message)}}

(async()=>{try{let r=await api('/me');$('login').classList.add('hide');$('app').classList.remove('hide');$('hello').textContent='สวัสดี '+r.name;await load()}catch(e){}})();
