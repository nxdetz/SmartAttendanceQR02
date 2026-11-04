package com.smartattendance.web;
import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Bean;
import org.springframework.boot.CommandLineRunner;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.security.crypto.bcrypt.BCryptPasswordEncoder;

@SpringBootApplication
public class AttendanceApplication {
 public static void main(String[] args){SpringApplication.run(AttendanceApplication.class,args);}
 @Bean CommandLineRunner init(JdbcTemplate db){
  return args->{
   db.execute("PRAGMA foreign_keys=ON");
   db.execute("CREATE TABLE IF NOT EXISTS users(id INTEGER PRIMARY KEY,username TEXT UNIQUE,password_hash TEXT,full_name TEXT,email TEXT)");
   db.execute("CREATE TABLE IF NOT EXISTS courses(id INTEGER PRIMARY KEY,code TEXT UNIQUE,name TEXT,teacher_email TEXT)");
   db.execute("CREATE TABLE IF NOT EXISTS students(id INTEGER PRIMARY KEY,student_code TEXT UNIQUE,full_name TEXT)");
   db.execute("CREATE TABLE IF NOT EXISTS enrollments(course_id INTEGER,student_id INTEGER,PRIMARY KEY(course_id,student_id),FOREIGN KEY(course_id) REFERENCES courses(id),FOREIGN KEY(student_id) REFERENCES students(id))");
   db.execute("CREATE TABLE IF NOT EXISTS attendance(id INTEGER PRIMARY KEY,course_id INTEGER,student_id INTEGER,attendance_date TEXT,attendance_time TEXT,status TEXT,UNIQUE(course_id,student_id,attendance_date),FOREIGN KEY(course_id) REFERENCES courses(id),FOREIGN KEY(student_id) REFERENCES students(id))");
   String u=System.getenv().getOrDefault("ADMIN_USERNAME","admin"), p=System.getenv().getOrDefault("ADMIN_PASSWORD","admin123");
   Integer n=db.queryForObject("SELECT COUNT(*) FROM users WHERE username=?",Integer.class,u);
   if(n!=null&&n==0)db.update("INSERT INTO users(username,password_hash,full_name,email) VALUES(?,?,?,?)",u,new BCryptPasswordEncoder().encode(p),"ผู้ดูแลระบบ",System.getenv().getOrDefault("TEACHER_EMAIL",""));
  };
 }
}