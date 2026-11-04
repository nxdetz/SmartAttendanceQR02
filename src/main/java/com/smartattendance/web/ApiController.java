package com.smartattendance.web;
import java.time.*;import java.util.*;
import jakarta.servlet.http.HttpSession;
import org.springframework.http.*;import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mail.SimpleMailMessage;import org.springframework.mail.javamail.JavaMailSender;
import org.springframework.beans.factory.ObjectProvider;import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;
import org.springframework.web.bind.annotation.*;
import org.springframework.web.server.ResponseStatusException;

@RestController @RequestMapping("/api")
public class ApiController{
 static final ZoneId ZONE=ZoneId.of(System.getenv().getOrDefault("APP_TZ","Asia/Bangkok"));
 private final JdbcTemplate db;private final ObjectProvider<JavaMailSender> mail;private final BCryptPasswordEncoder enc=new BCryptPasswordEncoder();
 public ApiController(JdbcTemplate d,ObjectProvider<JavaMailSender> m){db=d;mail=m;}
 void auth(HttpSession s){if(s.getAttribute("user")==null)throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"กรุณาเข้าสู่ระบบ");}
 String val(Map<String,String>b,String k){return b.getOrDefault(k,"").trim();}
 @PostMapping("/login") Map<String,Object> login(@RequestBody Map<String,String>b,HttpSession s){
  var r=db.queryForList("SELECT password_hash,full_name FROM users WHERE username=?",val(b,"username"));
  if(r.isEmpty()||!enc.matches(val(b,"password"),r.get(0).get("password_hash").toString()))throw new ResponseStatusException(HttpStatus.UNAUTHORIZED,"ชื่อผู้ใช้หรือรหัสผ่านไม่ถูกต้อง");
  s.setAttribute("user",val(b,"username"));s.setAttribute("name",r.get(0).get("full_name"));return Map.of("ok",true,"name",r.get(0).get("full_name"));
 }
 @GetMapping("/me") Map<String,Object> me(HttpSession s){auth(s);return Map.of("name",s.getAttribute("name"));}
 @PostMapping("/logout") Map<String,Object> logout(HttpSession s){s.invalidate();return Map.of("ok",true);}
 @GetMapping("/courses") List<Map<String,Object>> courses(HttpSession s){auth(s);return db.queryForList("SELECT * FROM courses ORDER BY code");}
 @PostMapping("/courses") Map<String,Object> addCourse(@RequestBody Map<String,String>b,HttpSession s){auth(s);try{db.update("INSERT INTO courses(code,name,teacher_email) VALUES(?,?,?)",val(b,"code"),val(b,"name"),val(b,"email"));return Map.of("ok",true);}catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสวิชาซ้ำหรือข้อมูลไม่ครบ");}}
 @GetMapping("/students") List<Map<String,Object>> students(HttpSession s){auth(s);return db.queryForList("SELECT * FROM students ORDER BY student_code");}
 @PostMapping("/students") Map<String,Object> addStudent(@RequestBody Map<String,String>b,HttpSession s){auth(s);try{db.update("INSERT INTO students(student_code,full_name) VALUES(?,?)",val(b,"code"),val(b,"name"));return Map.of("ok",true);}catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"รหัสนักศึกษาซ้ำหรือข้อมูลไม่ครบ");}}
 @PostMapping("/enroll") Map<String,Object> enroll(@RequestBody Map<String,String>b,HttpSession s){auth(s);try{db.update("INSERT INTO enrollments(course_id,student_id) SELECT ?,id FROM students WHERE student_code=?",Integer.parseInt(val(b,"courseId")),val(b,"code"));return Map.of("ok",true);}catch(Exception e){throw new ResponseStatusException(HttpStatus.BAD_REQUEST,"ไม่พบรหัสนักศึกษา หรือเคยลงทะเบียนแล้ว");}}
 @PostMapping("/attendance") Map<String,Object> attend(@RequestBody Map<String,String>b,HttpSession s){
  auth(s);int cid=Integer.parseInt(val(b,"courseId"));String code=val(b,"code"),date=LocalDate.now(ZONE).toString(),time=LocalTime.now(ZONE).withNano(0).toString();
  var r=db.queryForList("SELECT s.id,s.full_name,c.name,c.teacher_email FROM students s JOIN enrollments e ON e.student_id=s.id JOIN courses c ON c.id=e.course_id WHERE s.student_code=? AND c.id=?",code,cid);
  if(r.isEmpty())throw new ResponseStatusException(HttpStatus.NOT_FOUND,"ไม่พบผู้เรียนในรายวิชานี้");
  var x=r.get(0);try{db.update("INSERT INTO attendance(course_id,student_id,attendance_date,attendance_time,status) VALUES(?,?,?,?,?)",cid,x.get("id"),date,time,"มาเรียน");}catch(Exception e){throw new ResponseStatusException(HttpStatus.CONFLICT,"เช็คชื่อวันนี้แล้ว");}
  boolean sent=false;String to=Objects.toString(x.get("teacher_email"),"");
  if(!to.isBlank()&&mail.getIfAvailable()!=null)try{SimpleMailMessage m=new SimpleMailMessage();m.setTo(to);m.setSubject("แจ้งเตือนการเข้าเรียน - "+x.get("name"));m.setText("รายวิชา: "+x.get("name")+"\nรหัส: "+code+"\nชื่อ: "+x.get("full_name")+"\nวันที่: "+date+" เวลา: "+time);mail.getObject().send(m);sent=true;}catch(Exception ignored){}
  return Map.of("ok",true,"name",x.get("full_name"),"time",time,"emailSent",sent);
 }
 @GetMapping("/report") Map<String,Object> report(@RequestParam int courseId,HttpSession s){
  auth(s);var rows=db.queryForList("SELECT s.student_code,s.full_name,CASE WHEN a.id IS NULL THEN 'ขาดเรียน' ELSE 'มาเรียน' END status,a.attendance_time FROM enrollments e JOIN students s ON s.id=e.student_id LEFT JOIN attendance a ON a.student_id=s.id AND a.course_id=e.course_id AND a.attendance_date=? WHERE e.course_id=? ORDER BY s.student_code",LocalDate.now(ZONE).toString(),courseId);
  long p=rows.stream().filter(x->"มาเรียน".equals(x.get("status"))).count();return Map.of("students",rows,"total",rows.size(),"present",p,"absent",rows.size()-p);
 }
}
