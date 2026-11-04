package com.smartattendance.web;
import java.time.*;
import java.time.format.DateTimeFormatter;
import java.util.*;
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
 private final JdbcTemplate db;private final ObjectProvider<JavaMailSender> mail;private final BCryptPasswordEncoder enc=new BCryptPasswordEncoder();
 public ApiController(JdbcTemplate d,ObjectProvider<JavaMailSender> m){db=d;mail=m;}

 void auth(HttpSession s){if(s.getAttribute("user")==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"กรุณาเข้าสู่ระบบ");}
 String val(Map<String,String>b,String k){return b.getOrDefault(k,"").trim();}
 int toInt(String v){try{return Integer.parseInt(v.trim());}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ข้อมูลไม่ถูกต้อง");}}
 String newToken(){return UUID.randomUUID().toString().replace("-","");}
 String dateOf(String v){
  if(v==null||v.isBlank())return LocalDate.now(ZONE).toString();
  try{return LocalDate.parse(v.trim()).toString();}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"รูปแบบวันที่ไม่ถูกต้อง");}
 }

 // accepts a plain student code or a scanned URL like https://host/s/<token>
 String resolveCode(String input){
  int i=input.indexOf("/s/");
  if(i>=0){
   String t=input.substring(i+3);int q=t.indexOf('?');if(q>=0)t=t.substring(0,q);t=t.replace("/","");
   var r=db.queryForList("SELECT student_code FROM students WHERE qr_token=?",t);
   if(!r.isEmpty())return r.get(0).get("student_code").toString();
  }
  return input;
 }

 @PostMapping("/login") Map<String,Object> login(@RequestBody Map<String,String>b,HttpSession s){
  var r=db.queryForList("SELECT password_hash,full_name FROM users WHERE username=?",val(b,"username"));
  if(r.isEmpty()||!enc.matches(val(b,"password"),r.get(0).get("password_hash").toString()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง");
  s.setAttribute("user",val(b,"username"));s.setAttribute("name",r.get(0).get("full_name"));return Map.of("ok",true,"name",r.get(0).get("full_name"));
 }
 @GetMapping("/me") Map<String,Object> me(HttpSession s){auth(s);return Map.of("name",s.getAttribute("name"));}
 @PostMapping("/logout") Map<String,Object> logout(HttpSession s){s.invalidate();return Map.of("ok",true);}

 @GetMapping("/courses") List<Map<String,Object>> courses(HttpSession s){auth(s);return db.queryForList("SELECT * FROM courses ORDER BY code");}
 @PostMapping("/courses") Map<String,Object> addCourse(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);
  if(val(b,"code").isEmpty()||val(b,"name").isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"กรุณากรอกรหัสวิชาและชื่อวิชา");
  try{db.update("INSERT INTO courses(code,name,teacher_email) VALUES(?,?,?)",val(b,"code"),val(b,"name"),val(b,"email"));return Map.of("ok",true);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสวิชาซ้ำหรือข้อมูลไม่ครบ");}
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
 @PostMapping("/students/{id}/regen-qr") Map<String,Object> regenQr(@PathVariable int id,HttpSession s){
  auth(s);
  int n=db.update("UPDATE students SET qr_token=? WHERE id=?",newToken(),id);
  if(n==0)throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบนักศึกษา");
  return Map.of("ok",true);
 }
 @GetMapping("/enrollments") List<Map<String,Object>> enrollments(@RequestParam int courseId,HttpSession s){
  auth(s);
  return db.queryForList("SELECT s.id,s.student_code,s.full_name FROM enrollments e JOIN students s ON s.id=e.student_id WHERE e.course_id=? ORDER BY s.student_code",courseId);
 }
 @DeleteMapping("/enroll") Map<String,Object> unenroll(@RequestParam int courseId,@RequestParam int studentId,HttpSession s){
  auth(s);
  db.update("DELETE FROM enrollments WHERE course_id=? AND student_id=?",courseId,studentId);
  return Map.of("ok",true);
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
 @GetMapping("/students") List<Map<String,Object>> students(HttpSession s){auth(s);return db.queryForList("SELECT * FROM students ORDER BY student_code");}
 @PostMapping("/students") Map<String,Object> addStudent(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);
  String code=val(b,"code"),name=val(b,"name");
  if(code.isEmpty()||name.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"กรุณากรอกรหัสและชื่อนักศึกษา");
  try{db.update("INSERT INTO students(student_code,full_name,qr_token) VALUES(?,?,?)",code,name,newToken());return Map.of("ok",true);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสนักศึกษาซ้ำหรือข้อมูลไม่ครบ");}
 }
 @PostMapping("/enroll") Map<String,Object> enroll(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);
  try{db.update("INSERT INTO enrollments(course_id,student_id) SELECT ?,id FROM students WHERE student_code=?",Integer.parseInt(val(b,"courseId")),val(b,"code"));return Map.of("ok",true);}
  catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ไม่พบรหัสนักศึกษา หรือเคยลงทะเบียนแล้ว");}
 }

 // shared by teacher check-in and student self check-in
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

 @PostMapping("/attendance") Map<String,Object> attend(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);int cid=toInt(val(b,"courseId"));String code=resolveCode(val(b,"code"));
  var r=db.queryForList("SELECT s.id,s.full_name,c.name,c.teacher_email FROM students s JOIN enrollments e ON e.student_id=s.id JOIN courses c ON c.id=e.course_id WHERE s.student_code=? AND c.id=?",code,cid);
  if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบผู้เรียนในรายวิชานี้");
  var x=r.get(0);
  return record(cid,x.get("id"),code,x.get("full_name").toString(),x.get("name").toString(),Objects.toString(x.get("teacher_email"),""));
 }

 // ---- student self check-in: no login, the secret token in the QR identifies the student
 @PostMapping("/public/checkin") Map<String,Object> publicCheckin(@RequestBody Map<String,String>b){
  String token=val(b,"token");
  if(token.isEmpty())throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"QR Code ไม่ถูกต้อง");
  var st=db.queryForList("SELECT id,student_code,full_name FROM students WHERE qr_token=?",token);
  if(st.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"QR Code ไม่ถูกต้อง หรือไม่พบนักศึกษา");
  var u=st.get(0);Object sid=u.get("id");String code=u.get("student_code").toString(),name=u.get("full_name").toString();
  var r=db.queryForList("SELECT c.id AS cid,c.name AS cname,c.teacher_email AS temail FROM sessions x JOIN courses c ON c.id=x.course_id JOIN enrollments e ON e.course_id=c.id WHERE e.student_id=? AND x.closed=0 AND x.expires_at>? ORDER BY x.opened_at DESC",sid,System.currentTimeMillis());
  if(r.isEmpty())throw new ResponseStatusException(HttpStatus.CONFLICT,"ยังไม่เปิดให้เช็คชื่อ หรือคุณไม่ได้ลงทะเบียนในวิชาที่เปิดอยู่");
  var c=r.get(0);int cid=((Number)c.get("cid")).intValue();String cname=c.get("cname").toString();
  var ex=db.queryForList("SELECT attendance_time FROM attendance WHERE course_id=? AND student_id=? AND attendance_date=?",cid,sid,LocalDate.now(ZONE).toString());
  if(!ex.isEmpty())return Map.of("ok",true,"already",true,"name",name,"course",cname,"time",String.valueOf(ex.get(0).get("attendance_time")));
  return record(cid,sid,code,name,cname,Objects.toString(c.get("temail"),""));
 }

 // ---- teacher opens / closes a check-in window for a course
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

 // ---- QR image (SVG) generated on the server, teacher only
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
}
