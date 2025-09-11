package com.example.kyc.hits;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/hits")
public class HitController {
  private final HitService service;
  public HitController(HitService service){ this.service = service; }
  record HitRequest(@NotBlank String partyId, @NotBlank String hitType){}
  @PostMapping("/qualified")
  public ResponseEntity<String> createQualified(@RequestBody HitRequest req){
    return ResponseEntity.ok(service.receiveQualifiedHit(req.partyId(), req.hitType()));
  }
}
