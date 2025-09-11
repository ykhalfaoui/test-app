package com.example.kyc.kyc360;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
public class Kyc360Controller {
  @GetMapping("/api/parties/{id}/kyc360")
  public ResponseEntity<Map<String,Object>> kyc(@PathVariable String id){
    return ResponseEntity.ok(Map.of(
      "partyId", id,
      "currentBlocks", "TODO",
      "family", "TODO",
      "targets", "TODO"
    ));
  }
}
