package com.smartattendance.web;
import java.util.UUID;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class AttendanceApplication {
 public static void main(String[] args){SpringApplication.run(AttendanceApplication.class,args);}

 // adds a column to an older database only when it is missing
 static void addColumn(JdbcTemplate db,String table,String col,String type){
  boolean has=db.queryForList("PRAGMA table_info("+table+")").stream().anyMatch(c->col.equalsIgnoreCase(String.valueOf(c.get("name"))));
  if(!has)db.execute("ALTER TABLE "+table+" ADD COLUMN "+col+" "+type);
 }

 @Bean CommandLineRunner init(JdbcTemplate db){
  return args->{
   db.execute("PRAGMA foreign_keys=ON");
   db.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY,username TEXT UNIQUE,password_hash TEXT,full_name TEXT,email TEXT)");
   db.execute("CREATE TABLE IF NOT EXISTS courses(id INTEGER PRIMARY KEY,code TEXT UNIQUE,name TEXT,teacher_email TEXT,qr_token TEXT)");
   db.execute("CREATE TABLE IF NOT EXISTS students(id INTEGER PRIMARY KEY,student_code TEXT UNIQUE,full_name TEXT,password_hash TEXT,must_change INTEGER DEFAULT 1)");
   db.execute("CREATE TABLE IF NOT EXISTS enrollments(course_id INTEGER,student_id INTEGER,PRIMARY KEY(course_id,student_id),FOREIGN KEY(course_id) REFERENCES courses(id),FOREIGN KEY(student_id) REFERENCES students(id))");
   db.execute("CREATE TABLE IF NOT EXISTS attendance(id INTEGER PRIMARY KEY,course_id INTEGER,student_id INTEGER,attendance_date TEXT,attendance_time TEXT,status TEXT,UNIQUE(course_id,student_id,attendance_date),FOREIGN KEY(course_id) REFERENCES courses(id),FOREIGN KEY(student_id) REFERENCES students(id))");
   db.execute("CREATE TABLE IF NOT EXISTS sessions(id INTEGER PRIMARY KEY,course_id INTEGER,opened_at INTEGER,expires_at INTEGER,closed INTEGER DEFAULT 0,FOREIGN KEY(course_id) REFERENCES courses(id))");
   // upgrade databases created by older versions
   addColumn(db,"courses","qr_token","TEXT");
   addColumn(db,"students","password_hash","TEXT");
   addColumn(db,"students","must_change","INTEGER DEFAULT 1");
   for(Integer id:db.queryForList("SELECT id FROM courses WHERE qr_token IS NULL OR qr_token=''",Integer.class))
    db.update("UPDATE courses SET qr_token=? WHERE id=?",UUID.randomUUID().toString().replace("-",""),id);
   db.execute("CREATE UNIQUE INDEX IF NOT EXISTS idx_courses_qr_token ON courses(qr_token)");
   String u=System.getenv().getOrDefault("ADMIN_USERNAME","admin"), p=System.getenv().getOrDefault("ADMIN_PASSWORD","admin123");
   Integer n=db.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,u);
   if(n!=null&&n==0)db.update("INSERT INTO users(username,password_hash,full_name,email) VALUES(?,?,?,?)",u,new BCryptPasswordEncoder().encode(p),"ผู้ดูแลระบบ",System.getenv().getOrDefault("TEACHER_EMAIL",""));
  };
 }
}
