package com.example.kyc.party;
import jakarta.validation.constraints.NotBlank;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController @RequestMapping("/api/parties")
public class PartyController {
  private final PartyRepository repo;
  public PartyController(PartyRepository repo){ this.repo = repo; }

  record CreateParty(@NotBlank String type, String subType, String externalRef){}

  @PostMapping public ResponseEntity<Party> create(@RequestBody CreateParty req){
    Party p = new Party(); p.setType(req.type()); p.setSubType(req.subType()); p.setExternalRef(req.externalRef());
    return ResponseEntity.ok(repo.save(p));
  }

  @GetMapping("/{id}") public ResponseEntity<Party> get(@PathVariable String id){ return repo.findById(id).map(ResponseEntity::ok).orElse(ResponseEntity.notFound().build()); }
}
