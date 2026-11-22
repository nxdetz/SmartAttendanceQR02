package com.smartattendance.web;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

// /c/<course token> (the QR code of a course) and /student both open the student page
@RestController
public class PublicController {
 @GetMapping({"/c/{token}","/student"})
 ResponseEntity<byte[]> studentPage() throws Exception {
  try(InputStream in=new ClassPathResource("static/student.html").getInputStream()){
   byte[] body=in.readAllBytes();
   return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8)).header("Cache-Control","no-store").body(body);
  }
 }
}
