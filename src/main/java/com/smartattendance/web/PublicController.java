package com.smartattendance.web;
import java.io.InputStream;
import java.nio.charset.StandardCharsets;
import org.springframework.core.io.ClassPathResource;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

@RestController
public class PublicController {
 @GetMapping("/s/{token}")
 ResponseEntity<byte[]> checkinPage(@PathVariable String token) throws Exception {
  try(InputStream in=new ClassPathResource("static/checkin.html").getInputStream()){
   byte[] body=in.readAllBytes();
   return ResponseEntity.ok().contentType(new MediaType("text","html",StandardCharsets.UTF_8)).header("Cache-Control","no-store").body(body);
  }
 }
}
