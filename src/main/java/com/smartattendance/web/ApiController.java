package com.smartattendance.web;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
import java.util.concurrent.ConcurrentHashMap;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;
import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;
import com.google.zxing.BarcodeFormat;
import com.google.zxing.EncodeHintType;
import com.google.zxing.common.BitMatrix;
import com.google.zxing.qrcode.QRCodeWriter;

@RestController @RequestMapping("/api")
public class ApiController{
 static final ZoneId ZONE=ZoneId.of(System.getenv().getOrDefault("APP_TZ","Asia/Bangkok"));
 static final DateTimeFormatter TIME=DateTimeFormatter.ofPattern("HH:mm:ss");
 static final Map<String,long[]> FAILS=new ConcurrentHashMap<>();
 private final JdbcTemplate db;private final ObjectProvider<JavaMailSender> mail;private final BCryptPasswordEncoder enc=new BCryptPasswordEncoder();
 public ApiController(JdbcTemplate d,ObjectProvider<JavaMailSender> m){db=d;mail=m;}

 // ---------- helpers
 void auth(HttpSession s){if(s.getAttribute("user")==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"กรุณาเข้าสู่ระบบ");}
 int studentId(HttpSession s){Object o=s.getAttribute("student");if(o==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"กรุณาเข้าสู่ระบบ");return ((Number)o).intValue();}
 String val(Map<String,String>b,String k){return b.getOrDefault(k,"").trim();}
 String str(Object o){return o==null?"":o.toString().trim();}
 int toInt(String v){try{return Integer.parseInt(v.trim());}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ข้อมูลไม่ถูกต้อง");}}
 String newToken(){return UUID.randomUUID().toString().replace("-","");}
 boolean mustChange(Object o){return o==null||((Number)o).intValue()!=0;}
 String dateOf(String v){
  if(v==null||v.isBlank())return LocalDate.now(ZONE).toString();
  try{return LocalDate.parse(v.trim()).toString();}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"รูปแบบวันที่ไม่ถูกต้อง");}
 }
 // limits repeated wrong passwords: 8 failures -> wait 10 minutes
 void throttle(String k){long[] f=FAILS.get(k);if(f!=null&&f[0]>=8&&System.currentTimeMillis()-f[1]<600000)throw new ResponseStatusException(HttpStatus.TOO_MANY_REQUESTS,"ลองผิดหลายครั้ง กรุณารอ 10 นาทีแล้วลองใหม่");}
 void fail(String k){FAILS.compute(k,(x,f)->{long now=System.currentTimeMillis();if(f==null||now-f[1]>=600000)return new long[]{1,now};f[0]++;return f;});}
 // a student whose password_hash is empty still uses the default password = student code
 boolean passwordOk(String input,String code,Object hash){
  if(hash==null||hash.toString().isEmpty())return MessageDigest.isEqual(input.getBytes(StandardCharsets.UTF_8),code.getBytes(StandardCharsets.UTF_8));
  return enc.matches(input,hash.toString());
 }

 // ---------- teacher login
 @PostMapping("/login") Map<String,Object> login(@RequestBody Map<String,String>b,HttpSession s){
  String key="t:"+val(b,"username");throttle(key);
  var r=db.queryForList("SELECT password_hash,full_name FROM users WHERE username=?",val(b,"username"));
  if(r.isEmpty()||!enc.matches(val(b,"password"),r.get(0).get("password_hash").toString())){fail(key);throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง");}
  FAILS.remove(key);
  s.setAttribute("user",val(b,"username"));s.setAttribute("name",r.get(0).get("full_name"));return Map.of("ok",true,"name",r.get(0).get("full_name"));
 }
 @GetMapping("/me") Map<String,Object> me(HttpSession s){auth(s);return Map.of("name",s.getAttribute("name"));}
 @PostMapping("/logout") Map<String,Object> logout(HttpSession s){s.invalidate();return Map.of("ok",true);}

 // ---------- courses
 @GetMapping("/courses") List<Map<String,Object>> courses(HttpSession s){auth(s);return db.queryForList("SELECT * FROM courses ORDER BY code");}
 @PostMapping("/courses") Map<String,Object> addCourse(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);
  if(val(b,"code").isEmpty()||val(b,"name").isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"กรุณากรอกรหัสวิชาและชื่อวิชา");
  try{db.update("INSERT INTO courses(code,name,teacher_email,qr_token) VALUES(?,?,?,?)",val(b,"code"),val(b,"name"),val(b,"email"),newToken());return Map.of("ok",true);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสวิชาซ้ำหรือข้อมูลไม่ครบ");}
 }
 @PutMapping("/courses/{id}") Map<String,Object> editCourse(@PathVariable int id,@RequestBody Map<String,String>b,HttpSession s){
  auth(s);
  if(val(b,"code").isEmpty()||val(b,"name").isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"กรุณากรอกรหัสวิชาและชื่อวิชา");
  int n;
  try{n=db.update("UPDATE courses SET code=?,name=?,teacher_email=? WHERE id=?",val(b,"code"),val(b,"name"),val(b,"email"),id);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสวิชาซ้ำ");}
  if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบรายวิชา");
  return Map.of("ok",true);
 }
 @DeleteMapping("/courses/{id}") Map<String,Object> deleteCourse(@PathVariable int id,HttpSession s){
  auth(s);
  db.update("DELETE FROM attendance WHERE course_id=?",id);
  db.update("DELETE FROM enrollments WHERE course_id=?",id);
  db.update("DELETE FROM sessions WHERE course_id=?",id);
  db.update("DELETE FROM courses WHERE id=?",id);
  return Map.of("ok",true);
 }
 @PostMapping("/courses/{id}/regen-qr") Map<String,Object> regenCourseQr(@PathVariable int id,HttpSession s){
  auth(s);
  int n=db.update("UPDATE courses SET qr_token=? WHERE id=?",newToken(),id);
  if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบรายวิชา");
  return Map.of("ok",true);
 }
 // bulk import: rows come from an Excel/CSV file parsed in the browser
 @PostMapping("/courses/{id}/import") Map<String,Object> importStudents(@PathVariable int id,@RequestBody Map<String,Object> b,HttpSession s){
  auth(s);
  if(db.queryForList("SELECT 1 FROM courses WHERE id=?",id).isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบรายวิชา");
  if(!(b.get("rows") instanceof List<?> rows))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ไม่พบข้อมูลนักศึกษา");
  if(rows.size()>3000)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"นำเข้าได้ไม่เกิน 3000 คนต่อครั้ง");
  int[] n={0,0,0,0}; // new students, newly enrolled, already enrolled, skipped rows
  new org.springframework.transaction.support.TransactionTemplate(new org.springframework.jdbc.datasource.DataSourceTransactionManager(Objects.requireNonNull(db.getDataSource()))).executeWithoutResult(st->{
   for(Object o:rows){
    if(!(o instanceof Map<?,?> m)){n[3]++;continue;}
    String code=str(m.get("code")),name=str(m.get("name"));
    if(code.isEmpty()||name.isEmpty()){n[3]++;continue;}
    n[0]+=db.update("INSERT OR IGNORE INTO students(student_code,full_name,must_change) VALUES(?,?,1)",code,name);
    int added=db.update("INSERT OR IGNORE INTO enrollments(course_id,student_id) SELECT ?,id FROM students WHERE student_code=?",id,code);
    if(added>0)n[1]++;else n[2]++;
   }
  });
  return Map.of("ok",true,"newStudents",n[0],"enrolled",n[1],"already",n[2],"skipped",n[3]);
 }

 // ---------- students (managed by the teacher)
 @GetMapping("/students") List<Map<String,Object>> students(HttpSession s){auth(s);return db.queryForList("SELECT id,student_code,full_name,must_change FROM students ORDER BY student_code");}
 @PostMapping("/students") Map<String,Object> addStudent(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);
  String code=val(b,"code"),name=val(b,"name"),cid=val(b,"courseId");
  if(code.isEmpty()||name.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"กรุณากรอกรหัสและชื่อนักศึกษา");
  boolean existing=false;
  try{db.update("INSERT INTO students(student_code,full_name,must_change) VALUES(?,?,1)",code,name);}
  catch(Exception e){
   // code already exists: when adding from a course page, just enroll that existing student
   if(cid.isEmpty()||db.queryForList("SELECT 1 FROM students WHERE student_code=?",code).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสนักศึกษาซ้ำหรือข้อมูลไม่ครบ");
   existing=true;
  }
  if(!cid.isEmpty())db.update("INSERT OR IGNORE INTO enrollments(course_id,student_id) SELECT ?,id FROM students WHERE student_code=?",toInt(cid),code);
  return Map.of("ok",true,"existing",existing);
 }
 @PutMapping("/students/{id}") Map<String,Object> editStudent(@PathVariable int id,@RequestBody Map<String,String>b,HttpSession s){
  auth(s);String code=val(b,"code"),name=val(b,"name");
  if(code.isEmpty()||name.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"กรุณากรอกรหัสและชื่อนักศึกษา");
  int n;
  try{n=db.update("UPDATE students SET student_code=?,full_name=? WHERE id=?",code,name,id);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสนักศึกษาซ้ำ");}
  if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบนักศึกษา");
  return Map.of("ok",true);
 }
 @DeleteMapping("/students/{id}") Map<String,Object> deleteStudent(@PathVariable int id,HttpSession s){
  auth(s);
  db.update("DELETE FROM attendance WHERE student_id=?",id);
  db.update("DELETE FROM enrollments WHERE student_id=?",id);
  db.update("DELETE FROM students WHERE id=?",id);
  return Map.of("ok",true);
 }
 @PostMapping("/students/{id}/reset-password") Map<String,Object> resetPassword(@PathVariable int id,HttpSession s){
  auth(s);
  int n=db.update("UPDATE students SET password_hash=NULL,must_change=1 WHERE id=?",id);
  if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบนักศึกษา");
  return Map.of("ok",true);
 }

 // ---------- enrollments
 @PostMapping("/enroll") Map<String,Object> enroll(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);
  try{db.update("INSERT INTO enrollments(course_id,student_id) SELECT ?,id FROM students WHERE student_code=?",Integer.parseInt(val(b,"courseId")),val(b,"code"));return Map.of("ok",true);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ไม่พบรหัสนักศึกษา หรือเคยลงทะเบียนแล้ว");}
 }
 @GetMapping("/enrollments") List<Map<String,Object>> enrollments(@RequestParam int courseId,HttpSession s){
  auth(s);
  return db.queryForList("SELECT s.id,s.student_code,s.full_name,s.must_change FROM enrollments e JOIN students s ON s.id=e.student_id WHERE e.course_id=? ORDER BY s.student_code",courseId);
 }
 @DeleteMapping("/enroll") Map<String,Object> unenroll(@RequestParam int courseId,@RequestParam int studentId,HttpSession s){
  auth(s);
  db.update("DELETE FROM enrollments WHERE course_id=? AND student_id=?",courseId,studentId);
  return Map.of("ok",true);
 }

 // ---------- attendance record (shared by student check-in)
 Map<String,Object> record(int cid,Object sid,String code,String fullName,String courseName,String to){
  String date=LocalDate.now(ZONE).toString(),time=LocalTime.now(ZONE).format(TIME);
  try{db.update("INSERT INTO attendance(course_id,student_id,attendance_date,attendance_time,status) VALUES(?,?,?,?,?)",cid,sid,date,time,"มาเรียน");}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"เช็คชื่อวันนี้แล้ว");}
  boolean sent=false;
  if(!to.isBlank()&&mail.getIfAvailable()!=null)try{
   SimpleMailMessage m=new SimpleMailMessage();m.setTo(to);m.setSubject("แจ้งเตือนการเข้าเรียน - "+courseName);
   m.setText("รายวิชา: "+courseName+"\nรหัส: "+code+"\nชื่อ: "+fullName+"\nวันที่: "+date+" เวลา: "+time);
   mail.getObject().send(m);sent=true;
  }catch(Exception ignored){}
  return Map.of("ok",true,"name",fullName,"course",courseName,"time",time,"emailSent",sent);
 }

 // ---------- teacher: open / close a check-in window for a course
 @PostMapping("/sessions/open") Map<String,Object> openSession(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);int cid=toInt(val(b,"courseId"));int min=toInt(val(b,"minutes"));
  if(min<1)min=1;if(min>600)min=600;
  long now=System.currentTimeMillis();
  db.update("UPDATE sessions SET closed=1 WHERE course_id=? AND closed=0",cid);
  db.update("INSERT INTO sessions(course_id,opened_at,expires_at,closed) VALUES(?,?,?,0)",cid,now,now+min*60000L);
  return Map.of("ok",true);
 }
 @PostMapping("/sessions/close") Map<String,Object> closeSession(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);db.update("UPDATE sessions SET closed=1 WHERE course_id=? AND closed=0",toInt(val(b,"courseId")));return Map.of("ok",true);
 }
 @GetMapping("/sessions/active") Map<String,Object> activeSessions(HttpSession s){
  auth(s);long now=System.currentTimeMillis();
  var list=db.queryForList("SELECT x.course_id AS courseId,c.code AS code,c.name AS name,x.expires_at AS expiresAt FROM sessions x JOIN courses c ON c.id=x.course_id WHERE x.closed=0 AND x.expires_at>? ORDER BY x.opened_at DESC",now);
  return Map.of("now",now,"sessions",list);
 }

 // ---------- student portal (students log in with student code + password)
 @PostMapping("/student/login") Map<String,Object> studentLogin(@RequestBody Map<String,String>b,HttpSession s){
  String code=val(b,"code"),pw=b.getOrDefault("password","");
  String key="s:"+code;throttle(key);
  var r=db.queryForList("SELECT id,student_code,full_name,password_hash,must_change FROM students WHERE student_code=?",code);
  boolean ok=!r.isEmpty()&&passwordOk(pw,code,r.get(0).get("password_hash"));
  if(!ok){fail(key);throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"รหัสนักศึกษาหรือรหัสผ่านไม่ถูกต้อง");}
  FAILS.remove(key);
  var u=r.get(0);
  s.setAttribute("student",((Number)u.get("id")).intValue());
  return Map.of("ok",true,"code",u.get("student_code").toString(),"name",u.get("full_name").toString(),"mustChange",mustChange(u.get("must_change")));
 }
 @PostMapping("/student/logout") Map<String,Object> studentLogout(HttpSession s){s.removeAttribute("student");return Map.of("ok",true);}
 @GetMapping("/student/me") Map<String,Object> studentMe(HttpSession s){
  int sid=studentId(s);
  var r=db.queryForList("SELECT student_code,full_name,must_change FROM students WHERE id=?",sid);
  if(r.isEmpty()){s.removeAttribute("student");throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"กรุณาเข้าสู่ระบบ");}
  var u=r.get(0);
  return Map.of("code",u.get("student_code").toString(),"name",u.get("full_name").toString(),"mustChange",mustChange(u.get("must_change")));
 }
 @PostMapping("/student/password") Map<String,Object> studentPassword(@RequestBody Map<String,String>b,HttpSession s){
  int sid=studentId(s);
  String oldp=b.getOrDefault("oldPassword",""),newp=b.getOrDefault("newPassword","");
  var r=db.queryForList("SELECT student_code,password_hash FROM students WHERE id=?",sid);
  if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบนักศึกษา");
  String code=r.get(0).get("student_code").toString();
  if(!passwordOk(oldp,code,r.get(0).get("password_hash")))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"รหัสผ่านเดิมไม่ถูกต้อง");
  if(newp.length()<6)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"รหัสผ่านใหม่ต้องยาวอย่างน้อย 6 ตัวอักษร");
  if(newp.equals(code))throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"รหัสผ่านใหม่ต้องไม่ตรงกับรหัสนักศึกษา");
  db.update("UPDATE students SET password_hash=?,must_change=0 WHERE id=?",enc.encode(newp),sid);
  return Map.of("ok",true);
 }
 @GetMapping("/student/courses") List<Map<String,Object>> studentCourses(HttpSession s){
  int sid=studentId(s);
  return db.queryForList("SELECT c.id AS id,c.code AS code,c.name AS name,(SELECT COUNT(*) FROM attendance a WHERE a.course_id=c.id AND a.student_id=e.student_id) AS present,(SELECT COUNT(DISTINCT a2.attendance_date) FROM attendance a2 WHERE a2.course_id=c.id) AS total FROM enrollments e JOIN courses c ON c.id=e.course_id WHERE e.student_id=? ORDER BY c.code",sid);
 }
 @GetMapping("/student/history") List<Map<String,Object>> studentHistory(@RequestParam int courseId,HttpSession s){
  int sid=studentId(s);
  if(db.queryForList("SELECT 1 FROM enrollments WHERE course_id=? AND student_id=?",courseId,sid).isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"คุณไม่ได้อยู่ในรายชื่อของรายวิชานี้");
  return db.queryForList("SELECT d.attendance_date AS day,a.attendance_time AS time,CASE WHEN a.id IS NULL THEN 'ขาดเรียน' ELSE 'มาเรียน' END AS status FROM (SELECT DISTINCT attendance_date FROM attendance WHERE course_id=?) d LEFT JOIN attendance a ON a.attendance_date=d.attendance_date AND a.course_id=? AND a.student_id=? ORDER BY d.attendance_date DESC",courseId,courseId,sid);
 }
 // scanning the course QR (https://host/c/<token>) ends up here
 @PostMapping("/student/checkin") Map<String,Object> studentCheckin(@RequestBody Map<String,String>b,HttpSession s){
  int sid=studentId(s);
  String token=val(b,"token");
  if(token.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"QR Code ไม่ถูกต้อง");
  var c=db.queryForList("SELECT id,name,teacher_email FROM courses WHERE qr_token=?",token);
  if(c.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"QR Code ไม่ถูกต้อง หรือถูกเปลี่ยนแล้ว");
  int cid=((Number)c.get(0).get("id")).intValue();String cname=c.get(0).get("name").toString();
  if(db.queryForList("SELECT 1 FROM enrollments WHERE course_id=? AND student_id=?",cid,sid).isEmpty())throw new ResponseStatusException(HttpStatus.FORBIDDEN,"คุณไม่ได้อยู่ในรายชื่อของรายวิชา "+cname);
  var u=db.queryForList("SELECT student_code,full_name FROM students WHERE id=?",sid).get(0);
  String code=u.get("student_code").toString(),name=u.get("full_name").toString();
  var ex=db.queryForList("SELECT attendance_time FROM attendance WHERE course_id=? AND student_id=? AND attendance_date=?",cid,sid,LocalDate.now(ZONE).toString());
  if(!ex.isEmpty())return Map.of("ok",true,"already",true,"name",name,"course",cname,"time",String.valueOf(ex.get(0).get("attendance_time")));
  if(db.queryForList("SELECT 1 FROM sessions WHERE course_id=? AND closed=0 AND expires_at>?",cid,System.currentTimeMillis()).isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"อาจารย์ยังไม่เปิดรับเช็คชื่อ หรือหมดเวลาแล้ว");
  return record(cid,sid,code,name,cname,Objects.toString(c.get(0).get("teacher_email"),""));
 }

 // ---------- QR image (SVG) generated on the server, teacher only
 @GetMapping("/qr") ResponseEntity<String> qr(@RequestParam String data,HttpSession s) throws Exception{
  auth(s);
  if(data.isEmpty()||data.length()>300)throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ข้อมูลไม่ถูกต้อง");
  BitMatrix m=new QRCodeWriter().encode(data,BarcodeFormat.QR_CODE,0,0,Map.of(EncodeHintType.MARGIN,2));
  int w=m.getWidth(),h=m.getHeight();
  StringBuilder sb=new StringBuilder();
  sb.append("<svg xmlns=\"http://www.w3.org/2000/svg\" viewBox=\"0 0 ").append(w).append(' ').append(h).append("\" shape-rendering=\"crispEdges\">");
  sb.append("<rect width=\"").append(w).append("\" height=\"").append(h).append("\" fill=\"#fff\"/><path fill=\"#000\" d=\"");
  for(int y=0;y<h;y++)for(int x=0;x<w;x++)if(m.get(x,y))sb.append('M').append(x).append(' ').append(y).append("h1v1h-1z");
  sb.append("\"/></svg>");
  return ResponseEntity.ok().contentType(MediaType.valueOf("image/svg+xml")).body(sb.toString());
 }

 // ---------- teacher: reports and attendance corrections
 @DeleteMapping("/attendance") Map<String,Object> deleteAttendance(@RequestParam int courseId,@RequestParam int studentId,@RequestParam String date,HttpSession s){
  auth(s);
  db.update("DELETE FROM attendance WHERE course_id=? AND student_id=? AND attendance_date=?",courseId,studentId,dateOf(date));
  return Map.of("ok",true);
 }
 @PostMapping("/attendance/manual") Map<String,Object> manualAttend(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);int cid=toInt(val(b,"courseId"));int sid=toInt(val(b,"studentId"));String d=dateOf(val(b,"date"));
  String time=d.equals(LocalDate.now(ZONE).toString())?LocalTime.now(ZONE).format(TIME):"-";
  int n;
  try{n=db.update("INSERT INTO attendance(course_id,student_id,attendance_date,attendance_time,status) SELECT e.course_id,e.student_id,?,?,? FROM enrollments e WHERE e.course_id=? AND e.student_id=?",d,time,"มาเรียน",cid,sid);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"มีการเช็คชื่อแล้ว");}
  if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบนักศึกษาในรายวิชานี้");
  return Map.of("ok",true);
 }
 @GetMapping("/report") Map<String,Object> report(@RequestParam int courseId,@RequestParam(required=false) String date,HttpSession s){
  auth(s);var rows=db.queryForList("SELECT s.id AS student_id,s.student_code,s.full_name,CASE WHEN a.id IS NULL THEN 'ขาดเรียน' ELSE 'มาเรียน' END status,a.attendance_time FROM enrollments e JOIN students s ON s.id=e.student_id LEFT JOIN attendance a ON a.student_id=s.id AND a.course_id=e.course_id AND a.attendance_date=? WHERE e.course_id=? ORDER BY s.student_code",dateOf(date),courseId);
  long p=rows.stream().filter(x->"มาเรียน".equals(x.get("status"))).count();return Map.of("students",rows,"total",rows.size(),"present",p,"absent",rows.size()-p);
 }

 // ---------- backup / export (teacher only)
 @GetMapping("/backup") ResponseEntity<byte[]> backup(HttpSession s) throws Exception{
  auth(s);
  java.nio.file.Path p=java.nio.file.Files.createTempFile("backup",".db");
  java.nio.file.Files.delete(p);
  try{
   db.execute("VACUUM INTO '"+p.toString().replace("'","''")+"'");
   byte[] data=java.nio.file.Files.readAllBytes(p);
   return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"attendance-"+LocalDate.now(ZONE).toString().replace("-","")+".db\"").contentType(MediaType.APPLICATION_OCTET_STREAM).body(data);
  }finally{java.nio.file.Files.deleteIfExists(p);}
 }
 @GetMapping("/export.csv") ResponseEntity<byte[]> exportCsv(@RequestParam(required=false) Integer courseId,HttpSession s){
  auth(s);
  String sql="SELECT a.attendance_date,a.attendance_time,c.code AS ccode,c.name AS cname,s.student_code,s.full_name,a.status FROM attendance a JOIN courses c ON c.id=a.course_id JOIN students s ON s.id=a.student_id"+(courseId!=null?" WHERE a.course_id=?":"")+" ORDER BY a.attendance_date DESC,c.code,s.student_code";
  var rows=courseId!=null?db.queryForList(sql,courseId):db.queryForList(sql);
  String[] k={"attendance_date","attendance_time","ccode","cname","student_code","full_name","status"};
  StringBuilder sb=new StringBuilder("\uFEFF");
  sb.append("วันที่,เวลา,รหัสวิชา,ชื่อวิชา,รหัสนักศึกษา,ชื่อ-นามสกุล,สถานะ\r\n");
  for(var r:rows){
   for(int i=0;i<k.length;i++){if(i>0)sb.append(',');sb.append('"').append(Objects.toString(r.get(k[i]),"").replace("\"","\"\"")).append('"');}
   sb.append("\r\n");
  }
  return ResponseEntity.ok().header("Content-Disposition","attachment; filename=\"attendance.csv\"").contentType(new MediaType("text","csv",StandardCharsets.UTF_8)).body(sb.toString().getBytes(StandardCharsets.UTF_8));
 }
}
